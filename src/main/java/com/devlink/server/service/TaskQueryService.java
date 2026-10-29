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
import com.devlink.server.domain.task.Task;
import com.devlink.server.repository.TaskRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.task.TaskAgentResponse;
import com.devlink.server.dto.task.TaskListItemResponse;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import com.devlink.server.dto.task.TaskListResponse;
import java.util.ArrayList;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskQueryService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final AgentRepository agentRepository;
	private final TaskRepository taskRepository;
	
	@Transactional(readOnly = true)
	public TaskListResponse getTasks(
	        Long teamId,
	        AgentPrincipal agent,
	        TaskListRequest request
	) {
	    validateAgent(agent);
	    validateConditions(request);
	    validateTeam(teamId, agent);

	    Page<Task> taskPage = findTasks(
	            teamId,
	            agent,
	            request
	    );

	    return toListResponse(taskPage);
	}
	
	private TaskListResponse toListResponse(Page<Task> taskPage) {
	    List<Task> tasks = taskPage.getContent();

	    Map<Long, Agent> agentsById = findAgents(tasks);

	    List<TaskListItemResponse> items = new ArrayList<>();

	    for (Task task : tasks) {
	        Agent requesterAgent = agentsById.get(
	                task.getRequesterAgentId()
	        );

	        Agent receiverAgent = agentsById.get(
	                task.getReceiverAgentId()
	        );

	        if (requesterAgent == null || receiverAgent == null) {
	            throw new BusinessException(
	                    ErrorCode.INTERNAL_SERVER_ERROR
	            );
	        }

	        items.add(toListItem(
	                task,
	                requesterAgent,
	                receiverAgent
	        ));
	    }

	    return new TaskListResponse(
	            items,
	            taskPage.getNumber(),
	            taskPage.getSize(),
	            taskPage.getTotalElements(),
	            taskPage.getTotalPages(),
	            taskPage.hasNext()
	    );
	}

	private Map<Long,Agent> findAgents(List<Task> tasks){
		if(tasks.isEmpty()) {
			return Map.of();
		}
		Set<Long> agentIds = new HashSet<>();
		
		for(Task task : tasks) {
			agentIds.add(task.getRequesterAgentId());
			agentIds.add(task.getReceiverAgentId());
		}
		Map<Long,Agent> agentsById = new HashMap<>();
		
		for(Agent agent:agentRepository.findAllById(agentIds)) {
			agentsById.put(agent.getId(), agent);
		}
		return agentsById;
	}
	
	private TaskListItemResponse toListItem(Task task,Agent requesterAgent,Agent receiverAgent) {
		return new TaskListItemResponse(task.getId(),task.getTeamId(),
				new TaskAgentResponse(
						requesterAgent.getId(),requesterAgent.getName()),
				new TaskAgentResponse(
						receiverAgent.getId(),receiverAgent.getName()),
				task.getStatus(),
				task.getCreatedAt(),
				task.getStartedAt(),
				task.getCompletedAt());
	}
	
	private Page<Task> findTasks(Long teamId,AgentPrincipal agent,TaskListRequest request){
		Pageable pageable = PageRequest.of(request.page(), request.size());
		if("team".equals(request.scope())) {
			return taskRepository.findTeamTasks(teamId, request.status(), pageable);
		}
		return taskRepository.findAgentTasks(
				teamId,
				List.of(agent.agentId()),
				request.direction(),
				request.status(),
				pageable
				);
	}

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
