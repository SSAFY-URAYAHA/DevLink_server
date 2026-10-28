# 작업 생성 및 상태 변경 API 설계

> 구현 진행: 생성 트랜잭션 서비스와 생성 Controller를 구현했다. 인증·인가 및 실제 전달 모듈 연결은 후속 범위다. 현재 입력 검증 위치와 세션 userId 연동 계약은 [작업 생성 구현 현황](task-creation-implementation.md)을 참고한다. 아래의 미구현 계약은 후속 설계다.

## 범위와 현재 구현

2026-10-28 기준 구현 제안이다. 근거는 `C:/dev/Dev-Link-Docs/1차_MVP_요구사항_명세서.md`의 REQ-001~009, RCV-002~008과 `API_명세_및_담당자_배정.md`다.

**사용자(4번)의 구현 범위는 작업 생성 API와 상태 변경 API의 수락·실패 처리다. 완료 API의 Controller·Service·DTO 및 완료 결과·파일 저장 구현은 5번 담당자의 범위다.** 이 문서에서 완료를 언급하는 부분은 공통 도메인 규칙과 트랜잭션의 협업 계약이며 사용자에게 완료 API 구현을 배정하는 의미가 아니다.

현재 `Task.create/accept/complete/fail`, `@Version`, 생성 요청 키의 유니크 제약, 입력 오류의 HTTP 400 매핑은 구현돼 있다. 아래 Controller, Service, DTO 및 전달 인터페이스는 신규 설계다. `TaskStatus.canTransitionTo`는 구현돼 있지 않으며 API 구현의 선행 조건도 아니다. 기존 행동별 메서드를 사용한다.

작업 생성은 상태 변경의 기능상 선행 단계다. 다만 개발할 때는 저장한 테스트 Task로 상태 변경 서비스를 먼저 검증할 수 있다. 사용자 범위의 통합 순서는 인증·전달 계약 → 생성 → 5번의 상세 조회 연동 → 상태 변경(수락·실패)이다. 이후 완료 흐름 통합은 5번 담당자와 공동으로 진행한다.

## 1. 먼저 맞출 공통 계약

| 항목 | 설계 |
| --- | --- |
| 인증 | 검증된 `AgentPrincipal(agentId, ownerUserId, teamId)`를 서비스에 전달. 본문의 요청자 ID는 신뢰하지 않음 |
| 권한 | 현재 Agent 등록·소유권·팀 접근 권한 확인. 상태 변경은 지정 수신 Agent만 가능 |
| 응답 | 기존 `ApiResponse(success, data, code, message)` 재사용 |
| 버전 | 생성·상세·상태 변경 응답에 `version` 포함. 상태 변경은 `expectedVersion` 필수 제안 |
| 시각 | 서버가 결정. 클라이언트에서 시작/완료 시각을 받지 않음. 현재 LocalDateTime의 시간대 규약은 배포 전에 통일 |
| 오류 | 입력 400, 인증 401, 권한 403, 미존재/타 팀 리소스 404, 전이·버전·키 충돌 409 |

REST Agent 인증 연결은 3번 담당자와 확정해야 한다. 현재 인증 필터는 WebSocket 전용이므로 연결 토큰이 REST에서도 그대로 유효하다고 가정하지 않는다. 세션 인증을 사용한다면 선택한 Agent가 로그인 사용자 소유인지 확인해 컨텍스트를 만든다. Bearer 전용 방식을 채택한다면 대상 경로에만 별도 보안 체인을 적용한다.

기존 명세의 `GET /tasks/{teamId}`와 `GET /tasks/{taskId}`는 동일한 라우트 패턴이다. 상태 변경의 버전 조회를 위해 5번과 **상세 `GET /api/v1/tasks/{taskId}`, 목록 `GET /api/v1/teams/{teamId}/tasks`**로 구분하는 안을 합의한다. POST는 원문 명세의 `/api/v1/tasks/{teamId}`를 유지한다.

## 2. 작업 생성 API

`POST /api/v1/tasks/{teamId}` — 신규 생성 201, 동일 요청 재조회 200.

```json
{
  "requestKey": "7c6fcdf0-091c-4e57-92d5-e3f666391b63",
  "receiverAgentId": 4,
  "prompt": "로그인 실패 시 오류 메시지를 표시하도록 수정해주세요."
}
```

