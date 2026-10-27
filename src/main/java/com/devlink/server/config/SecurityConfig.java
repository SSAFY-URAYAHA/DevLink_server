package com.devlink.server.config;

import java.util.Optional;
import com.devlink.server.filter.AgentTokenAuthenticationFilter;
import com.devlink.server.handler.RestAccessDeniedHandler;
import com.devlink.server.handler.RestAuthenticationEntryPoint;
import com.devlink.server.service.AgentTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain brokerSecurityFilterChain(HttpSecurity http,
            ObjectProvider<AgentTokenVerifier> verifiers,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler) throws Exception {
        AgentTokenVerifier verifier = token -> {
            var delegate = verifiers.getIfAvailable();
            // Fail closed until the authentication module provides real token verification.
            return delegate == null ? Optional.empty() : delegate.verify(token);
        };
        http.securityMatcher("/ws/broker")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/ws/broker").hasRole("AGENT")
                        .anyRequest().denyAll())
                .addFilterBefore(new AgentTokenAuthenticationFilter(verifier, entryPoint),
                        AnonymousAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler) throws Exception {
        // TODO: 사용자 세션 로그인 구현 시 CSRF·쿠키 속성·세션 고정 보호와 인증 공급자 연결.
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
