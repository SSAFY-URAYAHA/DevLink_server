package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.task.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import com.devlink.server.domain.task.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {
    Optional<Task> findByRequesterUserIdAndRequestKey(Long requesterUserId, String requestKey);

    List<Task> findByTeamIdOrderByCreatedAtDesc(Long teamId);
    
    @Query("""
            select task
            from Task task
            where task.teamId = :teamId
              and (:status is null or task.status = :status)
            order by task.createdAt desc, task.id desc
            """)
    Page<Task> findTeamTasks(
    		@Param("teamId")Long teamId,
    		@Param("status")TaskStatus status,
    		Pageable pageable);
    
    
    @Query("""
            select task
            from Task task
            where task.teamId = :teamId
              and (:status is null or task.status = :status)
              and (
                  (:direction = 'sent'
                      and task.requesterAgentId in (:agentIds))
                  or
                  (:direction = 'received'
                      and task.receiverAgentId in (:agentIds))
                  or
                  (:direction = 'all'
                      and (
                          task.requesterAgentId in (:agentIds)
                          or task.receiverAgentId in (:agentIds)
                      ))
              )
            order by task.createdAt desc, task.id desc
            """)
    Page<Task> findAgentTasks(
            @Param("teamId") Long teamId,
            @Param("agentIds") List<Long> agentIds,
            @Param("direction") String direction,
            @Param("status") TaskStatus status,
            Pageable pageable
    );
    
}