| 필드 | 검증 |
| --- | --- |
| teamId, receiverAgentId | 양의 정수, 등록된 동일 팀 리소스 |
| requestKey | 필수, 공백 불가, 최대 64자. 브로커가 생성하고 같은 요청 재시도 시 재사용 |
| prompt | 필수, 공백 불가. 내용은 임의로 trim/변환하지 않고 저장·중복 비교 |

요청 크기 상한은 MCP·브로커와 별도 합의한다. 현재 TEXT 컬럼의 어노테이션 `length=255`를 프롬프트 최대 길이라고 해석하지 않는다. 요청자 Agent/사용자 ID는 인증 컨텍스트로 채운다. 자기 자신에 대한 요청 금지는 원문에 없으므로 임의로 추가하지 않는다.

성공 응답 예시:

```json
{
  "success": true,
  "data": {
    "taskId": 101,
    "receiverAgentId": 4,
    "status": "PENDING",
    "version": 1,
    "replayed": false,
    "delivery": { "status": "DELIVERED", "reasonCode": null }
  },
  "code": null,
  "message": null
}
```

`version`은 예시이며 고정값이 아니다. `delivery.status`는 `DELIVERED`, `FAILED`, `UNKNOWN`으로 제안한다. 성공은 소켓 write 성공이 아니라 브로커 ACK 수신이다. ACK 타임아웃은 `FAILED / ACK_TIMEOUT`으로 보고하되 실제 미수신을 단정하지 않는다. 전달 결과가 아직 기록되지 않았거나 복구할 수 없으면 `UNKNOWN`이다. 저장 후 전달 실패도 201과 작업 ID를 반환한다. API의 `success`는 작업 저장 성공이며 전달 성공은 별도 필드다.

### 생성 처리와 트랜잭션

```text
TaskCreationService.create() — 전체를 트랜잭션으로 감싸지 않음
  1. 인증·팀 접근·입력 검증
  2. 사용자 ID + requestKey로 기존 작업 조회
     └─ 동일 요청이면 기존 작업 반환, 재전달하지 않음
  3. 신규이면 수신 Agent 및 활성 브로커 확인
  4. TaskCreationWriter.create() — 별도 Bean의 @Transactional
     ├─ 등록·팀 권한 재검증
     ├─ Task.create() 및 CREATED 이력 저장
     └─ 커밋
  5. 커밋된 작업을 수신 브로커에 전달하고 제한 시간 내 ACK 대기
  6. TaskDeliveryRecorder.record() — 별도 트랜잭션
     └─ ACK 시 deliveredAt 및 DELIVERED 이력, 실패 시 DELIVERY_FAILED 이력
  7. 최신 작업 스냅샷과 확인한 전달 결과 반환
```

DB의 `Agent.status`만으로 실제 연결을 판단하지 않는다. 6번 담당자의 활성 연결 조회를 사용한다. 조회 시 오프라인이면 저장하지 않고 `409 RECEIVER_OFFLINE`, 연결 조회 시스템 자체가 불능이면 `503 DELIVERY_SERVICE_UNAVAILABLE`로 제안한다. 조회 직후 연결이 끊어질 수 있으므로 온라인 확인은 전달 성공 보장이 아니다. 저장 뒤 연결 실패는 작업을 삭제하거나 FAILED로 바꾸지 않는다. 수신자의 별도 상태 변경이 없다면 PENDING을 유지한다.

네트워크 ACK 대기 동안 DB 트랜잭션을 유지하지 않는다. 전달·결과 기록 실패를 작업 생성 실패로 오인해 새 작업을 만들지 않도록, 커밋 이후에는 작업 ID를 반드시 보존한다. 기록 실패 시 로그를 남기고 응답의 전달 결과와 DB 기록 여부를 구분한다. 프로세스 종료로 응답 자체를 잃었다면 같은 requestKey로 재조회한다. 자동 재전달은 MVP 범위에서 제외한다.

### 중복 요청과 동시 생성

