package com.devlink.server.service;

import org.springframework.stereotype.Service;
import com.devlink.server.common.BusinessException;
import com.devlink.server.dto.task.TaskListRequest;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.domain.AgentPrincipal;

@Service
@RequiredArgsConstructor
public class TaskQueryService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final AgentRepository agentRepository;


	// 팀 전체 조회에서는 direction=all만 허용
	private void validateConditions(TaskListRequest request) {
		if("team".equals(request.scope())
				&& !"all".equals(request.direction())) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private void validateTeam(Long teamId, AgentPrincipal agent) {
		//teamId 형식 확인.
		if(teamId == null || teamId <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		//요청한 팀과 호출 에이전트의 팀이 같은지 확인
		if(teamId.longValue() != agent.teamId()) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		//팀 존재 여부 확인
		if(!teamRepository.existsById(teamId)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		
		//사용자가 해당 팀의 팀원인지 확인
		if(!teamMemberRepository.existsByTeamIdAndUserId(teamId, agent.ownerUserId())) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private void validateAgent(AgentPrincipal agent) {
		if(agent == null || agent.agentId()<=0 || agent.ownerUserId()<=0 || agent.teamId() <= 0) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
	}

}
