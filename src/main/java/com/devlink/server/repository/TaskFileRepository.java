package com.devlink.server.repository;

import java.util.List;
import com.devlink.server.domain.taskfile.TaskFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {
    List<TaskFile> findByTaskId(Long taskId);
}
