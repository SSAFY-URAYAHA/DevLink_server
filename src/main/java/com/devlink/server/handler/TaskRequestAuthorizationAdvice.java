package com.devlink.server.handler;

import java.lang.reflect.Type;
import java.util.Map;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.controller.TaskController;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.service.TaskAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = TaskController.class)
@RequiredArgsConstructor
public class TaskRequestAuthorizationAdvice extends RequestBodyAdviceAdapter {
    private final TaskAuthorizationService authorization;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getParameterType() == CreateTaskRequest.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (!(body instanceof CreateTaskRequest taskRequest)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        HttpServletRequest request = attributes.getRequest();
        Long userId = requireUserId(request);
        Long teamId = requireTeamId(request);
        // 기존 작업 Controller를 수정하지 않고 웹 요청 경계에서 권한 확인
        // 권한 조회와 기존 작업 저장은 별도 트랜잭션으로 처리
        authorization.requireRequester(userId, teamId, taskRequest.requesterAgentId(), taskRequest.receiverAgentId());
        return body;
    }

    private Long requireUserId(HttpServletRequest request) {
        if (!(request.getUserPrincipal() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal user)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("userId") instanceof Long userId)
                || userId <= 0 || userId.longValue() != user.userId()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return userId;
    }

    private Long requireTeamId(HttpServletRequest request) {
        Object pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(pathVariables instanceof Map<?, ?> variables) || !(variables.get("teamId") instanceof String teamId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            long value = Long.parseLong(teamId);
            if (value <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