- 기존 `(requester_user_id, request_key)` 유니크 제약을 유지한다. Agent 단위가 아닌 사용자 단위 키다.
- 기존 요청과 `teamId`, `requesterAgentId`, `receiverAgentId`, `prompt`가 같으면 같은 작업 ID와 현재 상태·버전을 반환한다. 최초 HTTP 응답 바이트를 재생하는 계약은 아니다.
- 같은 키에 다른 payload 또는 다른 요청 Agent를 사용하면 `409 REQUEST_KEY_CONFLICT`. 기존 작업 내용은 노출하지 않는다.
- 기존 요청 확인은 온라인 검사보다 먼저 한다. 이미 생성된 작업의 재조회는 수신자가 현재 오프라인이어도 가능하다.
- 두 요청이 동시에 기존 작업을 찾지 못하면 유니크 제약이 최종 방어한다. 패배한 생성 트랜잭션이 완전히 롤백된 뒤 외부 서비스에서 새 트랜잭션으로 기존 작업을 조회·비교한다. rollback-only 트랜잭션 안에서 계속 조회하지 않는다.
- 모든 DB 제약 오류를 중복 요청으로 처리하지 않는다. 요청 키 유니크 제약 위반만 복구한다. 최초 생성에 성공한 요청만 전달을 수행한다.
- 중복 요청이 최초 전달 완료 전에 도착하면 `delivery.status=UNKNOWN`을 반환할 수 있다. 기존 작업을 다시 전송하지 않는다.

### 전달 기록과 상태 변경의 경합

`markDelivered()`도 Task를 갱신하여 `@Version`을 증가시킨다. 브로커가 바로 수락하면 전달 기록과 수락이 충돌할 수 있다. 전달 기록은 최신 엔티티를 다시 읽어 `deliveredAt`만 변경하며, 충돌 시 네트워크 재전송 없이 기록 작업만 제한적으로 재시도한다. 종료 상태를 PENDING으로 덮어쓰지 않는다. 재시도 한도 초과 시 기록 실패를 남긴다.

생성 응답은 전달 기록 이후의 최신 버전을 반환하고, 수신자는 상세 조회의 버전으로 상태를 변경한다. 이후에도 충돌할 수 있으므로 409를 받으면 재조회한다. 향후 불필요한 충돌이 많아지면 전달 메타데이터를 별도 테이블로 분리한다.

## 3. 작업 상태 변경 API

`PATCH /api/v1/tasks/{taskId}/status` — 성공 200.

```json
{ "status": "IN_PROGRESS", "expectedVersion": 1 }
```

```json
{
  "status": "FAILED",
  "expectedVersion": 2,
  "reason": "테스트 실패로 작업을 완료하지 못했습니다.",
  "partiallyModified": true
}
```

| 요청 | 허용 상태 | 도메인 호출 |
| --- | --- | --- |
| IN_PROGRESS | 현재 PENDING | `task.accept(actor.agentId())` |
| FAILED | 현재 IN_PROGRESS | `task.fail(actor.agentId(), reason, partiallyModified)` |
| COMPLETED | PATCH에서 거부 | 5번 담당자의 별도 완료 API 사용 |

DTO의 `expectedVersion`은 `Long`으로 받아 null과 0 이상을 검사한다. `partiallyModified`는 `Boolean`으로 받아 FAILED에서 누락을 거부한다. `false`는 유효하다. FAILED의 reason은 null·공백 불가, IN_PROGRESS의 실패 전용 필드는 null이어야 한다(생략과 명시적 null은 동일 취급). 잘못된 JSON/enum, PENDING·COMPLETED 대상 요청은 400이다. 존재하는 상태지만 현재 상태에서 전이 불가능하면 409다.

Controller에서 검증한 입력을 `AcceptTaskCommand` 또는 `FailTaskCommand`로 변환한다. DTO 교차 필드 검증과 도메인 검증을 모두 두어 내부 호출에서도 불변식을 지킨다.

```text
TaskStatusService.changeStatus() — @Transactional
  1. Task 조회: 미존재/다른 팀 404
  2. 수신 Agent 권한 검사: 불일치 403
  3. expectedVersion 비교: 불일치 409
  4. accept 또는 fail 호출: 잘못된 현재 상태 409
  5. STATUS_CHANGED 이력 저장
  6. flush: 낙관적 잠금 충돌 검사 및 새 버전 확보
  7. 불변 알림 스냅샷 발행, 응답 DTO 구성
커밋 성공 → AFTER_COMMIT 리스너에서 요청자 브로커 알림
```

버전 검사는 권한 검사 뒤에 수행한다. 권한 검사 공유를 위해 `Task.requireReceiver()`를 서비스에서도 호출 가능하도록 분리하는 안을 권장한다. accept/fail도 해당 검사를 다시 호출한다. 전이 규칙은 기존 도메인 메서드 한 곳에서 관리하고 서비스에 복제하지 않는다.

