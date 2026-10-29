package com.devlink.server.service;

import java.util.Optional;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskAuthorizationServiceTest {
    private final TeamAuthorizationService teams = mock(TeamAuthorizationService.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final TaskAuthorizationService service = new TaskAuthorizationService(teams, agents);

    @Test
    void checksRequesterAndAllowsDifferentReceiverOwnerInSameTeam() {
        when(agents.findById(9L)).thenReturn(Optional.of(agent(31L, 21L)));
        when(agents.findById(3L)).thenReturn(Optional.of(agent(31L, 22L)));

        service.requireRequester(21L, 31L, 9L, 3L);

        var order = inOrder(teams, agents);
        order.verify(teams).requireTeamMember(21L, 31L);
        order.verify(agents).findById(9L);
        order.verify(agents).findById(3L);
    }

    @Test
    void selfDirectedTaskReadsItsAgentOnlyOnce() {
        when(agents.findById(3L)).thenReturn(Optional.of(agent(31L, 21L)));

        service.requireRequester(21L, 31L, 3L, 3L);

        verify(agents, times(1)).findById(3L);
    }

    @Test
    void nonMemberCannotReachAgentLookup() {
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(teams).requireTeamMember(21L, 31L);

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(agents);
    }

    @Test
    void missingTeamCannotReachAgentLookup() {
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)).when(teams).requireTeamMember(21L, 31L);

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(agents);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidRequesterIdIsRejectedBeforeAgentLookup(Long requesterAgentId) {
        assertError(() -> service.requireRequester(21L, 31L, requesterAgentId, 9L), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(agents);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidReceiverIdIsRejectedBeforeAgentLookup(Long receiverAgentId) {
        assertError(() -> service.requireRequester(21L, 31L, 3L, receiverAgentId), ErrorCode.VALIDATION_FAILED);

        verifyNoInteractions(agents);
    }

    @Test
    void missingRequesterIsNotFound() {
        when(agents.findById(3L)).thenReturn(Optional.empty());

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void missingReceiverIsNotFound() {
        when(agents.findById(3L)).thenReturn(Optional.of(agent(31L, 21L)));
        when(agents.findById(9L)).thenReturn(Optional.empty());

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requesterOrReceiverFromAnotherTeamIsNotFound(boolean requesterInOtherTeam) {
        when(agents.findById(3L))
                .thenReturn(Optional.of(agent(requesterInOtherTeam ? 41L : 31L, 21L)));
        when(agents.findById(9L))
                .thenReturn(Optional.of(agent(requesterInOtherTeam ? 31L : 41L, 22L)));

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void anotherUsersRequesterAgentIsForbiddenEvenWithinSameTeam() {
        when(agents.findById(3L)).thenReturn(Optional.of(agent(31L, 22L)));
        when(agents.findById(9L)).thenReturn(Optional.of(agent(31L, 21L)));

        assertError(() -> service.requireRequester(21L, 31L, 3L, 9L), ErrorCode.ACCESS_DENIED);
    }

    private Agent agent(Long teamId, Long ownerUserId) {
        Agent agent = mock(Agent.class);
        when(agent.getTeamId()).thenReturn(teamId);
        when(agent.getOwnerUserId()).thenReturn(ownerUserId);
        return agent;
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
