package com.devlink.server;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.domain.team.Team;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OutboxPersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired OutboxEventRepository outbox;
    @Autowired TeamRepository teams;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void roundTripsCompleteMessageAndPublicationDefaults() {
        UUID outboxId = UUID.randomUUID();
        var message = mapper.createObjectNode();
        message.put("event_id", outboxId.toString());
        message.put("type", "TASK_STATUS_CHANGED");
        message.put("task_id", "task_001");
        message.put("version", 2);
        message.putObject("payload").put("previous_status", "PENDING").put("status", "IN_PROGRESS");
        outbox.saveAndFlush(OutboxEvent.create(outboxId, "TASK_STATUS_CHANGED", "TASK", "task_001",
                "task-events", "TASK:task_001", message));

        var saved = outbox.findById(outboxId).orElseThrow();
        assertThat(saved.getOutboxId()).isEqualTo(outboxId);
        assertThat(saved.getMessage()).isEqualTo(message);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttemptCount()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull().isEqualTo(saved.getAvailableAt());
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getLockToken()).isNull();
    }

    @Test
    void businessChangeAndOutboxRollBackTogether() {
        UUID outboxId = UUID.randomUUID();
        var message = mapper.createObjectNode().put("event_id", outboxId.toString()).put("type", "TEAM_CREATED");
        Long[] teamId = new Long[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            teamId[0] = teams.saveAndFlush(Team.create("Outbox rollback", outboxId.toString())).getId();
            outbox.saveAndFlush(OutboxEvent.create(outboxId, "TEAM_CREATED", "TEAM", teamId[0].toString(),
                    "team-events", null, message));
            throw new IllegalStateException("Business transaction failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Business transaction failed");

        assertThat(teams.existsById(teamId[0])).isFalse();
        assertThat(outbox.existsById(outboxId)).isFalse();
    }
}