응답 data는 `taskId`, `status`, `version`, `resultText`, `partiallyModified`, `startedAt`, `completedAt`이다. 실패 사유는 현재 저장 모델에 맞춰 resultText로 반환한다. 성공 응답은 커밋 완료를 의미하며 알림 전달 여부를 의미하지 않는다.

`flush` 성공만으로 커밋이 완료된 것은 아니다. 커밋 실패도 요청 실패이며 알림이 나가면 안 된다. 리스너는 전송 실패를 잡아 처리하고 별도 Bean의 `REQUIRES_NEW`에서 NOTIFY_FAILED를 저장한다. 그 저장 실패도 로깅하고 성공 응답을 실패로 바꾸지 않는다. 전송 대기 시간은 제한한다. 커밋 후 프로세스 종료로 인한 알림 유실을 막으려면 추후 outbox가 필요하다.

동일 상태 재요청도 409다. 시간 초과 후 상세 조회로 처리 결과를 확인하며, 동일 상태라는 이유만으로 다른 실패 사유를 덮어쓰지 않는다.

## 4. 예외 매핑

| 예외/상황 | HTTP 및 코드 | 구현 상태 |
| --- | --- | --- |
| InvalidTaskInputException, DTO 검증 | 400 VALIDATION_FAILED | 기존 매핑 재사용 |
| JSON/enum 변환, 경로 타입 오류 | 400 VALIDATION_FAILED | 명시적 매핑 추가 필요 |
| 인증 실패 | 401 AUTHENTICATION_REQUIRED | REST 인증 연결 필요 |
| TaskActorNotAllowedException | 403 ACCESS_DENIED | 신규 전용 예외 필요 |
| 작업/수신자 미존재 또는 타 팀 | 404 RESOURCE_NOT_FOUND | 서비스 처리 |
| InvalidTaskTransitionException | 409 INVALID_TASK_TRANSITION | 신규 예외·코드 필요 |
| 버전 불일치/JPA 낙관적 잠금 충돌 | 409 TASK_VERSION_CONFLICT | 신규 코드·매핑 필요 |
| 생성 키 재사용 충돌 | 409 REQUEST_KEY_CONFLICT | 신규 코드 필요 |
| 생성 전 수신자 오프라인 | 409 RECEIVER_OFFLINE | 신규 코드 필요 |
| 생성 전 연결 조회 불능 | 503 DELIVERY_SERVICE_UNAVAILABLE | 신규 코드 필요 |

일반 IllegalArgumentException/IllegalStateException 전체를 403/409로 매핑하지 않는다. 현재 Task 권한·전이 검사의 예외를 전용 예외로 교체하고 관련 테스트 기대값도 변경한다.

## 5. 클래스와 공동 구현 경계

| 구성 요소 | 책임 |
| --- | --- |
| TaskController | 생성/상태 변경 라우트, DTO 검증, 응답 코드 |
| TaskCreationService | 중복 복구, 생성 커밋 후 전달 조율 |
| TaskCreationWriter | 생성과 CREATED 이력의 원자적 저장 |
| TaskDeliveryRecorder | 전달 결과 별도 저장, 충돌 시 기록만 재시도 |
| TaskStatusService | 수락/실패와 상태 이력의 트랜잭션 |
| TaskConnectionGateway | 6번 제공: 활성 연결 조회, 작업 전송 및 ACK 결과, 상태 알림 |
| TaskStatusChangedListener | 커밋 후 알림과 실패 처리 |

3번과는 REST 인증·팀 접근 검증, 5번과는 version을 포함한 상세 조회·완료 트랜잭션·결과 저장 소유권, 6번과는 ACK 상관관계 ID·타임아웃·활성 연결 조회·알림 스냅샷을 맞춘다. 전달 메시지는 최소 taskId, teamId, requesterAgentId, receiverAgentId, prompt, version을 포함하고 ACK를 해당 전송 시도에 연결한다. 같은 taskId를 재수신해도 브로커가 중복 실행하지 않도록 한다.

