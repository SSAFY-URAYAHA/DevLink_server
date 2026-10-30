# 결과 저장 트랜잭션·예외 처리 검증

대상: S15P31D108-77. 검증일: 2026-10-30 (Asia/Seoul).

## 목적과 대상

75번에서 구현한 완료 등록 API의 원자성과 동시성 처리를 검증한다. 정상 완료·입력 검증·권한·순차 재요청은 기존 `TaskCompleteIntegrationTest`의 52개 테스트가 담당한다. 이번 테스트는 저장이 시작된 이후의 실패와 실제 동시 요청을 추가한다.

- 테스트 코드: `src/test/java/com/devlink/server/TaskCompleteTransactionIntegrationTest.java`
- 완료 API: `POST /api/v1/tasks/{taskId}/complete`
- 경합 검증에 사용하는 실패 API: `PATCH /api/v1/tasks/{taskId}/status`
- 대상 서비스: `TaskCompleteService`, 기존 `TaskStatusService`, `TaskCompletionOutboxWriter`
- 대상 데이터: `tasks`, `task_files`, `task_events`, `outbox_events`
- 기준 소스 커밋: `e8704e1`. 현재 테스트 브랜치의 완료 구현은 75번의 코드이며, 이번 작업에서는 운영 코드를 수정하지 않는다.

## 환경과 준비 데이터

Java 21, Spring Boot 3.5.16, JUnit 5, Testcontainers PostgreSQL 16과 `test` 프로필을 사용한다. Docker가 실행되어 있어야 한다. 실제 임의 포트의 Spring 서버로 Java HttpClient 요청을 전송하고, 응답 후 별도 DB 조회로 최종 데이터를 검사한다.

Repository는 Mockito Spy로 실패 지점과 두 요청의 조회 시점을 제어한다. 정상 경로는 실제 JPA·PostgreSQL을 사용하며, 파일·이력·Outbox 저장 실패는 실제 PostgreSQL NOT NULL 제약 위반으로 발생시킨다. 잘못된 요청을 보내 입력 검증 단계에서 중단시키는 방식과 구분된다.

각 테스트는 다음 데이터를 트랜잭션으로 먼저 커밋한다.

- 유효한 Agent 토큰과 팀 멤버십
- `IN_PROGRESS` 작업과 현재 version
- 기존 변경 파일 1건
- 생성·수락 이력 2건
- 기존 상태 변경 Outbox 1건

이후 테이블의 모든 컬럼과 행을 스냅샷으로 보관한다. 롤백 후에는 단순 행 개수뿐 아니라 기존 데이터 내용이 모두 유지되는지도 비교한다.

테스트 클래스 자체에는 `@Transactional`을 붙이지 않는다. 실제 서비스의 트랜잭션 시작·커밋·롤백을 확인하기 위해서다. Outbox 자동 발행은 기존 test 프로필에서 비활성화되어 있으므로, 저장 결과를 검사하는 동안 알림 발행기가 데이터를 변경하지 않는다.

## 테스트 항목과 완료 기준

| 번호 | 테스트 메서드 | 실패·경합 재현 방법 | 기대 결과 |
| --- | --- | --- | --- |
| T01 | `fileConstraintFailureRollsBackAlreadyFlushedTaskAndFirstFile` | 첫 파일과 완료 상태를 실제 flush한 뒤 다음 파일 INSERT의 NOT NULL 제약 위반 | 500, 첫 파일·작업 변경까지 모두 롤백 |
| T02 | `historyConstraintFailureRollsBackTaskAndSavedFiles` | 작업·파일 flush 후 이력 INSERT의 NOT NULL 제약 위반 | 500, 작업·파일·이력 추가 없음 |
| T03 | `outboxConstraintFailureRollsBackFlushedTaskFilesAndHistory` | 작업·파일·이력 flush 후 Outbox INSERT의 NOT NULL 제약 위반 | 500, 앞선 변경 전체 롤백 |
| T04 | `beforeCommitFailureRollsBackEvenPersistedOutboxAndReturnsError` | 정상 Outbox까지 INSERT·flush한 뒤 beforeCommit 콜백에서 예외 발생 | 500, 성공 응답 없이 전체 롤백 |
| T05 | `deferredDatabaseConstraintAtCommitRollsBackEntireCompletion` | 연결 전용 TEMP 테이블의 지연 UNIQUE 제약을 위반하여 실제 PostgreSQL 커밋 실패 | INSERT 단계는 성공하고 커밋에서 500, 완료 데이터 전체 롤백 |
| T06 | `callerTransactionFailureAlsoRollsBackCompletionAndOutbox` | 상위 트랜잭션에서 완료 서비스를 호출·flush한 뒤 예외 발생 | 호출자의 예외 전파, 완료 결과와 Outbox도 함께 롤백 |
| T07 | `concurrentRequestsReadingSameVersionCommitOnlyOneResult` — completion | 같은 version을 읽은 두 HTTP 완료 요청을 동시에 진행 | 하나만 200, 다른 요청은 409 TASK_VERSION_CONFLICT |
| T08 | `concurrentRequestsReadingSameVersionCommitOnlyOneResult` — failure | 같은 version을 읽은 HTTP 완료 요청과 실패 요청을 동시에 진행 | 하나만 200, 다른 요청은 409 TASK_VERSION_CONFLICT |

