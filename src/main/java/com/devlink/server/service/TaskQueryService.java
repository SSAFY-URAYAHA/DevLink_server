package com.devlink.server.service;

import org.springframework.stereotype.Service;
import com.devlink.server.common.BusinessException;
import com.devlink.server.dto.task.TaskListRequest;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.repository.AgentRepository;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskQueryService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final AgentRepository agentRepository;


	//올흔 사용자인지 판단 후 오류 반환
	private void validateUserId(Long userId) {
		if(userId == null || userId <= 0) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
	}
	//개인이 아닌 팀 전체를 조회할 때, 방향이나 특정 에이전트 지정 시 오류 반환
	private void validateConditions(TaskListRequest request) {
		if("team".equals(request.scope())) {
			if(!"all".equals(request.direction())|| request.agentId() != null) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		}
	}

	private void validateTeam(Long teamId, Long userId) {
		//teamId 값 올바른지 확인.
		if(teamId == null || teamId <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		//팀이 실제 존재하고 있는지 확인
		if(!teamRepository.existsById(teamId)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		//사용자가 해당 팀의 팀원인지 확인
		if(!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private List<Long> findAgentIds(Long teamId, Long userId, Long agentId){
		//특정 Agent를 지정한 경우
		if(agentId != null) {
			Agent agent = agentRepository.findById(agentId)
					.orElseThrow(() ->
					new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
							);
			//조회 대상 팀에 속하고, 로그인 사용자 소유인지 확인
			if(!teamId.equals(agent.getTeamId())||!userId.equals(agent.getOwnerUserId())) {
				throw new BusinessException(ErrorCode.ACCESS_DENIED);
			}
			return List.of(agent.getId());
		}
		//특정 에이전트 지정하지 않으면 사용자 소유 에이전트 전체 조회
		return agentRepository.findUserAgentInTeam(teamId, userId)
				.stream()
				.map(Agent::getId)
				.toList();
	}

}
