# 작업 조회 API 테스트 및 검증 기록

대상: S15P31D108-76의 작업 목록·상세 조회. 검증일: 2026-10-29 (Asia/Seoul).

## 검증 범위와 환경

- 목록: `GET /api/v1/teams/{teamId}/tasks`
- 상세: `GET /api/v1/tasks/{taskId}`
- 인증: `Authorization: Bearer {Agent 연결 토큰}`
- 테스트 파일: `src/test/java/com/devlink/server/TaskQueryIntegrationTest.java`
- 실제 Spring Boot 서버의 임의 포트에 Java HttpClient로 요청한다.
- `test` 프로필과 Testcontainers의 PostgreSQL 16을 사용한다. Docker 실행이 필요하며, 연결 정보는 `@ServiceConnection`으로 주입한다.
- 각 테스트마다 고유한 사용자·팀·Agent·작업을 생성한다. HTTP 요청이 별도 스레드에서 실행되므로 준비 데이터를 트랜잭션으로 먼저 커밋한다.
- 테스트 데이터의 시간은 고정한다. 테스트별 DB 생성 ID나 현재 시각에 의존하지 않는다.
- 운영 코드와 실제 서비스 DB는 수정하지 않는다. 토큰 폐기·팀 탈퇴 재현도 테스트 DB에서만 수행한다.

## 준비 데이터

같은 팀에 호출 Agent, 같은 사용자 소유의 다른 Agent, 팀원 Agent, 작업에 참여하지 않은 Agent를 생성한다. 별도 팀에는 다른 팀 사용자와 Agent를 생성한다.

기본 작업은 총 11건이다. 호출 Agent 관련 8건, 같은 사용자 소유의 다른 Agent 관련 2건, 다른 팀 작업 1건이다. 호출 Agent 자신에게 보낸 작업 1건을 포함한다. 상태는 `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `REJECTED`를 모두 준비한다.

파일·이벤트 검증에 필요한 추가 데이터는 해당 테스트 안에서만 생성한다. 파일은 `ADDED`, `MODIFIED`, `DELETED`, `RENAMED`와 null diff를 포함한다. 이벤트는 저장 순서와 발생 시각을 다르게 하고, 다른 작업·다른 팀 데이터도 함께 생성해 혼입 여부를 확인한다.

## 상세 조회 검증 항목

| 번호 | 테스트 메서드 | 검증 내용 | 기대 결과 |
| --- | --- | --- | --- |
| D01 | `returnsCompletedTaskDetail` | 작업·팀·Agent·prompt·version·결과·빈 배열 | 200, 저장된 값과 일치 |
| D02 | `returnsDetailFieldsForEveryStatus` | 상태 5종, 결과·실패·거절 사유와 시간 | 해당 상태의 필드만 값이 있고 나머지 사유는 null |
| D03 | `returnsFalseWhenFailedTaskHasNoPartialChanges` | 일부 수정 없이 실패 | failureReason 반환, partiallyModified=false |
| D04 | `allowsEveryAuthorizedAgentInSameTeamToReadDetail` | 요청·수신·같은 소유자·비참여 Agent | 같은 팀이면 모두 200 |
| D05 | `returnsDetailForSelfDirectedTask` | 요청·수신 Agent가 같은 작업 | 두 Agent 정보가 동일하게 반환 |
| D06 | `returnsOnlyRequestedTaskFilesWithEveryChangeType` | 변경 유형 4종, 파일 ID·경로·diff, 다른 작업의 파일 | 해당 작업 파일만 반환. 파일 순서는 강제하지 않음 |
| D07 | `returnsOnlyRequestedTaskEventsInChronologicalOrder` | 시간 정렬, content→message, null 설명, 다른 작업 이벤트 | 해당 작업 이벤트만 오래된 순으로 반환 |
| D08 | `rejectsDetailWithoutAgentToken` | 토큰 없는 요청 | 401 AUTHENTICATION_REQUIRED |
| D09 | `rejectsInvalidDetailAuthorizationHeader` | 알 수 없는 토큰·잘못된 인증 형식 4종 | 401 AUTHENTICATION_REQUIRED |
| D10 | `rejectsInvalidDetailTaskId` | 0·음수·문자열·Long 범위 초과 | 400 VALIDATION_FAILED |
| D11 | `returnsNotFoundForMissingDetailTask` | 존재하지 않는 양수 ID | 404 RESOURCE_NOT_FOUND |
| D12 | `rejectsDetailAccessAcrossTeams` | 서로 다른 팀의 작업 접근 | 양방향 모두 403 ACCESS_DENIED |
| D13 | `rejectsDetailWithRevokedToken` | 연결 토큰 폐기 | 401 AUTHENTICATION_REQUIRED |
| D14 | `rejectsDetailWhenAgentOwnerHasLeftTeam` | Agent 소유자가 팀에서 탈퇴 | 토큰 검증 단계에서 401 AUTHENTICATION_REQUIRED |
| D15 | `requiresDetailAgentTokenAndDoesNotReplaceUserSessionIdentity` | 실제 사용자 세션과 Agent 인증 분리 | 세션만으로는 401, 잘못된 토큰도 401, 유효한 토큰은 200. 사용자 세션 신원 유지 |
| D16 | `readingDetailDoesNotChangeTaskVersionOrCreateHistory` | 상세 반복 조회의 부작용 | 작업 데이터·version 유지, 파일·이력 추가 없음 |

상태별 기대값은 다음과 같다.

| 상태 | resultText | failureReason | rejectionReason | partiallyModified | startedAt | completedAt |
| --- | --- | --- | --- | --- | --- | --- |
| PENDING | null | null | null | false | null | null |
| IN_PROGRESS | null | null | null | false | 시작 시각 | null |
| COMPLETED | 완료 결과 | null | null | false | 시작 시각 | 종료 시각 |
| FAILED | null | 실패 사유 | null | 저장 값(true/false) | 시작 시각 | 종료 시각 |
| REJECTED | null | null | 거절 사유 | false | null | 종료 시각 |

빈 파일·이벤트 목록은 null 대신 `[]`로 확인한다. 성공 응답은 `success=true`, `code/message=null`을 확인한다. 오류 응답은 HTTP 상태와 함께 `success=false`, `data=null`, 오류 코드와 비어 있지 않은 메시지를 확인한다.

## 기존 목록 조회 회귀 검증

기존 52개 테스트를 유지한다. 기본 조회 조건, Agent 단위 분리, 팀 전체 조회, 상태·방향 필터, 페이지와 전체 개수, 생성 시각 내림차순·동일 시각 ID 내림차순, Agent·시간 정보, 빈 결과, 잘못된 입력, 다른 팀 접근, 토큰 폐기·팀 탈퇴, 세션 인증 분리를 검증한다.

목록·상세 테스트는 동일한 준비 데이터를 재사용한다. 로그인과 공통 응답 검사도 공통 메서드를 사용한다.

## 실행 방법

프로젝트 루트에서 Docker를 실행한 상태로 다음 명령을 실행한다. `--offline`은 Gradle 의존성이 이미 캐시에 있을 때 사용한다. 최초 의존성 다운로드가 필요하면 이 옵션을 제외한다.

```powershell
# 작업 조회 통합 테스트 전체
.\gradlew.bat test --offline --tests "com.devlink.server.TaskQueryIntegrationTest"

