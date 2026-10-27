package com.devlink.server.filter;

import java.io.IOException;
import java.util.List;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.service.AgentTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only in the broker security chain, never as a global servlet filter. */
public class AgentTokenAuthenticationFilter extends OncePerRequestFilter {
    private final AgentTokenVerifier verifier;
    private final AuthenticationEntryPoint entryPoint;

    public AgentTokenAuthenticationFilter(AgentTokenVerifier verifier, AuthenticationEntryPoint entryPoint) {
        this.verifier = verifier;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        AgentPrincipal agent;
        try {
            String token = readBearerToken(request);
            agent = verifier.verify(token)
                    .orElseThrow(() -> new BadCredentialsException("Invalid Agent credential"));
        } catch (AuthenticationException exception) {
            entryPoint.commence(request, response, exception);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                agent, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String readBearerToken(HttpServletRequest request) {
        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers == null || !headers.hasMoreElements()) {
            throw new BadCredentialsException("Agent credential required");
        }
        String header = headers.nextElement();
        if (headers.hasMoreElements() || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BadCredentialsException("Invalid authorization header");
        }
        String token = header.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new BadCredentialsException("Invalid authorization header");
        }
        return token;
    }
}
