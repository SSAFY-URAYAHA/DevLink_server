# 보안 전략

## 1. 현재 범위

인증 방식은 미정입니다. 비밀번호 인코더는 BCrypt이며 사용자 인증·토큰 발급 로직은 없습니다. Agent 토큰은 DB에 원문 대신 해시만 저장합니다.

## 2. HTTP 접근 정책

signup, login, API health와 Actuator health만 공개합니다. 나머지 요청은 인증이 필요합니다. formLogin과 httpBasic은 비활성화하며 인증 실패는 401, 접근 거부는 403 ApiResponse JSON으로 반환합니다. Spring Security의 기본 세션·CSRF 동작을 유지합니다.

## 3. WebSocket

`/ws`에 빈 raw WebSocket 핸들러만 등록합니다. Origin은 설정으로 제한합니다. Origin 검증은 인증을 대체하지 않습니다. 인증 연동 전에는 실제 연결·메시지 라우팅을 제공하지 않습니다.

## 4. 인증 방식 확정 후 결정할 사항

세션 선택 시 CSRF 전달 방식, 쿠키 속성, 세션 고정 보호와 저장소를 결정합니다. JWT 선택 시 STATELESS 정책, CSRF 적용 범위, 검증 필터 순서, 서명 키, 만료·갱신·폐기 정책을 결정합니다. 공통으로 Agent 토큰 인증, WebSocket 핸드셰이크, 인증 공급자와 접근 권한을 정의하고 SecurityConfig의 두 TODO를 구현합니다. 필요한 환경 변수와 V2 이후 테이블을 함께 추가합니다.
