package com.devlink.server;

import java.time.LocalDateTime;
import com.devlink.server.domain.user.User;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.repository.*;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.domain.taskfile.ChangeType;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DomainPersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskFileRepository files;
    @Autowired private TaskEventRepository events;
    @Autowired private EntityManager em;

    @Test
    void savesAllEntitiesAndQueriesThemWithLifecycleAndVersionUpdates() {
        User user = users.save(User.create("test@example.com", "Tester", null, "password-hash"));
        Team team = teams.save(Team.create("DevLink", "invite"));
        team.regenerateInviteCode("new-invite");
        TeamMember member = members.save(TeamMember.create(team.getId(), user.getId(), MemberRole.OWNER));
        Agent agent = agents.save(Agent.create(team.getId(), user.getId(), "Codex", null, ToolType.CODEX, "server"));
        LocalDateTime seenAt = LocalDateTime.now();
        agent.issueConnectionToken("a".repeat(64));
        agent.markOnline(seenAt);
        Task task = tasks.save(Task.create("request", team.getId(), agent.getId(), user.getId(), agent.getId(), "Prompt"));
        em.flush();
        Long initialVersion = task.getVersion();
        task.accept(agent.getId());
        em.flush();
        assertThat(task.getVersion()).isGreaterThan(initialVersion);
        task.complete(agent.getId(), "Done");
        files.save(TaskFile.of(task.getId(), "src/Main.java", ChangeType.MODIFIED, "diff"));
        events.save(TaskEvent.of(task.getId(), TaskEventType.STATUS_CHANGED, "Completed"));
        em.flush();
        em.clear();

        assertThat(users.findByEmail("test@example.com")).hasValueSatisfying(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getPassword()).isEqualTo("password-hash");
        });
        assertThat(teams.findByInviteCode("new-invite")).isPresent();
        assertThat(member.getJoinedAt()).isNotNull();
        assertThat(members.existsByTeamIdAndUserId(team.getId(), user.getId())).isTrue();
        assertThat(agents.findByTeamId(team.getId())).singleElement().satisfies(saved -> {
            assertThat(saved.getStatus()).isEqualTo(AgentStatus.ONLINE);
            assertThat(saved.getConnectionTokenHash()).isEqualTo("a".repeat(64));
            assertThat(saved.getLastSeenAt()).isNotNull();
            saved.markOffline();
            assertThat(saved.getStatus()).isEqualTo(AgentStatus.OFFLINE);
        });
        assertThat(tasks.findByRequesterUserIdAndRequestKey(user.getId(), "request")).hasValueSatisfying(saved -> {
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(saved.getResultText()).isEqualTo("Done");
        });
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(team.getId())).hasSize(1);
        assertThat(files.findByTaskId(task.getId())).singleElement().satisfies(saved ->
                assertThat(saved.getChangeType()).isEqualTo(ChangeType.MODIFIED));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(task.getId())).singleElement().satisfies(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getEventType()).isEqualTo(TaskEventType.STATUS_CHANGED);
        });
    }
}