5번 담당자가 구현할 완료 서비스는 PATCH를 호출하지 않고 동일 Task.complete 규칙을 사용하여 결과·파일·검증 결과·이력을 한 트랜잭션에 저장한다. 사용자는 이 공통 규칙의 호출 계약만 협의하며 완료 서비스를 구현하지 않는다. 실패 데이터가 현재처럼 Task 필드뿐이면 TaskStatusService에서 저장하고, 별도 실패 결과 테이블이 추가되면 5번의 저장 모듈도 동일 트랜잭션에 참여하도록 협의한다.

## 6. 구현 순서와 수락 기준

1. 인증 컨텍스트, 전달 인터페이스, 상세 조회 경로/version 계약 확정.
2. 요청·응답 DTO와 도메인 전용 예외 및 HTTP 매핑 구현.
3. 생성: 정상 생성, 오프라인 무저장, 키 중복/상이한 payload, 동시 생성 한 건 보장 검증.
4. 전달: 커밋 전 미전송, ACK 실패 후 작업 보존, 중복 요청 무재전송, 전달 기록과 수락 경합 검증.
5. 상태 변경: 수신자/팀 권한, 빈 실패 사유·부분 수정 누락, 완료 우회 거부, 버전 충돌 검증.
6. 트랜잭션: 이력/결과 저장 실패 시 롤백, 롤백 시 무알림, 알림 실패 시 상태 유지 검증.
7. 실제 DB의 독립 트랜잭션을 사용하는 동시성 테스트와 MVC의 400/401/403/404/409 응답 테스트 수행.

거절·취소는 MVP에 추가하지 않는다. 이후 행동별 권한 메서드와 상태 전이를 추가하며, 진행 보고는 상태를 변경하지 않고 재시도는 새 작업을 생성한다.

## 7. 동시 요청과 트랜잭션: 낙관적 락 / 조건부 UPDATE

권장안은 현재 Task의 `@Version`과 도메인 메서드를 사용하는 낙관적 락이다. 조건부 UPDATE는 대안이며 같은 행동에서 두 방식을 중복 실행하지 않는다. 두 방식 모두 상태와 이력을 같은 DB 트랜잭션으로 묶어야 한다. 락은 경쟁 갱신을 제어하고, 트랜잭션은 여러 저장의 원자성을 보장한다.

### 생성 시 중복은 별도 문제

새 Task에는 경쟁할 기존 행이 없으므로 `@Version`이 중복 생성을 막아주지 않는다. 생성 트랜잭션에서 Task와 CREATED 이력을 함께 저장하고, `(requester_user_id, request_key)` 유니크 제약으로 중복을 막는다. 충돌 요청은 롤백 뒤 기존 결과를 조회한다. 상태가 같아도 키가 다르면 별개 작업이다.

### 상황별 처리

| 상황 | 낙관적 락 | 조건부 UPDATE | 이력 |
| --- | --- | --- | --- |
| 서로 다른 두 Agent가 수락 | 지정 수신자가 아닌 Agent는 권한 검사에서 403 | 사전 권한 검사와 UPDATE의 receiver 조건으로 보호 | 성공한 수신자만 저장 |
| 같은 수신자의 두 요청이 같은 버전을 읽고 수락 | 최초 커밋 하나만 성공, 나머지는 버전 충돌 409 | 허용 상태·버전이 일치하는 요청만 1행 갱신, 나머지는 0행으로 409 | 성공 트랜잭션만 커밋 |
| 완료 뒤 다시 완료 요청 | 현재 상태가 COMPLETED이므로 전이 거부 | WHERE status='IN_PROGRESS' 불일치로 0행 | 추가 이력·파일 저장 없음 |
| 상태 또는 이력 저장 실패 | 전체 롤백 | 전체 롤백 | 부분 커밋 불가 |

두 번째 요청이 첫 번째 커밋 후 조회했다면 낙관적 락 예외 대신 버전 사전 검사나 상태 검사에서 거부될 수 있다. 동일 버전을 읽은 요청도 앞선 트랜잭션이 롤백하면 뒤 요청이 성공할 수 있다. 보장하는 것은 최대 한 번의 성공 커밋이다.

완료 중복 처리는 5번과 공유할 계약이다. 기본안은 409이며 기존 결과를 덮어쓰지 않는다. 메시지 재전송을 성공으로 응답해야 한다면 별도의 commandId와 payload 비교, 처리 결과의 원자적 저장이 필요하다. COMPLETED 상태만 보고 동일 요청이라고 간주하면 서로 다른 결과까지 성공 처리하게 된다.

