package com.devlink.server.config;

import java.util.List;
import java.util.Optional;
import com.devlink.server.filter.AgentTokenAuthenticationFilter;
import com.devlink.server.handler.RestAccessDeniedHandler;
import com.devlink.server.handler.RestAuthenticationEntryPoint;
import com.devlink.server.handler.RestLogoutSuccessHandler;
import com.devlink.server.service.AgentTokenVerifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
            @Value("${devlink.cors.allowed-origins}") List<String> allowedOrigins,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler,
            RestLogoutSuccessHandler logoutSuccessHandler,
            SecurityContextRepository userSecurityContextRepository,
            HttpSessionCsrfTokenRepository userCsrfTokenRepository) throws Exception {
        // 사용자 세션 인증·CSRF 보호·로그아웃 처리 연결
        http.cors(cors -> cors.configurationSource(apiCorsConfigurationSource(allowedOrigins)))
                .securityContext(context -> context
                        .securityContextRepository(userSecurityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(userCsrfTokenRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/signup", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf",
                                "/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/users/me").hasRole("USER")
                        .requestMatchers("/api/v1/teams", "/api/v1/teams/**", "/api/v1/agents/**")
                                .hasRole("USER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutSuccessHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    SecurityContextRepository userSecurityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    HttpSessionCsrfTokenRepository userCsrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy userSessionAuthenticationStrategy(
            HttpSessionCsrfTokenRepository userCsrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(userCsrfTokenRepository)));
    }

    private CorsConfigurationSource apiCorsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Authorization", "Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("X-CSRF-TOKEN", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
