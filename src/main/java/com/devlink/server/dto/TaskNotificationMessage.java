package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.task.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 요청자에게 전달할 상태·결과 스냅샷이다. 프롬프트·토큰·요청 키는 포함하지 않는다. */
public record TaskNotificationMessage(String type, @JsonProperty("event_id") String eventId, Payload payload) {
    public record Payload(
            @JsonProperty("task_id") long taskId,
            @JsonProperty("team_id") long teamId,
            @JsonProperty("requester_agent_id") long requesterAgentId,
            @JsonProperty("receiver_agent_id") long receiverAgentId,
            TaskStatus status,
            long version,
            @JsonProperty("result_text") String resultText,
            @JsonProperty("partially_modified") boolean partiallyModified,
            @JsonProperty("started_at") LocalDateTime startedAt,
            @JsonProperty("completed_at") LocalDateTime completedAt) { }
}
