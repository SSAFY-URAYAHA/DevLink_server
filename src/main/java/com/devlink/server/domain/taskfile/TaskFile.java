package com.devlink.server.domain.taskfile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: task.
@Entity
@Table(name = "task_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "task_id", length = 255, nullable = false)
    private Long taskId;

    @Column(name = "relative_path", length = 500, nullable = false)
    private String relativePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20, nullable = false)
    private ChangeType changeType;

    @Column(name = "diff_text", length = 255, nullable = true, columnDefinition = "TEXT")
    private String diffText;

    public static TaskFile of(Long taskId, String relativePath, ChangeType changeType, String diffText) {
        requireValue(taskId, "taskId");
        requireValue(relativePath, "relativePath");
        requireValue(changeType, "changeType");
        TaskFile entity = new TaskFile();
        entity.taskId = taskId;
        entity.relativePath = relativePath;
        entity.changeType = changeType;
        entity.diffText = diffText;
        return entity;
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
