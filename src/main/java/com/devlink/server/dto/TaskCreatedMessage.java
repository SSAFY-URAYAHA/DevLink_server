package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.task.Task;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 작업 전달에 필요한 정보만 담고, 토큰·요청 키·작업 결과는 메시지에 포함하지 않는다. */
public record TaskCreatedMessage(String type, @JsonProperty("event_id") String eventId, Payload payload) {
    public static TaskCreatedMessage from(String eventId, Task task) {
        return new TaskCreatedMessage("TASK_CREATED", eventId,
                new Payload(task.getId(), task.getTeamId(), task.getRequesterAgentId(),
                        task.getReceiverAgentId(), task.getPrompt(), task.getCreatedAt()));
    }

    public record Payload(
            @JsonProperty("task_id") long taskId,
            @JsonProperty("team_id") long teamId,
            @JsonProperty("requester_agent_id") long requesterAgentId,
            @JsonProperty("receiver_agent_id") long receiverAgentId,
            String prompt,
            @JsonProperty("created_at") LocalDateTime createdAt) { }
}
