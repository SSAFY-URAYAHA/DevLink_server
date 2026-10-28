# 작업 상태 변경을 위한 Task 메서드 설계

이 문서는 구현 제안이다. 현재 엔티티 이름은 `Tasks`가 아닌 `Task`이며, 테이블은 `tasks`다. API 구현 전에 도메인 메서드와 서비스 간 계약을 정리한다.

## 1. 근거와 범위

- `C:/dev/Dev-Link-Docs/1차_MVP_요구사항_명세서.md`: RCV-002, RCV-003, RCV-006~008, EXE-003.
- `C:/dev/Dev-Link-Docs/API_명세_및_담당자_배정.md`: 상태 변경 API, 완료 API, 4·5·6번 담당자의 공동 구현 경계.
- 현재 코드: `Task.accept/complete/fail`, `TaskStatus`, `TaskEvent`, `AgentPrincipal`, `TaskRepository`, `SecurityConfig`.

MVP는 `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`만 지원한다. 거절·취소는 확장 설계로 남긴다. 상태 변경 PATCH는 수락·실패를 처리하고, 완료는 결과·파일·검증 결과를 받는 별도 API로만 처리한다.

## 2. 권장 구조

**공개 메서드는 행동별로 유지하고, 상태 전이 검증만 공유한다.** 임의의 상태를 지정하는 공개 `setStatus`나 `changeStatus(TaskStatus)`는 제공하지 않는다. 각 행동에 필요한 입력을 메서드 시그니처로 드러내고 결과 없는 종료를 방지한다.

| 계층 | 책임 | 제안 메서드 |
| --- | --- | --- |
| Controller | 요청 형식 검증, 인증된 Agent 식별, 응답 변환 | `changeStatus(taskId, request, principal)` |
| TaskStatusService | 작업 조회, 팀 접근 검증, 버전 비교, 트랜잭션, 이력·이벤트 처리 | `changeStatus(taskId, actor, command)` |
| TaskCompletionService | 완료 결과·변경 파일·검증 결과와 상태를 한 트랜잭션에 저장 | `complete(taskId, actor, command)` |
| Task | 행동별 권한·전이·필수 값 검증과 필드 변경 | `accept`, `fail`, `complete` |
| TaskStatus | 순수 상태 전이 규칙 | `canTransitionTo(target)`, `isTerminal()` |
| 알림 어댑터 | 커밋 후 요청자 브로커로 알림 | `notifyStatusChanged(event)` |

서비스 둘은 동일한 `Task` 메서드/전이 규칙을 사용한다. 완료 서비스가 상태 변경 PATCH나 별도 트랜잭션의 상태 변경 서비스를 호출하지 않는다.

## 3. 도메인 메서드 계약

아래 시그니처는 제안이며 아직 구현되지 않았다.

```java
// Task: 기존 호출부가 있으면 기존 시그니처를 유지하는 오버로드로 이행한다.
public void accept(Long actorAgentId, LocalDateTime occurredAt);
public void fail(Long actorAgentId, String reason,
                 boolean partiallyModified, LocalDateTime occurredAt);
public void complete(Long actorAgentId, String resultSummary,
                     LocalDateTime occurredAt);

// Task 내부에서만 호출. 검증과 필드 변경을 분리한다.
private void requireReceiver(Long actorAgentId);
private void requireTransitionTo(TaskStatus target);

// TaskStatus: 프레임워크나 저장소에 의존하지 않는 상태 그래프
public boolean canTransitionTo(TaskStatus target);
public boolean isTerminal();
```

| 행동 | 허용 전이 | 행위자 | 필수 입력 | 변경 필드 |
| --- | --- | --- | --- | --- |
| `accept` | `PENDING → IN_PROGRESS` | 지정 수신 Agent | 발생 시각 | `status`, `startedAt` |
| `fail` | `IN_PROGRESS → FAILED` | 지정 수신 Agent | 공백 아닌 실패 사유, 부분 수정 여부, 발생 시각 | `status`, `resultText`, `partiallyModified`, `completedAt` |
| `complete` | `IN_PROGRESS → COMPLETED` | 지정 수신 Agent | 공백 아닌 결과 요약, 발생 시각 | `status`, `resultText`, `completedAt` |

