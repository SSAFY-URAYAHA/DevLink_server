package com.devlink.server.domain;

import java.security.Principal;

/** Identity returned by Agent token verification. Never contains the raw token or its hash. */
public record AgentPrincipal(long agentId, long ownerUserId, long teamId) implements Principal {
    @Override
    public String getName() {
        return Long.toString(agentId);
    }
}
