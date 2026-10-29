package com.devlink.server;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.CreateTeamRequest;
import com.devlink.server.dto.CreateTeamResponse;
import com.devlink.server.dto.JoinTeamRequest;
import com.devlink.server.dto.TeamJoinResponse;
import com.devlink.server.dto.TeamMemberResponse;
import com.devlink.server.dto.TeamResponse;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.InviteCodeGenerator;
import com.devlink.server.service.TeamCreationService;
import com.devlink.server.service.TeamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TeamManagementIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TeamService service;
    @Autowired private TeamCreationService creation;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private TeamRepository teams;
    @MockitoSpyBean private TeamMemberRepository members;
    @MockitoSpyBean private InviteCodeGenerator inviteCodes;

    @Test
    void ownTeamListAndMemberOnlyQueriesKeepInviteCodesAndPasswordsPrivate() {
        User owner = createUser("Owner");
        User member = createUser("Member");
        User outsider = createUser("Outsider");
        CreateTeamResponse team = createTeam(owner);
        CreateTeamResponse otherTeam = createTeam(outsider);
        service.join(member.getId(), new JoinTeamRequest(team.inviteCode()));

        assertThat(service.getMyTeams(owner.getId())).extracting(TeamResponse::teamId)
                .containsExactly(team.teamId());
        assertThat(service.getMyTeams(outsider.getId())).extracting(TeamResponse::teamId)
                .containsExactly(otherTeam.teamId());
        TeamResponse detail = service.getTeam(member.getId(), team.teamId());
        assertThat(detail.myRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(json.valueToTree(detail).has("inviteCode")).isFalse();
        List<TeamMemberResponse> teamMembers = service.getMembers(member.getId(), team.teamId());
        assertThat(teamMembers).extracting(TeamMemberResponse::userId)
                .containsExactly(owner.getId(), member.getId());
        assertThat(teamMembers).extracting(TeamMemberResponse::role)
                .containsExactly(MemberRole.OWNER, MemberRole.MEMBER);
        assertThat(teamMembers).allSatisfy(response -> {
            assertThat(response.joinedAt()).isNotNull();
            assertThat(json.valueToTree(response).has("password")).isFalse();
            assertThat(json.valueToTree(response).has("email")).isFalse();
        });
        assertError(() -> service.getTeam(outsider.getId(), team.teamId()), ErrorCode.ACCESS_DENIED);
        assertError(() -> service.getMembers(outsider.getId(), team.teamId()), ErrorCode.ACCESS_DENIED);
    }

    @Test
    void emptyTeamListAndInvalidCallerOrTeamAreHandled() {
        User user = createUser("No teams");
        assertThat(service.getMyTeams(user.getId())).isEmpty();
        assertError(() -> service.getMyTeams(null), ErrorCode.AUTHENTICATION_REQUIRED);
        assertError(() -> service.getMyTeams(-1L), ErrorCode.AUTHENTICATION_REQUIRED);
        assertError(() -> service.getTeam(user.getId(), 0L), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.getTeam(user.getId(), Long.MAX_VALUE), ErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.join(user.getId(), new JoinTeamRequest(" ")), ErrorCode.VALIDATION_FAILED);
        assertError(() -> service.join(user.getId(), new JoinTeamRequest("missing-" + UUID.randomUUID())),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void joiningAgainReturnsSameMembershipWithoutChangingOwnerOrJoinTime() {
        User owner = createUser("Owner");
        User member = createUser("Member");
        CreateTeamResponse team = createTeam(owner);
        TeamJoinResponse ownerReplay = service.join(owner.getId(), new JoinTeamRequest(team.inviteCode()));
        TeamJoinResponse first = service.join(member.getId(), new JoinTeamRequest(" " + team.inviteCode() + " "));
        var storedJoinedAt = members.findByTeamIdAndUserId(team.teamId(), member.getId()).orElseThrow().getJoinedAt();
        TeamJoinResponse replay = service.join(member.getId(), new JoinTeamRequest(team.inviteCode()));

        assertThat(ownerReplay.replayed()).isTrue();
        assertThat(ownerReplay.myRole()).isEqualTo(MemberRole.OWNER);
        assertThat(first.replayed()).isFalse();
        assertThat(first.myRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.joinedAt()).isEqualTo(storedJoinedAt);
        assertThat(members.findByTeamIdOrderByJoinedAtAscIdAsc(team.teamId())).hasSize(2);
    }

    @Test
    void onlyOwnerCanReplaceInviteAndOldCodeCannotJoinAfterCommit() {
        User owner = createUser("Owner");
        User member = createUser("Member");
        User newcomer = createUser("Newcomer");
        CreateTeamResponse team = createTeam(owner);
        service.join(member.getId(), new JoinTeamRequest(team.inviteCode()));
        assertError(() -> service.regenerateInvite(member.getId(), team.teamId()), ErrorCode.ACCESS_DENIED);
        assertError(() -> service.regenerateInvite(newcomer.getId(), team.teamId()), ErrorCode.ACCESS_DENIED);
        assertThat(teams.findById(team.teamId()).orElseThrow().getInviteCode()).isEqualTo(team.inviteCode());

        var replacement = service.regenerateInvite(owner.getId(), team.teamId());

        assertThat(replacement.inviteCode()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(team.inviteCode());
        assertThat(teams.findByInviteCode(team.inviteCode())).isEmpty();
        assertError(() -> service.join(newcomer.getId(), new JoinTeamRequest(team.inviteCode())),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(service.join(newcomer.getId(), new JoinTeamRequest(replacement.inviteCode())).replayed()).isFalse();
    }

    @Test
    void inviteCollisionRetriesInFreshTransactionAndKeepsOtherTeamUnchanged() {
        User owner = createUser("Owner");
        CreateTeamResponse target = createTeam(owner);
        CreateTeamResponse other = createTeam(owner);
        clearInvocations(inviteCodes);
        doReturn(other.inviteCode()).doCallRealMethod().when(inviteCodes).generate();
        try {
            var response = service.regenerateInvite(owner.getId(), target.teamId());

            assertThat(response.inviteCode()).isNotEqualTo(target.inviteCode()).isNotEqualTo(other.inviteCode());
            assertThat(teams.findById(target.teamId()).orElseThrow().getInviteCode()).isEqualTo(response.inviteCode());
            assertThat(teams.findById(other.teamId()).orElseThrow().getInviteCode()).isEqualTo(other.inviteCode());
            verify(inviteCodes, times(2)).generate();
        } finally {
            reset(inviteCodes);
        }
    }

    @Test
    void exhaustedInviteCollisionsAndUnrelatedPersistenceErrorsDoNotReplaceCurrentCode() {
        User owner = createUser("Owner");
        CreateTeamResponse target = createTeam(owner);
        CreateTeamResponse other = createTeam(owner);
        clearInvocations(inviteCodes);
        doReturn(other.inviteCode()).when(inviteCodes).generate();
        try {
            assertError(() -> service.regenerateInvite(owner.getId(), target.teamId()), ErrorCode.INTERNAL_SERVER_ERROR);
            assertThat(teams.findById(target.teamId()).orElseThrow().getInviteCode()).isEqualTo(target.inviteCode());
            verify(inviteCodes, times(5)).generate();
        } finally {
            reset(inviteCodes);
        }

        clearInvocations(inviteCodes);
        doThrow(new DataIntegrityViolationException("unrelated persistence error"))
                .when(teams).saveAndFlush(any(Team.class));
        try {
            assertThatThrownBy(() -> service.regenerateInvite(owner.getId(), target.teamId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verify(inviteCodes, times(1)).generate();
            assertThat(teams.findById(target.teamId()).orElseThrow().getInviteCode()).isEqualTo(target.inviteCode());
        } finally {
            reset(teams);
        }
    }

    @Test
    void membershipUniqueConflictRecoversAfterRollbackInsteadOfUsingFailedTransaction() {
        User owner = createUser("Owner");
        User member = createUser("Member");
        CreateTeamResponse team = createTeam(owner);
        TeamMember existing = members.saveAndFlush(TeamMember.create(team.teamId(), member.getId(), MemberRole.MEMBER));
        doReturn(Optional.empty()).doReturn(Optional.of(existing))
                .when(members).findByTeamIdAndUserId(team.teamId(), member.getId());
        try {
            TeamJoinResponse response = service.join(member.getId(), new JoinTeamRequest(team.inviteCode()));

            assertThat(response.replayed()).isTrue();
            assertThat(response.myRole()).isEqualTo(MemberRole.MEMBER);
            assertThat(response.joinedAt()).isEqualTo(existing.getJoinedAt());
            assertThat(members.findByTeamIdOrderByJoinedAtAscIdAsc(team.teamId())).hasSize(2);
        } finally {
            reset(members);
        }
    }

    @Test
    void concurrentJoinRequestsCreateOnlyOneMembership() throws Exception {
        User owner = createUser("Owner");
        User member = createUser("Member");
        CreateTeamResponse team = createTeam(owner);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> joinAfter(start, member.getId(), team.inviteCode()), executor);
            var second = CompletableFuture.supplyAsync(() -> joinAfter(start, member.getId(), team.inviteCode()), executor);
            start.countDown();
            TeamJoinResponse firstResult = first.get(10, TimeUnit.SECONDS);
            TeamJoinResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.replayed(), secondResult.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(members.findByTeamIdOrderByJoinedAtAscIdAsc(team.teamId())).hasSize(2);
        }
    }

    private TeamJoinResponse joinAfter(CountDownLatch start, Long userId, String inviteCode) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent join did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return service.join(userId, new JoinTeamRequest(inviteCode));
    }

    private User createUser(String name) {
        return users.saveAndFlush(User.create("team-management-" + UUID.randomUUID() + "@example.com",
                name, name.toLowerCase(Locale.ROOT), "test-password-hash"));
    }

    private CreateTeamResponse createTeam(User owner) {
        return creation.create(owner.getId(), new CreateTeamRequest("Team " + UUID.randomUUID()));
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
