# 작업 생성 구현 현황

## 현재 흐름

`POST /api/v1/tasks/{teamId}`의 Controller에서 DTO·경로 입력을 검증하고 생성 서비스를 호출한다. 로그인 구현과 요청 Agent 소유권·사용자의 팀 권한 검증은 후속 연동 범위다. Controller의 세션 `userId`(Long)는 로그인 담당자와 맞출 계약이며 기존 Security/CSRF 설정은 유지한다.

`TaskCreationService.create()`는 다음 세 단계로 구성한다.

1. `transaction.execute(...)`에서 작업과 CREATED 이력을 함께 저장하고 커밋한다.
2. 브로커 전달 호출은 주석 TODO로 두고 `TaskDeliveryResult.unknown()`을 사용한다. 신규 요청의 결과는 TaskCreationService.saveDeliveryEvent에서 직접 처리한다. DELIVERED이면 Task.deliveredAt 갱신과 DELIVERED 이력을 같은 트랜잭션으로 저장하고, FAILED이면 DELIVERY_FAILED 이력과 실패 사유를 저장한다. UNKNOWN은 이력을 저장하지 않는다.
3. 작업 ID·현재 상태·version·replayed·전달 결과를 반환한다.

서비스의 try-catch·재조회·재시도 복구 로직은 없다. 저장 중 런타임 예외는 그대로 전파되고 작업과 생성 이력은 함께 롤백된다. TaskDeliveryResultService는 제거했다. 전달 결과 저장과 최신 전달 이력 조회는 TaskCreationService 내부에서 TransactionTemplate으로 처리한다. 성공 시 flush 후 최신 version을 가진 Task를 응답에 사용한다. 전달 이력 저장 실패는 예외로 전파되므로 API는 실패 응답이 될 수 있지만, 이미 커밋된 작업·CREATED 이력은 유지된다. TransactionTemplate의 REQUIRES_NEW와 외부 메서드의 NOT_SUPPORTED는 전달을 생성 커밋 이후에 연결하도록 유지했다. private createTask는 이미 시작된 트랜잭션 안에서 호출되므로 별도의 프록시가 필요 없다.

## 중복 요청

- 기존 요청 키와 내용이 같으면 기존 작업을 반환한다(200, replayed=true). 생성 이력을 추가하지 않는다.
- 동일 키에 내용이 다르면 if 검사 후 BusinessException(REQUEST_KEY_CONFLICT)을 던진다.
- 두 요청이 동시에 기존 작업을 찾지 못하고 INSERT하면 DB 유니크 제약으로 한 건만 저장한다. 패배한 요청은 롤백되고 예외를 그대로 전파한다.
- GlobalExceptionHandler는 uk_tasks_request_key 제약 위반만 409 REQUEST_KEY_CONFLICT로 매핑한다. 다른 DB 오류는 500으로 처리한다.
- 동시 충돌 시 기존 결과를 자동으로 찾아 반환하는 복구는 하지 않는다. 클라이언트가 같은 키로 다시 요청하면 기존 작업을 조회할 수 있다.

## 아직 연결하지 않은 기능

- 생성 전 수신 브로커 온라인 확인
- 생성 커밋 후 신규 작업의 실제 전달과 ACK 확인
- deliveredAt 및 전달 이력 저장 구현 완료. 실제 전송 결과 연결은 TODO.
- 재조회 시 가장 최근 DELIVERED/DELIVERY_FAILED 이력을 조회해 전달 결과와 실패 사유 반환 (구현 완료)

현재는 수신 Agent가 존재하고 같은 팀이면 온라인 여부와 무관하게 저장한다. 따라서 오프라인 생성 거부 요구사항은 아직 충족하지 않으며 6번 담당자의 전달 모듈 연동 때 추가해야 한다. TaskDeliveryGateway는 후속 연동용 인터페이스로 남겨두고 현재 서비스에서는 사용하지 않는다.

DeliveryStatus는 domain/task 폴더의 DELIVERED / FAILED / UNKNOWN enum이며 TaskStatus와 별개다. 신규 요청은 전송 TODO로 UNKNOWN을 반환한다. 재조회는 가장 최근 전달 이력을 반환하며 이력이 없을 때만 UNKNOWN을 반환한다.

## 검증

- Controller: 입력 오류, 신규 201·재조회 200, 세션 값 누락 401, 중복 키 409, 다른 DB 오류 500.
- PostgreSQL 통합: 생성·이력 동시 저장, 이력 실패 시 작업 롤백, 순차 재요청, 다른 payload 충돌, 동시 INSERT 한 건 커밋·한 건 예외 전파.
- 기존 Task 도메인 테스트.

설계 문서의 온라인 확인·전달 복구 등은 향후 확장안이며 현재 구현 동작은 이 문서를 기준으로 한다.