### A. 낙관적 락

서비스의 `@Transactional` 안에서 Task 조회 → 권한/요청 버전 검사 → `task.accept()` → STATUS_CHANGED 이력 저장 → flush → 커밋을 수행한다. JPA 버전 갱신은 개념적으로 다음과 같다(실제 SQL의 SET 컬럼은 다를 수 있다).

```sql
UPDATE tasks
SET status = 'IN_PROGRESS', started_at = :at, version = :oldVersion + 1
WHERE id = :taskId AND version = :oldVersion;
```

다른 트랜잭션이 이미 version을 바꿨다면 갱신이 실패하며 낙관적 잠금 예외가 발생한다. 이 예외를 서비스 안에서 삼키지 않고 트랜잭션을 롤백한 뒤 HTTP 409로 변환한다. 이미 INSERT SQL이 실행된 이력도 커밋 전이라면 함께 롤백된다. 상태 변경 충돌을 자동 재시도하여 변경된 상태에 명령을 다시 적용하지 않는다.

### B. 조건부 UPDATE

읽은 뒤 Java에서 상태만 확인하는 대신, 갱신 SQL 자체에 허용 상태·수신자·팀·버전 조건을 포함한다.

```sql
UPDATE tasks
SET status = 'IN_PROGRESS',
    started_at = :at,
    version = version + 1
WHERE id = :taskId
  AND team_id = :teamId
  AND receiver_agent_id = :actorAgentId
  AND status = 'PENDING'
  AND version = :expectedVersion;
```

같은 서비스 트랜잭션에서 영향받은 행 수가 1이면 STATUS_CHANGED 이력을 저장하고, 0이면 예외를 던져 롤백한다. 사전 조회로 미존재/타 팀 404와 권한 403을 구분하고, 사전 검사를 통과했는데 0행이면 경합을 포함한 409로 처리한다. 0행만으로 미존재·권한·상태·버전 중 정확한 원인을 단정하지 않는다.

이 SQL도 DB 행 잠금으로 직렬화될 수 있으므로 '락을 전혀 사용하지 않는 방식'은 아니다. 상태 조건만으로도 PENDING의 단발성 수락은 보호할 수 있지만, 현재 모델의 다른 JPA 쓰기와 일관성을 유지하기 위해 version 검사·증가도 포함한다.

JPA bulk/native UPDATE는 엔티티 메서드와 자동 버전 증가를 우회한다. 따라서 다음을 구현자가 책임진다.

- 수락·실패별 SQL에 올바른 출발 상태와 모든 변경 필드를 명시한다. 실패 입력의 사유/부분 수정 검증도 실행한다.
- version을 SQL에서 직접 증가시킨다.
- 같은 Task를 관리 상태로 수정한 뒤 bulk UPDATE를 섞지 않는다. 사전 조회는 projection을 사용하거나, bulk 갱신 후 영속성 컨텍스트를 적절히 비우고 새 값을 조회한다. clear 전에 저장되지 않은 변경이 유실되지 않도록 순서를 관리한다.
- UPDATE와 이력 INSERT를 같은 트랜잭션에서 실행하고 이력에는 실제 성공한 전이를 기록한다.

현재 규모에서는 이 추가 관리보다 기존 도메인 규칙을 그대로 사용하는 A안이 단순하다. 조건부 UPDATE는 특정 전이의 높은 경합이나 명확한 SQL 제어가 필요해질 때 도입한다.

### 원자성의 공통 조건

상태 변경 서비스는 Spring 프록시를 통해 호출되는 public `@Transactional` 메서드로 둔다. TaskRepository와 TaskEventRepository는 같은 데이터소스/트랜잭션에 참여하며, 상태 이력에 REQUIRES_NEW를 사용하지 않는다. 런타임 예외는 전파하고 checked exception을 사용하는 경우 rollbackFor를 명시한다. AFTER_COMMIT에서 처리하는 것은 브로커 알림이며, 필수 상태 이력 INSERT가 아니다.

DB 롤백으로 외부 메시지를 회수할 수 없으므로 커밋 전에 알리지 않는다. 상태와 이력을 각각 별도 트랜잭션으로 저장하면 낙관적 락이나 조건부 UPDATE 어느 쪽도 부분 성공을 막아주지 못한다.
