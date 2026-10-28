# 작업 생성 구현 현황

## 실행 흐름

1. Controller에서 입력을 검증하고 세션 userId를 받아 서비스 호출. 로그인·요청 Agent 소유권·팀 권한은 후속 인증 연동 범위.
2. TaskCreationService에서 작업(PENDING)과 TaskEvent(CREATED)를 같은 트랜잭션으로 저장·커밋.
3. 신규 요청만 TaskRoutingService.sendTask(taskId) 호출. TaskDeliveryGateway는 제거.
4. 전송 전 DELIVERING 이력(content=DELIVERING)을 별도 트랜잭션으로 커밋하고, 전송 후 결과 이력을 저장.
5. 작업 ID·현재 작업 상태·version·replayed·delivery 반환.

## 응답 전달 상태

별도 DeliveryStatus enum 없이 TaskEventType을 사용하여 응답 상태를 DELIVERED / DELIVERY_FAILED 두 가지로 제공한다. 라우팅 결과가 SENT이면 DELIVERED와 reasonCode=null을 반환한다. 나머지는 DELIVERY_FAILED와 원래 라우팅 상태 이름(RECEIVER_OFFLINE, CONNECTION_UNAVAILABLE, CONNECTION_REPLACED, TRANSPORT_ERROR, TASK_NOT_PENDING)을 reasonCode로 반환한다. 재요청 응답도 같은 규칙을 적용한다.

SENT는 소켓 쓰기 성공이다. 이번 변경부터 API에서는 이를 전달 완료 기준으로 사용한다. ACK를 기다리지 않으며 SENT 시 deliveredAt 갱신과 DELIVERED 이력(content=SENT)을 같은 트랜잭션으로 저장한다. 반환 객체는 커밋 후 증가한 version을 포함한다.

## 이력과 재조회

- 전송 전 DELIVERING 이력(content=DELIVERING)을 남긴다.
- SENT이면 DELIVERED 이력을 저장한다.
- 나머지 라우팅 상태는 DELIVERY_FAILED 이력을 저장하고 content에 원래 상태명을 기록한다. TRANSPORT_ERROR는 상대가 실제 수신했을 수도 있으므로 자동 재전송하지 않는다.
- 기존 요청은 재전송 없이 최신 DELIVERED/DELIVERY_FAILED 이력에서 상태를 복원한다.
- 최신 이력이 DELIVERING이거나 아직 결과가 없으면 delivery=null이다.
- 결과 이력 저장 실패는 예외로 전파된다. deliveredAt 갱신은 결과 이력과 함께 롤백되고 앞서 커밋된 작업·CREATED·DELIVERING은 유지된다.
- ROUTING_RESULT는 제거했다. 기존 데이터가 없으므로 데이터 변환 마이그레이션은 추가하지 않는다.
TaskDeliveryResultService는 제거했으며 조회와 저장 모두 TaskCreationService에서 TransactionTemplate으로 처리한다. create 전체는 NOT_SUPPORTED이고 DB 작업 구간만 REQUIRES_NEW로 실행한다. 생성 커밋 전에는 라우팅을 호출하지 않는다.

## 중복 생성과 검증

같은 사용자·requestKey·내용이면 기존 작업을 반환한다. 같은 키에 다른 내용은 409다. 동시 INSERT는 DB 유니크 제약으로 한 건만 저장하고 패배 요청은 롤백 후 409를 반환한다. 복구용 try-catch는 없다.

Controller 테스트, PostgreSQL의 생성·이력 롤백 및 동시 INSERT 테스트, 실제 WebSocket 생성→전송→중복 무재전송 통합 테스트로 검증한다. 라우팅 시점에는 CREATED와 DELIVERING이 커밋돼 있고 전송 전에 DELIVERING, 반환 이후 DELIVERED 또는 DELIVERY_FAILED가 추가돼야 한다.
