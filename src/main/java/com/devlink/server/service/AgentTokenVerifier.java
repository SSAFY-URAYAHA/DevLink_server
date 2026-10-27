package com.devlink.server.service;

import java.util.Optional;
import com.devlink.server.domain.AgentPrincipal;

/** 브로커 연결에 사용할 Agent 토큰을 검증하는 인증 모듈 연동 인터페이스입니다. */
public interface AgentTokenVerifier {
    /**
     * 토큰 원문을 현재 유효한 Agent 자격 정보와 접근 정책에 따라 검증합니다.
     * 존재하지 않거나 폐기·만료된 토큰 또는 접근 권한이 없는 경우 빈 Optional을 반환합니다.
     * 반환할 Agent 식별 정보는 클라이언트가 보낸 ID가 아니라 서버에서 확인한 데이터로 구성해야 합니다.
     */
    Optional<AgentPrincipal> verify(String rawToken);
}