모든 메서드는 **권한 → 전이 → 입력 검증을 끝낸 후** 필드를 변경한다. 예외가 발생하면 엔티티의 상태·결과·시각이 하나도 바뀌지 않아야 한다. `null` Agent/시각도 거부한다. 종료 시각은 시작 시각보다 빠를 수 없다. 시각은 클라이언트 입력을 받지 않고 서비스에 주입한 `Clock`으로 한 번 생성한다. 저장 시간대는 프로젝트 공통 규약으로 통일한다.

현재 `complete`, `fail`은 결과 문자열을 검사하지 않아 `null`/공백 결과로 종료할 수 있다. 구현 시 도메인에서도 검증해야 한다. DTO 검증만으로는 완료 서비스나 향후 내부 호출의 우회를 막지 못한다.

`TaskStatus.canTransitionTo`는 처음에는 enum의 간단한 `switch`로 충분하다. `null`, 동일 상태, 종료 상태에서의 전이는 모두 `false`다. 권한은 상태 그래프에 넣지 않는다. 미래의 요청자 취소와 수신자 완료는 행위자가 다르기 때문이다.

## 4. PATCH 계약

`PATCH /api/v1/tasks/{taskId}/status`

수락 예시:

```json
{ "status": "IN_PROGRESS", "expectedVersion": 0 }
```

실패 예시:

```json
{
  "status": "FAILED",
  "expectedVersion": 1,
  "reason": "빌드 오류로 작업을 완료하지 못했습니다.",
  "partiallyModified": true
}
```

- `status`, 0 이상의 `expectedVersion`은 필수로 제안한다. 생성·상세·상태 변경 응답에 `version`을 노출해야 하며, 이 버전 필드는 기존 요구사항에 추가되는 계약이다.
- `FAILED`에서는 `reason`과 `partiallyModified`가 필수다. DTO의 부분 수정 여부는 `Boolean`으로 받아 누락과 `false`를 구분하고 검증 후 도메인의 `boolean`으로 변환한다.
- `IN_PROGRESS` 요청에 실패 전용 필드가 있으면 400으로 거부한다. `PENDING`·`COMPLETED`도 PATCH에서 거부한다. 특히 완료 API를 우회해 파일·검증 결과 없이 완료 상태만 저장하지 못하게 한다.
- 요청 본문에 행위자 ID를 받지 않는다. 인증 결과에서 `agentId`, `teamId`를 가져온다.
- 내부 명령은 `AcceptTaskCommand(expectedVersion)`와 `FailTaskCommand(expectedVersion, reason, partiallyModified)`로 구분한다. Controller 경계에서 변환하면 서비스까지 무관한 nullable 필드를 운반하지 않아도 된다. 초기에는 두 타입을 `sealed interface`로 묶는 정도면 충분하다.
- 성공은 공통 `ApiResponse` 안에 `taskId`, `status`, `version`, `startedAt`, `completedAt`, `resultText`, `partiallyModified`를 담아 200으로 반환한다. 실시간 알림 수신 성공을 의미하지 않는다.

## 5. 트랜잭션·동시성·알림

상태 변경 서비스의 순서는 다음과 같다.

