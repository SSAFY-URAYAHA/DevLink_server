# 인증·팀·Agent 관리

3번 담당 구현과 API 사용 방법. 기존 팀원 코드의 변경을 줄이기 위해 별도 문서로 관리합니다.

## 6. 사용자 인증 API

모든 응답은 `ApiResponse(success, data, code, message)` 형식입니다. 회원가입은 자동 로그인하지 않습니다.

| 메서드 | 경로 | 요청 / 성공 응답 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 200, `headerName`, `parameterName`, `token` |
| POST | `/api/v1/auth/signup` | `email`, `password`, `name`, 선택 `nickname` / 201, 사용자 정보 |
| POST | `/api/v1/auth/login` | `email`, `password` / 200, 사용자 정보와 세션 쿠키 |
| GET | `/api/v1/auth/users/me` | 로그인 세션 / 200, 사용자 정보 |
| POST | `/api/v1/auth/logout` | 로그인 세션과 CSRF 토큰 / 200, `data=null` |

사용자 정보에는 `userId`, `email`, `name`, `nickname`, `createdAt`을 반환하며 비밀번호·해시는 포함하지 않습니다. 이메일은 앞뒤 공백을 제거하고 소문자로 저장·조회합니다. 가입 비밀번호는 최소 8자이며 가입·로그인 모두 UTF-8 기준 72바이트 이하로 제한합니다. 이메일 중복은 `409 EMAIL_ALREADY_EXISTS`, 계정 또는 비밀번호 불일치는 `401 INVALID_CREDENTIALS`, 입력 오류는 `400 VALIDATION_FAILED`입니다.

프론트엔드는 모든 인증 요청에 `credentials: "include"`를 사용합니다.

1. `GET /api/v1/auth/csrf`로 토큰을 받고 `data.headerName`을 이름으로, `data.token`을 값으로 POST 요청 헤더에 전달합니다.
2. 회원가입 후 같은 세션과 CSRF 토큰으로 로그인합니다. 로그인 시 세션 ID와 CSRF 토큰이 교체됩니다.
3. 로그인 후 `GET /api/v1/auth/csrf`를 다시 호출해 이후 POST 요청에 사용할 토큰을 갱신합니다.
4. 새로고침 후에는 `GET /api/v1/auth/users/me`로 사용자 정보를 복원합니다. 미로그인·세션 만료 시 401입니다.
5. 갱신한 CSRF 토큰으로 로그아웃합니다. 다음 로그인 전에도 CSRF 토큰을 다시 조회합니다.

세션은 서버 메모리에 저장하며 30분 동안 요청이 없으면 만료됩니다. 세션 쿠키는 HttpOnly이며 로컬에서는 SameSite=Lax, 운영에서는 HTTPS용 Secure와 SameSite=None을 사용합니다. 운영 브라우저가 서드파티 쿠키를 차단하면 별도 사이트 간 로그인 유지가 제한될 수 있습니다. 브라우저 로그아웃은 사용자 세션만 종료하며 Agent 토큰·브로커 연결을 폐기하지 않습니다.

로그인 시 내부 `UserPrincipal(userId)`를 저장하고 기존 작업 API와의 호환을 위해 같은 세션에 `userId`도 기록합니다. 기존 `POST /api/v1/tasks/{teamId}`의 권한 검사는 신규 `TaskRequestAuthorizationAdvice`에서 요청 본문을 읽은 뒤 수행합니다. 팀 가입·요청 Agent 소유권·송수신 Agent의 팀 일치를 확인하며, 기존 작업 생성 Controller와 Service는 변경하지 않습니다. 권한 조회와 작업 저장은 별도 트랜잭션입니다. OAuth 외부 계정 연결과 다중 서버용 세션 저장소는 후속 범위입니다.

## 6-1. 팀 관리 및 공통 팀 권한

`POST /api/v1/teams`는 로그인한 사용자의 세션과 CSRF 토큰이 필요합니다. 요청에는 `name`만 받으며 필수·공백 불가·최대 120자입니다.

```json
{
  "name": "DevLink 팀"
}
```

성공 시 201과 공통 응답의 `data`에 `teamId`, `name`, `inviteCode`, `myRole`, `createdAt`을 반환합니다. 생성자와 역할은 요청 본문이 아닌 로그인 정보로 결정하며 `myRole`은 `OWNER`입니다. 초기 초대코드는 32바이트 난수의 Base64URL 표현으로 생성한 43자 문자열입니다.

팀과 생성자의 OWNER 가입 기록을 같은 트랜잭션으로 저장합니다. 가입 기록 저장이 실패하면 팀도 롤백합니다. DB의 초대코드 UNIQUE 충돌만 새 트랜잭션에서 최대 5회 시도하고, 다른 DB 오류는 재시도하지 않습니다. 기존 테이블과 제약을 사용하므로 마이그레이션은 추가하지 않습니다.

공통 `TeamAuthorizationService`는 서버가 확인한 로그인 사용자 ID와 대상 팀 ID를 받아 가입 정보를 반환합니다.

| 메서드 | 허용 대상 |
| --- | --- |
| `requireTeamMember(userId, teamId)` | 해당 팀의 OWNER 또는 MEMBER |
| `requireTeamOwner(userId, teamId)` | 해당 팀의 OWNER |

