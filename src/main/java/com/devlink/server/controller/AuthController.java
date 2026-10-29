package com.devlink.server.controller;

import java.util.List;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.CsrfTokenResponse;
import com.devlink.server.dto.LoginRequest;
import com.devlink.server.dto.SignupRequest;
import com.devlink.server.dto.UserResponse;
import com.devlink.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final SecurityContextRepository userSecurityContextRepository;
    private final SessionAuthenticationStrategy userSessionAuthenticationStrategy;

    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> csrf(CsrfToken csrfToken, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.success(CsrfTokenResponse.from(csrfToken));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.signup(request)));
    }

    @PostMapping("/login")
    public ApiResponse<UserResponse> login(@Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request, HttpServletResponse response) {
        UserResponse user = authService.login(loginRequest);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new UserPrincipal(user.userId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        userSessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        userSecurityContextRepository.saveContext(context, request, response);
        // 기존 작업 생성 API와 동일한 세션에 로그인 사용자 ID 저장
        request.getSession().setAttribute("userId", user.userId());
        return ApiResponse.success(user);
    }

    @GetMapping("/users/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ApiResponse.success(authService.getCurrentUser(principal.userId()));
    }
}