1. `@Transactional` 안에서 작업을 조회한다. 미존재 또는 다른 팀 작업은 동일하게 404를 반환한다. 같은 팀의 다른 Agent는 도메인 권한 검사에서 403으로 거부한다.
2. 현재 버전과 `expectedVersion`을 비교한다. 불일치는 409다. 버전 비교 전에 접근 권한을 검사하여 타인에게 작업 버전을 노출하지 않는다.
3. `Clock`에서 시각을 생성하고 `task.accept(...)` 또는 `task.fail(...)`을 호출한다.
4. `TaskEventType.STATUS_CHANGED` 이력을 같은 트랜잭션에 저장한다. 이전/변경 상태, 행위자, 발생 시각을 기록한다. 원문 토큰은 저장하지 않는다.
5. `flush()`로 기존 `@Version` 충돌을 확인하고 증가한 버전을 응답 및 알림 스냅샷에 담는다. `expectedVersion` 사전 비교만으로 동시 요청을 막을 수 없으므로 JPA 버전 검사를 함께 사용한다.
6. 불변 `TaskStatusChangedEvent`를 발행한다. 스냅샷에는 작업·요청자 Agent·팀 ID, 이전/새 상태, 결과, 부분 수정 여부, 버전, 발생 시각을 담고 JPA 엔티티를 전달하지 않는다.
7. 커밋 성공 후 `AFTER_COMMIT` 리스너에서 6번 담당자의 알림 인터페이스를 호출한다. 롤백된 요청은 알리지 않는다.

알림 실패는 리스너에서 처리하여 이미 커밋된 작업을 롤백하거나 상태 변경 API를 실패로 응답하지 않게 한다. 실패 이력 `NOTIFY_FAILED`는 별도 Spring Bean의 `REQUIRES_NEW` 트랜잭션으로 저장한다. 실패 이력 저장 오류도 로깅하고 전파하지 않는다. 커밋 후 리스너에서 원래 트랜잭션에 단순 저장하면 정상 커밋을 보장할 수 없다.

MVP에서는 커밋 직후 프로세스가 종료되면 알림을 놓칠 수 있으며 상세 조회로 상태를 확인한다. 향후 알림 전달 보장이 필요하면 상태 저장과 동일 트랜잭션에 outbox를 기록하고 별도 전달자가 전송한다. 현재 `TaskEvent`에는 전달 상태·재시도 정보가 없으므로 그대로 outbox라고 간주하지 않는다. 이벤트의 버전은 브로커가 오래된 알림을 식별하는 기준으로 사용한다.

동일 상태 요청은 성공으로 처리하지 않고 409로 반환한다. 네트워크 오류 후 클라이언트는 상세 조회로 결과를 확인한다. 추후 정확한 재요청 재생이 필요하면 작업 생성용 `requestKey`와 별개인 상태 변경 요청 키와 payload 검증·결과 저장을 도입한다. 단순히 상태가 같다는 이유로 서로 다른 실패 사유를 무시하면 안 된다.

## 6. 완료 API와의 공동 구현 경계

5번 담당자의 `TaskCompletionService.complete(...)`는 권한·버전을 확인하고 `Task.complete(...)`를 호출한 후 결과·변경 파일·검증 결과·상태 이력을 **같은 트랜잭션**에 저장한다. 파일 저장 실패 시 상태도 `IN_PROGRESS`로 롤백한다. `TaskFile` 저장과 완료 입력 전체 검증은 완료 서비스가 담당하고, `Task`에는 저장소나 파일 목록 저장 책임을 넣지 않는다.

현재 `Task`에는 별도 검증 결과 필드가 없다. 완료 API 구현 시 5번 담당자와 구조화된 검증 결과 저장 모델 및 마이그레이션을 정해야 한다. `resultText`에 임의로 합쳐 저장하는 규약을 여기서 확정하지 않는다. 실패 사유는 현재 모델대로 `resultText`에 저장한다.

상태 이력 생성 및 이벤트 스냅샷 생성이 두 서비스에서 반복되면 작은 공통 협력 객체로 추출한다. 별도 트랜잭션을 여는 공통 상태 변경 서비스나 범용 워크플로 엔진은 현재 필요하지 않다.

## 7. 오류 및 인증 연결

| 상황 | HTTP | 제안 오류 코드 |
| --- | --- | --- |
| 잘못된 JSON/enum, 필수 값 누락, 공백 사유, PATCH 완료 요청 | 400 | 기존 `VALIDATION_FAILED` |
| 인증 실패 | 401 | 기존 `AUTHENTICATION_REQUIRED` |
| 같은 팀이지만 행위자 권한 없음 | 403 | 기존 `ACCESS_DENIED` |
| 작업 미존재/다른 팀 | 404 | 기존 `RESOURCE_NOT_FOUND` |
| 허용되지 않는 현재 상태 전이 | 409 | 신규 `INVALID_TASK_TRANSITION` |
| 요청 버전 불일치/JPA 낙관적 잠금 충돌 | 409 | 신규 `TASK_VERSION_CONFLICT` |

