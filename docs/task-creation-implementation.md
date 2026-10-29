# 작업 생성 구현 현황

## 실행 흐름

1. Controller에서 입력과 세션 userId를 확인한다. 로그인·요청 Agent 소유권·팀 권한은 후속 인증 연동 범위다.
2. TaskCreationService.create에서 Task(PENDING)와 CREATED를 같은 트랜잭션으로 저장·커밋한다.
3. 같은 requestKey 재요청은 최신 전달 이력을 조회하며 재전송하지 않는다.
4. 신규 작업은 deliverTask에서 TaskRoutingService.sendTask를 트랜잭션 밖에서 호출한다.
5. TaskRoutingResult를 saveDeliveryEvent에 그대로 전달하고, Task 행 잠금 후 결과 이력을 저장한다.
6. findLatestDelivery로 조회한 전달 결과를 응답한다. HTTP 요청은 소켓 쓰기까지 기다리지만 ACK는 기다리지 않는다.

## 전달 이력

- 전송 전 전달 이력은 기록하지 않는다.
- SENT → ACK_PENDING, content=null. deliveredAt은 변경하지 않는다.
- RECEIVER_OFFLINE / CONNECTION_UNAVAILABLE / CONNECTION_REPLACED / TASK_NOT_PENDING → DELIVERY_NOT_SENT. content는 원래 결과 이름이다.
- TRANSPORT_ERROR → DELIVERY_UNKNOWN / TRANSPORT_ERROR. 자동 재전송하지 않는다.
- 기존 ACK_RECEIVED가 있으면 전송 결과를 추가하지 않는다. 타임아웃·연결 종료 이력이 먼저 있으면 SENT에 대한 ACK_PENDING도 추가하지 않는다.
- 전달 이력이 아직 없으면 delivery=null이다. 빠른 ACK가 먼저 커밋되면 ACK_RECEIVED를 반환한다.

## ACK와 트랜잭션

TaskAckService는 기존 검증과 소켓 쓰기 직전 타이머 등록을 유지한다. 결과 저장은 REQUIRES_NEW 트랜잭션에서 생성 서비스와 동일한 Task 행 잠금을 사용한다.

- ACKNOWLEDGED: deliveredAt과 ACK_RECEIVED를 함께 커밋한다. Task.status는 바꾸지 않는다.
- TIMED_OUT: DELIVERY_UNKNOWN / ACK_TIMEOUT.
- CONNECTION_CLOSED: DELIVERY_UNKNOWN / CONNECTION_CLOSED.
- SEND_FAILED: 생성 서비스가 TRANSPORT_ERROR를 기록하므로 ACK 서비스에서는 저장하지 않는다.

create의 NOT_SUPPORTED와 생성 서비스의 REQUIRES_NEW 설정은 유지한다. 네트워크 호출은 트랜잭션 밖에서 실행한다.

## 실패 처리와 한계

- Task + CREATED 저장 실패는 생성 실패로 전파한다.
- 생성 이후 전달 이력 저장·커밋 실패는 작업 ID와 생성 성공을 유지하고 DELIVERY_UNKNOWN / HISTORY_RECORD_FAILED를 응답한다.
- 예상 밖 전송 예외는 ROUTING_ERROR, 이력 조회 실패는 HISTORY_READ_FAILED를 응답하고 로그를 남긴다. 이 응답 사유가 DB에 저장됐다는 의미는 아니다.
- ACK 저장 실패는 시간과 이력을 함께 롤백하고 로그를 남긴다. ACK 관측 결과 Future와 이벤트는 완료하며, 자동 DB 저장 재시도는 없다.
- 전송 후 이력 커밋 전 서버가 중단되면 CREATED만 남을 수 있다.
- Task와 최신 전달 이력은 별도 조회이므로 빠른 ACK 경합에서 응답 version이 delivery보다 이전 시점일 수 있다.

## 검증

단위 테스트는 ACK 검증·전송 경로·응답과 저장 실패 이후 Future 완료를 검증한다. 통합 테스트는 전송 전 CREATED 커밋, 결과 매핑, 빠른 ACK/타임아웃 선행, 생성 보존, 커밋 실패, ACK 저장 롤백과 실제 WebSocket 수신을 검증한다. 통합 테스트 실행에는 Docker가 필요하다.
