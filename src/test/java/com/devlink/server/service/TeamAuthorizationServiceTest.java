package com.devlink.server.service;

import java.util.Optional;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TeamAuthorizationServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final TeamAuthorizationService service = new TeamAuthorizationService(teams, members);

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    void teamMemberCheckAcceptsOwnerAndMember(MemberRole role) {
        TeamMember member = TeamMember.create(31L, 21L, role);
        when(teams.existsById(31L)).thenReturn(true);
        when(members.findByTeamIdAndUserId(31L, 21L)).thenReturn(Optional.of(member));

        assertThat(service.requireTeamMember(21L, 31L)).isSameAs(member);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidUserRequiresAuthenticationBeforeCheckingTeam(Long userId) {
        assertThatThrownBy(() -> service.requireTeamMember(userId, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
        verifyNoInteractions(teams, members);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidTeamIdReturnsValidationErrorWithoutDatabaseLookup(Long teamId) {
        assertThatThrownBy(() -> service.requireTeamMember(21L, teamId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(teams, members);
    }

    @Test
    void missingTeamIsNotFoundWithoutMembershipLookup() {
        when(teams.existsById(31L)).thenReturn(false);

        assertThatThrownBy(() -> service.requireTeamMember(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(members);
    }

    @Test
    void existingTeamRejectsNonMember() {
        when(teams.existsById(31L)).thenReturn(true);
        when(members.findByTeamIdAndUserId(31L, 21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireTeamMember(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void teamOwnerCheckReturnsOwnerMembership() {
        TeamMember owner = TeamMember.create(31L, 21L, MemberRole.OWNER);
        when(teams.existsById(31L)).thenReturn(true);
        when(members.findByTeamIdAndUserId(31L, 21L)).thenReturn(Optional.of(owner));

        assertThat(service.requireTeamOwner(21L, 31L)).isSameAs(owner);
    }

    @Test
    void ordinaryTeamMemberCannotPassOwnerCheck() {
        TeamMember member = TeamMember.create(31L, 21L, MemberRole.MEMBER);
        when(teams.existsById(31L)).thenReturn(true);
        when(members.findByTeamIdAndUserId(31L, 21L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.requireTeamOwner(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    void nonMemberCannotPassOwnerCheck() {
        when(teams.existsById(31L)).thenReturn(true);
        when(members.findByTeamIdAndUserId(31L, 21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireTeamOwner(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }
}
