package com.devlink.server.domain;

import java.io.Serializable;
import java.security.Principal;

public record UserPrincipal(long userId) implements Principal, Serializable {
    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