현재 `Task`의 `IllegalArgumentException`/`IllegalStateException`은 전역 핸들러에서 500으로 처리된다. 구현 시 도메인 전용 예외(`TaskActorNotAllowedException`, `InvalidTaskTransitionException`, `InvalidTaskInputException`)를 정의하고 위 응답으로 매핑한다. 모든 `IllegalArgumentException`을 403으로 일괄 매핑하지 않는다. JSON 역직렬화 오류 및 낙관적 잠금 예외도 명시적으로 처리한다.

현재 Agent 인증 필터는 `/ws/broker` 전용이다. REST에서 `AgentPrincipal`이 자동으로 제공된다고 가정하면 안 된다. 3번 담당자의 REST 인증 계약에 연결해 검증된 Agent 컨텍스트를 제공해야 한다. 사용자 세션을 사용하면 Agent 소유권·팀 권한을 추가로 확인한다. Bearer 전용 체인을 선택한다면 그 경로에 한정해 인증·CSRF 정책을 구성하고 기존 세션 경로의 보호를 일괄 해제하지 않는다.

## 8. 확장 방법

| 확장 | 추가 지점 | 유지할 규칙 |
| --- | --- | --- |
| 거절 | `REJECTED`, `reject(actor, reason, at)`, `PENDING → REJECTED` | 수신 Agent만 허용, 사유·종료 시각 저장 |
| 취소 | `CANCELLED`, `cancel(actor, reason, at)`, `PENDING/IN_PROGRESS → CANCELLED` | 요청 Agent 권한을 별도로 검사; DB 취소와 로컬 실행 중단은 별개 |
| 진행 보고 | `reportProgress` 서비스와 기존 `PROGRESS` 이력 | 작업 상태 전이를 발생시키지 않음 |
| 재시도 | 새 작업 생성과 원본 작업 참조 | 종료된 원본을 `PENDING`으로 되돌리지 않음 |
| 수동 재전달 | 전달 서비스 | `PENDING`을 유지하며 상태 변경 API를 사용하지 않음 |

취소 요청·확인 단계가 필요하면 즉시 `CANCELLED`로 처리할지 중간 상태를 둘지 먼저 계약을 정한다. 상태 추가 시 enum 전이표뿐 아니라 종료 상태 판정, 행위자 권한, 필수 입력, 이력·알림, DB 제약, MCP 스키마, 테스트를 함께 갱신한다. 팀별로 전이 규칙 자체가 달라지는 요구가 생길 때 `TaskTransitionPolicy`로 분리한다.

## 9. 구현 시 검증 기준

- 네 상태의 모든 출발/도착 조합을 검증한다. 허용 전이 세 개 외에는 전부 거부한다.
- 잘못된 Agent, null/공백 결과, 잘못된 시각 요청이 필드를 변경하지 않는지 확인한다.
- FAILED 입력에서 `partiallyModified` 누락과 명시적 `false`를 구분한다.
- PATCH에서 완료가 불가능하고, 별도 완료 API가 공통 도메인 규칙을 사용하는지 확인한다.
- 실제 DB의 독립 트랜잭션 둘이 같은 버전을 읽은 뒤 각각 완료/실패를 시도하면 하나만 커밋되고 다른 하나는 409인지 검증한다.
- 결과·파일·이력 저장 실패 시 작업 상태도 롤백되고 알림이 발생하지 않는지 확인한다.
- 알림 실패 후에도 성공 응답과 변경 상태가 유지되며, 별도 실패 이력이 저장되는지 확인한다.
- REST Agent 인증, 다른 팀 접근, 수신자가 아닌 Agent 접근을 통합 테스트한다.

설계 문서만 추가했으며, 위 검증은 후속 구현의 수락 기준이다.