잘못된 사용자 ID는 401, 잘못된 팀 ID는 400, 없는 팀은 404, 미가입 사용자 또는 OWNER가 아닌 사용자의 OWNER 요청은 403으로 처리합니다. 팀 조회·초대코드 재발급·Agent 등록 등에서 공통 검사를 재사용합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/v1/teams` | 본인이 가입한 팀 목록, 최근 가입순 |
| GET | `/api/v1/teams/{teamId}` | 가입자만 팀 상세 조회 |
| GET | `/api/v1/teams/{teamId}/members` | 가입자만 팀원과 역할 조회 |
| POST | `/api/v1/teams/{teamId}/invites` | OWNER만 초대코드 재발급, 기존 코드 무효화, 201 |
| POST | `/api/v1/teams/join` | `{ "inviteCode": "..." }`로 참여, 신규 201·기존 가입 200 |

기본 팀 응답은 `teamId/name/myRole/createdAt`이며 일반 목록·상세에 초대코드를 포함하지 않습니다. 팀원 응답은 `userId/name/nickname/role/joinedAt`입니다. 초대코드는 생성·재발급 응답에서만 반환합니다. 참여 응답은 `teamId/name/myRole/joinedAt/replayed`이며 이미 가입한 OWNER의 역할을 MEMBER로 바꾸지 않습니다. 잘못되었거나 재발급으로 무효화된 코드는 404입니다. 참여와 코드 재발급은 같은 팀 행 잠금으로 직렬화합니다.

## 6-2. Agent 관리와 연결 토큰

아래 관리 API는 사용자 세션을 사용하고 변경 요청에는 CSRF 토큰이 필요합니다.

| 메서드 | 경로 | 권한·동작 |
| --- | --- | --- |
| POST | `/api/v1/agents/teams/{teamId}` | 팀 회원이 본인 소유 Agent 등록, 201 |
| GET | `/api/v1/agents/teams/{teamId}` | 팀 회원이 Agent 목록 조회 |
| GET | `/api/v1/agents/{agentId}` | 같은 팀 회원이 상세 조회 |
| POST | `/api/v1/agents/{agentId}/token` | 소유자만 토큰 발급·회전, 201 |

등록 요청은 `name`(필수·100자), `roleDescription`(선택·255자), `toolType`(`CLAUDE_CODE` 또는 `CODEX`), `projectName`(필수·120자)입니다. 소유자는 로그인 정보로 결정하며 등록 초기 상태는 OFFLINE입니다. Agent 프로필 수정·삭제 및 토큰 폐기 API는 현재 MVP 범위에서 제외합니다.

조회 응답은 `agentId/teamId/ownerUserId/name/roleDescription/toolType/projectName/status/lastSeenAt/createdAt`입니다. 토큰과 해시는 포함하지 않습니다. 토큰 발급 응답의 `data`는 `agentId/token`이며 원문은 이 응답에서만 제공합니다. 토큰은 32바이트 난수로 만들고 DB에는 SHA-256 64자리 해시만 저장합니다. 한 Agent의 활성 토큰은 하나이며 재발급하면 기존 토큰을 거부합니다. 현재 MVP는 만료 시각 없이 재발급으로 토큰을 교체합니다.

토큰 재발급이 커밋된 후 기존 WebSocket 연결과 ACK 대기를 정리하고 접속 상태를 갱신합니다. 롤백된 변경으로 연결을 끊지 않습니다. 사용자 로그아웃은 브라우저의 세션과 인증·CSRF 정보를 정리하며 별도의 Agent 토큰을 무효화하지 않습니다.

## 6-3. MCP·브로커용 Agent 인증

`/ws/broker`와 `/api/v1/agent/**`는 `Authorization: Bearer <Agent 토큰>`으로 인증합니다. 사용자 세션과 서로 대체하지 않습니다. DB의 현재 토큰 해시와 소유자의 팀 가입을 확인해 `AgentPrincipal(agentId, ownerUserId, teamId)`를 구성합니다. Agent 전용 REST는 쿠키 인증을 사용하지 않아 해당 체인에만 CSRF 예외를 적용하며, 사용자 관리 API의 CSRF 보호는 유지합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/v1/agent/context` | 토큰의 Agent 정보 확인 |
| GET | `/api/v1/agent/agents` | 토큰의 팀에 속한 Agent 목록 |
| POST | `/api/v1/agent/tasks` | `{requestKey, receiverAgentId, prompt}`로 작업 생성 |

Agent 작업 생성은 요청 사용자·요청 Agent·팀을 토큰에서 채워 기존 생성 서비스를 공유합니다. 수락·완료·결과 조회는 각각 4·5번 담당자의 API에 같은 인증·권한 계약을 적용해야 합니다.

`register_agent`·`list_agents`의 TypeScript 도구 정의와 중앙 REST 클라이언트는 별도 `DevLink_client` 저장소의 신규 모듈로 제공합니다. 기존 MCP 공통 설정·실행·도구 등록 파일은 변경하지 않으며 실제 도구 등록과 호출 경로 연결은 2번 담당자가 수행해야 합니다. `packages/mcp-server/AGENT_MANAGEMENT.md`에 연결 계약을 정리했습니다. 최초 등록은 로그인 사용자 세션과 CSRF로 등록·토큰 발급을 수행하고 이후 목록은 Agent 토큰으로 조회합니다. 토큰은 모델의 도구 입력·출력에 포함하지 않습니다. CLI의 자격정보 영구 보관과 브로커의 로컬 실행·WebSocket 연결 규약은 1·2·6번 담당자의 연동 범위입니다. `projectName`은 표시 정보이며 실제 작업 폴더는 로컬 실행 설정으로 지정해야 합니다.
