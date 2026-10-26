package com.devlink.server.config;

import com.devlink.server.handler.RestAccessDeniedHandler;
import com.devlink.server.handler.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler) throws Exception {
        // TODO: 인증 방식 확정 후 설정 (세션: CSRF·쿠키 속성·세션 고정 보호 / JWT: STATELESS·CSRF 비활성·addFilterBefore)
        // TODO: 인증 방식 확정 후 인증 공급자 및 filter/의 인증 필터 연결.
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login",
                                "/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
