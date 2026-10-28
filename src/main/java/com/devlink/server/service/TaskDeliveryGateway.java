package com.devlink.server.service;

import com.devlink.server.dto.TaskDeliveryResult;

/** Integration contract implemented by the realtime module (owner: member 6). */
public interface TaskDeliveryGateway {
    /** Check actual receiver/broker reachability; DB ONLINE alone is insufficient. */
    boolean isReachable(Long receiverAgentId);

    /** Called after commit. Bound ACK wait time; DELIVERED means ACK, not socket write. */
    TaskDeliveryResult deliver(TaskMessage message);

    record TaskMessage(Long taskId, Long teamId, Long requesterAgentId,
            Long receiverAgentId, String prompt, Long version) { }
}
