# 보안 전략

## 1. 현재 범위

사용자 로그인은 세션 방식, `/ws/broker`는 Agent 토큰 인증으로 분리합니다. 비밀번호 인코더는 BCrypt이며 사용자 로그인·Agent 토큰 발급과 DB 검증 로직은 아직 없습니다. Agent 토큰은 DB에 원문 대신 해시만 저장하는 계약입니다.

## 2. HTTP 접근 정책

signup, login, API health와 Actuator health만 공개합니다. 나머지 요청은 인증이 필요합니다. formLogin과 httpBasic은 비활성화하며 인증 실패는 401, 접근 거부는 403 ApiResponse JSON으로 반환합니다. Spring Security의 기본 세션·CSRF 동작을 유지합니다.

## 3. WebSocket

`/ws/broker`에 raw WebSocket 핸들러를 등록합니다. 전용 필터 체인이 `Authorization: Bearer <Agent 토큰>`을 확인하고 `AgentTokenVerifier`의 검증 결과를 인증 Principal로 전달합니다. 사용자 세션으로 대체할 수 없으며 Agent 인증 결과도 사용자 REST API 권한으로 사용하지 않습니다. 검증 구현 Bean이 없거나 인증에 실패하면 401로 연결을 거부합니다.

Origin은 설정으로 제한하며 Origin 검증은 인증을 대체하지 않습니다. 핸들러는 아직 빈 골격이며 메시지 라우팅을 제공하지 않습니다. 상세 계약과 남은 연동은 [브로커 인증 계약](broker-authentication.md)을 참고합니다.

## 4. 인증 방식 확정 후 결정할 사항

사용자 세션 로그인의 CSRF 전달 방식, 쿠키 속성, 세션 고정 보호와 저장소를 결정하고 인증 공급자를 연결합니다. 인증 담당자는 Agent 토큰 검증 구현을 제공합니다. Agent 토큰의 해시·발급·만료·폐기 정책과 연결 종료 연동, 사용자 로그아웃이 Agent 자격에 미치는 영향을 함께 정합니다. 필요한 환경 변수와 테이블은 실제 정책·ERD에 맞춰 추가합니다.
