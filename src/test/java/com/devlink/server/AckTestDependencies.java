package com.devlink.server;

import com.devlink.server.domain.task.Task;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** ACK 프로토콜 단위 테스트에서 사용하는 DB 의존성. 실제 원자성은 통합 테스트로 검증한다. */
public final class AckTestDependencies {
    private AckTestDependencies() { }
    public static TaskRepository tasks() {
        TaskRepository tasks = mock(TaskRepository.class);
        when(tasks.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.of(mock(Task.class)));
        return tasks;
    }
    public static TaskEventRepository history() { return mock(TaskEventRepository.class); }
    public static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return manager;
    }
}
