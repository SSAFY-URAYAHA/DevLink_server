# 보안 전략

## 1. 현재 범위

사용자 로그인은 세션 방식, `/ws/broker`는 Agent 토큰 인증으로 분리합니다. 비밀번호 인코더는 BCrypt이며 사용자 로그인·Agent 토큰 발급과 DB 검증 로직은 아직 없습니다. Agent 토큰은 DB에 원문 대신 해시만 저장하는 계약입니다.

## 2. HTTP 접근 정책

signup, login, API health와 Actuator health만 공개합니다. 나머지 요청은 인증이 필요합니다. formLogin과 httpBasic은 비활성화하며 인증 실패는 401, 접근 거부는 403 ApiResponse JSON으로 반환합니다. Spring Security의 기본 세션·CSRF 동작을 유지합니다.

## 3. WebSocket

`/ws/broker`에 raw WebSocket 핸들러를 등록합니다. 전용 필터 체인이 `Authorization: Bearer <Agent 토큰>`을 확인하고 `AgentTokenVerifier`의 검증 결과를 인증 Principal로 전달합니다. 사용자 세션으로 대체할 수 없으며 Agent 인증 결과도 사용자 REST API 권한으로 사용하지 않습니다. 검증 구현 Bean이 없거나 인증에 실패하면 401로 연결을 거부합니다.

Origin은 설정으로 제한하며 Origin 검증은 인증을 대체하지 않습니다. 인증된 연결은 ConnectionRegistry에 등록한 뒤 BROKER_READY를 전송하고, 종료 시 현재 연결만 해제합니다. AGENT_PRESENCE는 현재 인증된 연결의 Agent에만 반영하며 ONLINE/OFFLINE과 last_seen_at을 DB에 저장합니다. 잘못된 메시지는 1008로 종료하고, 현재 연결 종료 시 오프라인으로 갱신합니다. 저장된 작업의 라우팅은 수신 Agent의 ONLINE 상태와 현재 연결, 작업의 팀 및 인증 신원의 Agent·소유자·팀 일치를 검증합니다. 작업 저장 트랜잭션 안에서의 직접 전송은 거부하며, 커밋 후 이벤트로 연결할 수 있습니다. ACK와 상태·결과 알림은 후속 구현입니다. 상세 계약과 남은 연동은 [브로커 인증 계약](../../bogyeong/docs/broker-authentication.md)과 [작업 라우팅 구현](../../bogyeong/docs/task-routing.md)을 참고합니다.

### BROKER_READY 신원 정보

연결 등록 후 보내는 `BROKER_READY.payload`에는 필수 문자열 `connection_id`와 필수 정수 `agent_id`, `user_id`, `team_id`를 포함합니다. 세 ID는 토큰 검증 결과인 `AgentPrincipal`에서 가져오며, `user_id`는 Agent 소유자의 `ownerUserId`입니다. 클라이언트가 요청에 넣은 ID를 사용하지 않고 토큰 원문·해시는 전송하지 않습니다. 재접속 시에도 같은 필드를 보내므로 브로커 수신 DTO에서 사용자·팀 신원을 다시 구성할 수 있습니다.

## 4. 인증 방식 확정 후 결정할 사항

사용자 세션 로그인의 CSRF 전달 방식, 쿠키 속성, 세션 고정 보호와 저장소를 결정하고 인증 공급자를 연결합니다. 인증 담당자는 Agent 토큰 검증 구현을 제공합니다. Agent 토큰의 해시·발급·만료·폐기 정책과 연결 종료 연동, 사용자 로그아웃이 Agent 자격에 미치는 영향을 함께 정합니다. 필요한 환경 변수와 테이블은 실제 정책·ERD에 맞춰 추가합니다.
