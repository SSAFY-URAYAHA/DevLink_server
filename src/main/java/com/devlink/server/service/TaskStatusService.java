package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.ChangeTaskStatusResponse;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskStatusService {
    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;

    @Transactional
    public ChangeTaskStatusResponse changeStatus(Long taskId, Long agentId, ChangeTaskStatusRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // TODO: 호출부가 Bearer 토큰 인증 객체에서 추출한 agentId를 전달하도록 연동한다.
        if (!task.getReceiverAgentId().equals(agentId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!task.getVersion().equals(request.version())) {
            throw new BusinessException(ErrorCode.TASK_VERSION_CONFLICT);
        }

        // 요청한 상태와 기대값을 비교
        TaskStatus previous = task.getStatus();
        TaskStatus expected = switch (request.status()) {
            case IN_PROGRESS, REJECTED -> TaskStatus.PENDING;
            case FAILED -> TaskStatus.IN_PROGRESS;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
        if (previous != expected) {         // 허용되지 않은 상태전이는 예외
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }

        String description;
        switch (request.status()) {
            case IN_PROGRESS -> {
                task.accept(agentId);
                description = "작업 수락";
            }
            case REJECTED -> {
                task.reject(agentId, request.rejectionReason());
                description = request.rejectionReason();
            }
            case FAILED -> {
                task.fail(agentId, request.failureReason(), request.partiallyModified());
                description = request.failureReason();
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        taskEventRepository.save(TaskEvent.of(taskId, TaskEventType.STATUS_CHANGED,
                previous + " → " + task.getStatus() + ": " + description));
        // 이력과 상태를 함께 flush한다. 동시 변경은 @Version으로 검출하고 전체 롤백한다.
        taskRepository.flush();
        return ChangeTaskStatusResponse.from(task);
    }
}
