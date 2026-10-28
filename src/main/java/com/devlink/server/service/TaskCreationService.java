package com.devlink.server.service;


import java.util.List;
import java.time.LocalDateTime;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.dto.TaskDeliveryResult;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskCreationService {
    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;
    private final AgentRepository agentRepository;
    private final TaskRoutingService taskRoutingService;
    private final TransactionTemplate transaction;

    public TaskCreationService(TaskRepository taskRepository, TaskEventRepository taskEventRepository,
            AgentRepository agentRepository, TaskRoutingService taskRoutingService,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.taskEventRepository = taskEventRepository;
        this.agentRepository = agentRepository;
        this.taskRoutingService = taskRoutingService;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreateTaskResponse create(Long teamId, Long userId, CreateTaskRequest request) {
        // 트랜잭션 시작 → Task(PENDING) + TaskEvent(CREATED) 저장 → 커밋
        Creation creation = transaction.execute(tx -> createTask(teamId, userId, request));

        Task task = creation.task();
        if (creation.replayed()) {
            TaskDeliveryResult delivery = findLatestDelivery(task.getId());
            return CreateTaskResponse.of(task, true, delivery);
        }

        // 전송 시작 이력을 커밋한 뒤 네트워크 호출을 수행한다.
        Long taskId = task.getId();
        transaction.executeWithoutResult(tx -> taskEventRepository.save(
                TaskEvent.of(taskId, TaskEventType.DELIVERING, "DELIVERING")));

        // 전송 결과 받기
        TaskDeliveryResult delivery = deliverTask(task);

        // 결과에 따라 이력 저장
        task = saveDeliveryEvent(task, delivery);

        // 같은 결과를 응답에 넣기
        return CreateTaskResponse.of(task, false, delivery);
    }

    private TaskDeliveryResult deliverTask(Task task) {
        var routing = taskRoutingService.sendTask(task.getId());
        return new TaskDeliveryResult(routing.status(),
                routing.status() == Status.SENT ? null : routing.status().name());
    }

    private Task saveDeliveryEvent(Task task, TaskDeliveryResult delivery) {
        return transaction.execute(tx -> {
            Task savedTask = taskRepository.findById(task.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            switch (delivery.status()) {
                case SENT -> {
                    savedTask.markDelivered(LocalDateTime.now());
                    taskEventRepository.save(TaskEvent.of(
                            task.getId(), TaskEventType.DELIVERED, delivery.status().name()));
                }
                case RECEIVER_OFFLINE, CONNECTION_UNAVAILABLE, CONNECTION_REPLACED,
                        TRANSPORT_ERROR, TASK_NOT_PENDING ->
                    taskEventRepository.save(TaskEvent.of(
                            task.getId(), TaskEventType.DELIVERY_FAILED, delivery.reasonCode()));
            }
            return savedTask;
        });
    }

    private TaskDeliveryResult findLatestDelivery(Long taskId) {
        return transaction.execute(tx -> taskEventRepository
                .findFirstByTaskIdAndEventTypeInOrderByIdDesc(taskId,
                        List.of(TaskEventType.DELIVERING, TaskEventType.DELIVERED, TaskEventType.DELIVERY_FAILED))
                .filter(event -> event.getEventType() != TaskEventType.DELIVERING)
                .map(event -> {
                    Status status = event.getEventType() == TaskEventType.DELIVERED
                            ? Status.SENT : Status.valueOf(event.getContent());
                    return new TaskDeliveryResult(status, status == Status.SENT ? null : status.name());
                })
                .orElse(null)); // 아직 라우팅 결과가 없거나 이전 형식의 이력만 존재하는 경우
    }
    private Creation createTask(Long teamId, Long userId, CreateTaskRequest request) {
        // 1. 요청 키로 생성된 작업 찾기
        var existing = taskRepository.findByRequesterUserIdAndRequestKey(userId, request.requestKey());
        // 2. 기존 작업이 있으면 요청 내용 비교
        if (existing.isPresent()) {
            Task task = existing.get();
            if (!task.getTeamId().equals(teamId)
                    || !task.getRequesterAgentId().equals(request.requesterAgentId())
                    || !task.getReceiverAgentId().equals(request.receiverAgentId())
                    || !task.getPrompt().equals(request.prompt())) {
                // 2-1. 기존 작업이 있는데 내용이 다른 경우 요청키를 잘못 재사용한것
                throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
            }
            return new Creation(task, true);
        }

        // 3. 신규 요청인 경우 수신 Agent 확인
        requireReceiver(teamId, request.receiverAgentId());
        // 3-1. Task(새 작업) 저장
        Task task = taskRepository.save(Task.create(request.requestKey(), teamId,
                request.requesterAgentId(), userId, request.receiverAgentId(), request.prompt()));
        // 3-2. TaskEvent(생성 이력) 저장
        taskEventRepository.save(TaskEvent.of(task.getId(), TaskEventType.CREATED, null));
        return new Creation(task, false);
    }

    private void requireReceiver(Long teamId, Long agentId) {
        // 1. 수신 Agent가 존재하는가?
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 2. URL로 받은 팀과 같은가?
        if (!agent.getTeamId().equals(teamId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private record Creation(Task task, boolean replayed) { }
}
