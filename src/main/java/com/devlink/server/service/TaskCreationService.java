package com.devlink.server.service;

import java.time.LocalDateTime;
import java.util.List;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.DeliveryStatus;
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
    private final TransactionTemplate transaction;

    public TaskCreationService(TaskRepository taskRepository, TaskEventRepository taskEventRepository,
            AgentRepository agentRepository,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.taskEventRepository = taskEventRepository;
        this.agentRepository = agentRepository;
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

        // 전송 결과 받기
        TaskDeliveryResult delivery = deliverTask(task);

        // 결과에 따라 이력 저장
        task = saveDeliveryEvent(task, delivery);

        // 같은 결과를 응답에 넣기
        return CreateTaskResponse.of(task, false, delivery);
    }

    private TaskDeliveryResult deliverTask(Task task) {
        // TODO(6번 연동): 전송 구현체를 주입하고 아래 호출을 연결한다.
        // var message = new TaskDeliveryGateway.TaskMessage(task.getId(), task.getTeamId(),
        //         task.getRequesterAgentId(), task.getReceiverAgentId(), task.getPrompt(), task.getVersion());
        // return taskDeliveryGateway.deliver(message);
        return TaskDeliveryResult.unknown();
    }

    private Task saveDeliveryEvent(Task task, TaskDeliveryResult delivery) {
        return transaction.execute(tx -> {
            Task savedTask = taskRepository.findById(task.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            switch (delivery.status()) {
                case DELIVERED -> {
                    // 전달 성공: deliveredAt 갱신 + DELIVERED 이력 저장
                    if (savedTask.getDeliveredAt() == null) {
                        savedTask.markDelivered(LocalDateTime.now());
                    }
                    taskEventRepository.save(TaskEvent.of(
                            task.getId(), TaskEventType.DELIVERED, null));
                }
                case FAILED -> {
                    // 전달 실패: DELIVERY_FAILED 이력과 실패 사유 저장
                    taskEventRepository.save(TaskEvent.of(
                            task.getId(), TaskEventType.DELIVERY_FAILED, delivery.reasonCode()));
                }
                case UNKNOWN -> {
                    // 결과 미확정: 갱신하거나 이력을 저장하지 않는다.
                    return savedTask;
                }
            }
            // deliveredAt UPDATE에 따른 최신 version을 응답에 담는다.
            taskRepository.flush();
            return savedTask;
        });
    }

    private TaskDeliveryResult findLatestDelivery(Long taskId) {
        return transaction.execute(tx -> taskEventRepository
                .findFirstByTaskIdAndEventTypeInOrderByIdDesc(
                        taskId, List.of(TaskEventType.DELIVERED, TaskEventType.DELIVERY_FAILED))
                .map(event -> new TaskDeliveryResult(
                        event.getEventType() == TaskEventType.DELIVERED
                                ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED,
                        event.getEventType() == TaskEventType.DELIVERED ? null : event.getContent()))
                .orElseGet(TaskDeliveryResult::unknown));
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