# 완료 작업 상세 조회 한 건만 확인
.\gradlew.bat test --offline --tests "com.devlink.server.TaskQueryIntegrationTest.returnsCompletedTaskDetail"

# 기존 기능을 포함한 전체 테스트
.\gradlew.bat test --offline
```

동일한 입력으로 재검증이 필요하면 명령에 `--rerun-tasks`를 추가한다. 기본 실행의 `UP-TO-DATE`는 새 실행이 아닌 이전 결과 재사용이다.

## 실행 결과

상세 조회 테스트 29개를 추가했다. 위 D01~D16의 16개 테스트 메서드에 매개변수별 실행 사례를 포함한 개수다. 기존 목록 테스트 52개와 합쳐 작업 조회 통합 테스트는 81개다.

| 실행 | 테스트 수 | 실패 | 오류 | 건너뜀 | 결과 |
| --- | --- | --- | --- | --- | --- |
| 작업 조회 통합 테스트 | 81 | 0 | 0 | 0 | BUILD SUCCESSFUL, 46초 |
| 기존 기능 포함 전체 테스트 | 443 | 0 | 0 | 0 | BUILD SUCCESSFUL, 2분 28초 |

작업 조회 단독 실행의 XML에는 `tests=81`, `failures=0`, `errors=0`, `skipped=0`이 기록되었다. 전체 실행 후 30개 테스트 클래스의 XML을 집계하여 443개 모두 통과한 것을 확인했다. 전체 결과에도 작업 조회 81개가 포함되어 있으며, 상세 29개는 이 수에 포함된 부분집합이다.

작성·수정한 파일은 이 문서와 `TaskQueryIntegrationTest.java`다. 사용자가 작성한 상세 조회 Service·Controller 변경을 포함한 현재 작업 폴더를 대상으로 검증했으며, 이번 테스트 작업에서 운영 코드를 추가 수정하지 않았다. Git 커밋·머지는 실행하지 않았다.

결과 근거:

- HTML 보고서: `build/reports/tests/test/index.html`
- 작업 조회 XML: `build/test-results/test/TEST-com.devlink.server.TaskQueryIntegrationTest.xml`
- 전체 XML: `build/test-results/test/TEST-*.xml`

전체 테스트를 실행하면 Gradle 보고서는 해당 전체 실행 결과로 갱신된다. 위 build 경로는 생성 결과이며 커밋 대상에 포함하지 않는다.

## 검증의 한계와 명세 확인

- 파일·이벤트 등록 자체와 작업 완료 등록 API는 이번 검증 범위에 포함하지 않는다. 조회 검증용 데이터는 Repository로 저장한다.
- 이벤트는 현재 코드의 `TaskEventType` 값을 그대로 응답하는 계약으로 검증한다. 예를 들어 `ACK_RECEIVED`를 사용한다. 과거 ZIP 명세의 `DELIVERED`·`DELIVERY_FAILED`와는 명칭이 다르므로 최종 팀 명세와 대조가 필요하다. 이번 작업에서 이벤트 enum이나 응답 명칭을 임의로 변경하지 않는다.
- 이벤트의 발생 시각이 같을 때의 ID 정렬은 현재 명세에 별도로 정의되어 있지 않아 검증하지 않는다. 파일 배열에도 특정 정렬 순서를 가정하지 않는다.
- 배포 환경·부하·동시 갱신 상황의 응답 일관성은 이번 기능 테스트 범위에 포함하지 않는다.
