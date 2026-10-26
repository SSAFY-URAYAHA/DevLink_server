# DevLink 중앙 서버

DevLink 중앙 서버 단독 저장소입니다. Java 21 · Spring Boot 3.5.16 · Gradle(Groovy DSL) · PostgreSQL 16 · Flyway 단일 모듈이며, 인증·팀·Agent / 작업 생성·상태 / 작업 결과·조회 / 실시간 연결·라우팅 담당자가 기능을 붙여 나갈 공통 기반입니다.

> **현재 미확정**: ERD, 인증 방식(세션/JWT). 확정 전까지 마이그레이션·엔티티·인증 필터를 추가하지 않습니다. 비즈니스 로직은 아직 없습니다.

## 1. 필요 환경

- Java 21
- 실행 중인 Docker Desktop 또는 Docker Engine (로컬 DB와 테스트 모두 사용)

## 2-1. 로컬 실행 방법 - PC 실행
#### 1) env 파일 생성
```sh
cp .env.example .env
```
#### 2) postgres 컨테이너 실행
```bash
docker compose up -d postgres
```
#### 3) PC 직접 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 2-2. 로컬 실행 방법 - Docker 실행
#### 1) env 파일 생성
```sh
cp .env.example .env
```

#### 2) 컨테이너 한번에 실행
```bash
docker compose up -d --build
```

## 2-3. 헬스체크 요청

```sh
curl -i http://localhost:8080/api/v1/health   # 200, {"success":true,"data":"UP",...}
curl -i http://localhost:8080/api/v1/teams    # 401 JSON (리다이렉트 없음)
```

## 2-4. 테스트 · 빌드

```sh
./gradlew test
./gradlew build
```

- 테스트는 Testcontainers로 PostgreSQL 16 컨테이너를 직접 띄웁니다. Docker가 없으면 명확한 오류로 실패합니다.
- 새 테스트는 `AbstractIntegrationTest`를 상속합니다.
- 결과물: `build/libs/app.jar`

## 3. 환경 변수

| 변수 | local 기본값 | 용도 |
| --- | --- | --- |
| DB_URL | jdbc:postgresql://localhost:5432/devlink | JDBC URL (앱 컨테이너는 postgres 호스트) |
| DB_USERNAME | devlink | PostgreSQL 사용자 |
| DB_PASSWORD | devlink | PostgreSQL 비밀번호 |
| WS_ALLOWED_ORIGINS | http://localhost:3000 | WebSocket 허용 Origin (쉼표 구분) |

prod 프로필은 기본값 없이 작업 디렉터리의 `.env` 또는 환경 변수에서 값을 받습니다: `java -jar build/libs/app.jar --spring.profiles.active=prod`. `.env`는 커밋하지 않습니다.

## 4. 패키지 구조

루트 패키지 `com.devlink.server`, 계층별 패키지이며 도메인별 하위 패키지는 만들지 않습니다.

| 패키지 | 역할 |
| --- | --- |
| config | Security, WebSocket, JPA Auditing 설정 |
| common | 공통 응답, 오류 코드, 비즈니스 예외 |
| controller | REST API 진입점 |
| domain | JPA 엔티티, 상태 enum, BaseTimeEntity |
| dto | 요청·응답 DTO |
| filter | 인증·Agent 토큰 서블릿 필터 (추후) |
| handler | 전역 예외, 인증 실패·권한 거부, WebSocket 처리 |
| repository | Spring Data JPA 리포지토리 |
| service | 비즈니스 로직과 트랜잭션 |

클래스 이름은 기능명 접두사를 씁니다: `AuthController`, `TeamService`, `AgentRepository`, `TaskCreateRequest`.

## 5. 마이그레이션 규칙

- 경로 `src/main/resources/db/migration`, 이름 `V{정수 순번}__{설명}.sql`. 날짜 버전은 쓰지 않습니다.
- 순번이 충돌하면 리베이스 후 아직 적용하지 않은 파일의 번호를 다시 매깁니다.
- **공유 DB(dev 서버 등)에 적용된 마이그레이션은 수정 금지**. 로컬에만 적용된 것은 `docker compose down -v`로 초기화 후 다시 작성할 수 있습니다.
- 모든 프로필에서 `ddl-auto=validate`, Flyway `validate-on-migrate=true`, `baseline-on-migrate=false`. prod는 `clean-disabled=true`.
- 엔티티는 마이그레이션 스키마의 타입·길이·널 허용과 일치시킵니다. `updated_at`은 BaseTimeEntity Auditing으로 채웁니다.

## 6. 확정 후 수정할 지점

**ERD 확정 시**
- `V1__init_schema.sql` 추가 (TIMESTAMPTZ + Instant, 모든 FK 인덱스, Agent 토큰 해시만 저장, 작업 상태 CHECK, 멱등 키 UNIQUE)
- `domain/`에 엔티티 추가
- `DevLinkServerIntegrationTest`의 "마이그레이션·테이블 없음" 단정을 V1 적용·테이블 존재 검증으로 교체

**인증 방식 확정 시**
- `SecurityConfig` TODO 두 곳 (세션/CSRF 정책, 인증 필터 연결)
- `filter/`에 인증 필터·Agent 토큰 필터 추가
- `WebSocketConfig` 핸드셰이크 인증, STOMP 여부
- `.env.example`에 쿠키 또는 JWT 변수 추가
- 세션 또는 리프레시 토큰 테이블 마이그레이션
- [보안 전략 문서 4절](docs/security-strategy.md#4-인증-방식-확정-후-결정할-사항) 갱신

## 7. 참고 문서

- [검증 기록](docs/verification.md): 직접 실행해 확인한 결과
- [보안 전략](docs/security-strategy.md)
