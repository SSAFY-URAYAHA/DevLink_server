# 브로커 연결 인증 계약

## 확정한 방향

- 사용자 로그인은 세션 방식으로 구현한다. 로그인 API의 실제 구현은 인증 담당 범위다.
- `/ws/broker`는 사용자 세션 대신 Agent 토큰으로 인증한다.
- 사용자당 Agent 하나, 사용자당 활성 브로커 하나를 전제로 한다. DB 중복 방지는 후속 구현이며, 활성 연결 교체는 ConnectionRegistry에서 처리한다.
- 연결은 raw WebSocket + JSON을 사용하며, 배포에서는 WSS를 사용한다.

## 연결 요청

브로커는 WebSocket Upgrade 요청에 `Authorization: Bearer <Agent 토큰 원문>`을 보낸다.
토큰은 URL 쿼리에 넣지 않는다. 사용자 세션 쿠키만으로는 연결할 수 없다.
기존 Origin 허용 목록은 유지한다. 네이티브 브로커의 Origin 헤더는 선택한 클라이언트와 함께 확인한다.

`SecurityConfig`의 브로커 전용 필터 체인은 세션을 생성하거나 사용자 로그인 세션을 읽지 않는다.
인증 실패는 Upgrade 전에 401 `ApiResponse` JSON으로 반환한다.
Agent 인증은 `/ws/broker`에만 적용되며 사용자 REST API 접근 권한을 부여하지 않는다.

## 인증 담당자에게 필요한 구현

`service/AgentTokenVerifier`를 구현한 Spring Bean을 제공한다.

```java
Optional<AgentPrincipal> verify(String rawToken);
```

- 성공: 서버 데이터에서 확인한 `agentId`, `ownerUserId`, `teamId`를 반환한다.
- 실패: 알 수 없는 토큰, 폐기·만료된 자격, 허용되지 않은 Agent는 `Optional.empty()`를 반환한다.
- 토큰 해시 알고리즘, 조회 방식, 팀 멤버십·Agent 유효성 검사와 발급·폐기는 인증 담당자가 구현한다.
- `connection_token_hash`의 길이만으로 해시 알고리즘을 결정하지 않는다.
- 원문 토큰이나 해시를 Principal, 일반 API 응답, 로그에 포함하지 않는다.

현재는 인증 구현 Bean이 없으면 모든 연결을 거부한다. 설정값이나 임시 토큰으로 우회하는 기능은 없다.
성공한 요청의 Principal은 Spring Security `Authentication`이며, 그 안의 Principal은 `AgentPrincipal`이다.
후속 메시지 처리에서는 이 신원을 기준으로 권한을 검사하고 클라이언트가 보낸 Agent ID를 그대로 신뢰하지 않는다.

## BROKER_READY 응답

인증된 WebSocket 연결을 Registry에 등록한 뒤 다음 JSON을 전송한다. `agent_id`는 토큰 검증으로 확인한 숫자 ID이며 클라이언트 쿼리 값을 사용하지 않는다. `connection_id`는 현재 WebSocket 세션 ID로 재접속 시 변경된다. 토큰·해시·소유자·팀 정보는 응답에 포함하지 않는다.

```json
{
  "type": "BROKER_READY",
  "payload": {
    "connection_id": "current-websocket-session-id",
    "agent_id": 11
  }
}
```

READY는 브로커 연결 준비 완료를 의미한다. 이를 보냈다는 이유로 DB의 Agent 상태를 ONLINE으로 변경하지 않는다. 현재 Registry의 조회 키는 `AgentPrincipal.getName()`으로 얻은 Agent ID 문자열이다.

## 연결 수명과 후속 작업

- 토큰 재발급·폐기·Agent 삭제 시 열린 연결을 종료하는 연동 방식은 인증 담당자와 추가 합의한다.
- 사용자 로그아웃이 Agent 토큰도 폐기하는지는 별도 정책이다. 세션 무효화만으로 토큰 연결이 자동 종료되지는 않는다.
- 연결 성공 시 인증된 `AgentPrincipal`의 Agent ID로 `ConnectionRegistry`에 등록하고 `BROKER_READY`를 전송한다.
- 동일 Agent의 새 연결은 기존 연결을 4002 코드로 종료한다. 이전 연결의 종료 콜백은 새 연결을 제거하지 않는다.
- 연결 종료 시 현재 연결만 Registry에서 해제한다. READY 전송 실패도 등록을 정리하고 연결을 종료한다.
- Agent presence, 하트비트, 작업 라우팅, ACK와 메시지별 권한 검사는 이후 구현한다.
- Agent 등록·토큰 발급이 브로커 연결보다 먼저 가능하도록 CLI·인증 담당자와 초기 설정 흐름을 맞춘다.
- `owner_user_id`의 고유 제약 등 사용자당 Agent 하나를 보장하는 DB 변경은 스키마 담당자가 반영한다.

## 검증 범위

통합 테스트에서 테스트 전용 검증 Bean으로 실제 WebSocket Upgrade와 인증 Principal 전달을 확인한다.
누락·잘못된 토큰, 세션만 있는 요청, 쿼리 토큰, 중복 Authorization 헤더, 허용되지 않은 Origin과
Agent 토큰의 사용자 API 접근을 거부하는지 검사한다. 별도로 검증 구현이 없는 경우의 연결 거부도 확인한다.
연결 수명 통합 테스트는 실제 WebSocket으로 READY 수신, 정상 종료 시 해제, 동일 Agent 재접속의 4002 종료와 새 연결 유지, 클라이언트 강제 종료 시 해제를 확인한다. 이 테스트는 실제 DB 토큰 발급·해시 검증·만료 판정이 구현되었다는 의미는 아니다.