T01~T03은 오류 직전 같은 트랜잭션에서 실제 반영된 중간 상태를 확인한다. 또한 PostgreSQL SQLSTATE `23502`를 확인해 예상한 DB 제약 위반 지점까지 도달했음을 보장한다. T04는 Outbox 행의 실제 저장을 확인한 후 애플리케이션의 커밋 전 훅에서 실패시킨다. T05는 중복 INSERT가 허용된 것을 확인한 후 PostgreSQL의 지연 제약 검사로 커밋 자체를 실패시킨다. TEMP 테이블은 해당 테스트 연결에만 존재하며 운영 스키마·마이그레이션을 변경하지 않는다.

모든 롤백 테스트에서 다음을 확인한다.

- 상태가 `IN_PROGRESS`로 유지된다.
- version, 결과, 완료 시각 및 기존 작업의 모든 컬럼이 원래 값과 일치한다.
- 추가 파일·이력·Outbox 행이 남지 않는다.
- 기존 파일·이력·Outbox의 내용과 ID는 유지된다.
- HTTP 실패 응답은 `success=false`, `data=null`, `code=INTERNAL_SERVER_ERROR` 형식이다. T06은 서비스 직접 호출이므로 HTTP 응답 대신 원래 예외를 확인한다.

T07~T08은 `CountDownLatch`로 두 서비스 트랜잭션이 같은 이전 version을 읽은 것을 확인한 뒤 진행한다. 시간 지연이나 단순 순차 호출에 의존하지 않는다. 요청별 결과와 파일 경로를 다르게 하여 성공한 요청의 데이터만 남는지 확인한다.

- version은 정확히 1 증가한다.
- 완료·실패 상태와 결과는 성공한 요청과 일치한다.
- 완료 요청이 이기면 해당 요청의 파일만 추가된다. 실패 요청이 이기면 완료 요청의 파일이 남지 않는다.
- 상태 이력과 Outbox는 각각 정확히 1건만 추가된다.
- 성공 응답·저장 작업·Outbox의 version과 상태가 일치한다.
- 기존 데이터는 모두 보존된다.

## 실행 방법

프로젝트 루트에서 실행한다. 의존성이 캐시되지 않은 환경에서는 `--offline`을 제외한다.

```powershell
# 77번 트랜잭션·동시성 검증
.\gradlew.bat test --offline --tests "com.devlink.server.TaskCompleteTransactionIntegrationTest"

# 완료 API 기본 테스트와 함께 실행
.\gradlew.bat test --offline --tests "com.devlink.server.TaskCompleteIntegrationTest" --tests "com.devlink.server.TaskCompleteTransactionIntegrationTest"

# 전체 회귀 테스트
.\gradlew.bat test --offline
```

같은 입력으로 반드시 재실행할 때에는 `--rerun-tasks`를 추가한다. 기본 실행의 `UP-TO-DATE` 표시는 이전 결과 재사용이다.

## 실행 결과

| 실행 | 테스트 수 | 실패 | 오류 | 건너뜀 | 결과 |
| --- | --- | --- | --- | --- | --- |
| 77번 트랜잭션·동시성 테스트 | 8 | 0 | 0 | 0 | BUILD SUCCESSFUL, 29초 |
| 기존 기능 포함 전체 회귀 테스트 | 547 | 0 | 0 | 0 | BUILD SUCCESSFUL, 2분 1초 |

77번 단독 실행의 XML에는 `tests=8`, `failures=0`, `errors=0`, `skipped=0`이 기록되었다. T05 로그에서도 `completion_commit_guard_value_key` 중복 제약으로 JDBC 커밋이 실패한 것을 확인했다. 파일·이력·Outbox 제약 위반은 T01~T03에서 SQLSTATE `23502`로 확인했다.

전체 실행 후 34개 테스트 클래스의 XML을 집계했다. 기존 539개와 새 테스트 8개를 합친 547개가 모두 통과했다. 이 결과에는 완료 API 기본 52개, 목록·상세 조회 81개, 작업 알림 통합 16개가 포함된다.

NOT NULL 제약 위반, `completion_commit_guard_value_key` 중복, `INJECTED_BEFORE_COMMIT_FAILURE` 등의 서버 오류 로그는 이 테스트에서 의도적으로 발생시킨 실패다. 검증 성공 여부는 HTTP 기대 응답과 롤백 후 스냅샷 비교, JUnit 실패·오류 수로 확인했다.

이번 작업에서는 테스트 파일과 검증 문서만 추가했다. 운영 코드 변경이나 Git 커밋·머지는 실행하지 않았다.

근거 파일:

- HTML: `build/reports/tests/test/index.html`
- 77번 XML: `build/test-results/test/TEST-com.devlink.server.TaskCompleteTransactionIntegrationTest.xml`
- 전체 XML: `build/test-results/test/TEST-*.xml`

## 검증 범위와 한계

- `TaskEvent`는 상세 조회용 이력이며, Outbox는 알림 발행용 기록이다. 두 종류 모두 완료 저장 트랜잭션의 롤백 대상으로 검증한다.
- 실제 WebSocket 전송·재시도는 기존 알림 테스트의 범위다. 이번 테스트에서는 알림 전송을 직접 실행하지 않는다.
- 시퀀스 번호는 롤백 후에도 건너뛸 수 있으므로 다음 ID의 연속성은 검증하지 않는다. 실제 남아 있는 데이터의 보존과 원자성을 검증한다.
- 제한된 두 요청의 경합을 결정적으로 재현하는 기능 테스트이며, 부하 테스트나 프로세스 강제 종료·네트워크 단절 검증을 대신하지 않는다.
- 운영 코드 문제가 발견되면 원인을 기록하고 수정 범위를 별도로 확인한다. Git 커밋·머지는 실행하지 않는다.
