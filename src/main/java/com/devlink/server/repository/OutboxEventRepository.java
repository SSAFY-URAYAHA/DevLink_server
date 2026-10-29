package com.devlink.server.repository;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
}
