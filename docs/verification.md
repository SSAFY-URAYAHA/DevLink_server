# 실행 검증 기록

검증일: 2026-10-26 (Asia/Seoul). Windows, Temurin Java 21, Docker Desktop Linux 엔진에서 수행했습니다.

| 항목 | 명령 / 확인 방법 | 결과 |
| --- | --- | --- |
| 테스트 포함 빌드 | `./gradlew.bat build` | BUILD SUCCESSFUL, 테스트 1개, 실패·오류·건너뜀 0개 |
| PostgreSQL 기동 | `docker compose up -d postgres` | healthy |
| 로컬 서버 | `./gradlew.bat bootRun --args='--spring.profiles.active=local'` | 8080 기동, Flyway가 빈 스키마에 V1 적용 (이후 ERD 미확정으로 V1 삭제, 현재는 마이그레이션 없이 기동) |
| 마이그레이션 이력 | `SELECT version, description, success FROM flyway_schema_history` | 1 / init schema / true |
| API health | `GET /api/v1/health` | 200, success=true, data=UP |
| 미인증 API | `GET /api/v1/teams` | 401 application/json, AUTHENTICATION_REQUIRED, Location 없음 |
| 기본 CSRF | 통합 테스트에서 토큰 없는 login POST | 403 JSON, ACCESS_DENIED |
| Compose 전체 기동 | `docker compose up --build -d --wait --wait-timeout 120` | 앱과 PostgreSQL 모두 healthy, 종료 코드 0 |
| 비루트 실행 | `docker compose exec -T app id` | uid=999(devlink), gid=999(devlink) |
| 컨테이너 HTTP | health와 teams GET 재검증 | 각각 200과 401 JSON |

도메인 엔티티가 없는 상태에서 `ddl-auto=validate`를 유지하고 컨텍스트가 정상 기동함을 통합 테스트와 로컬 실행으로 확인했습니다. Docker가 없는 환경의 실패 메시지는 테스트 초기화 코드에 명시되어 있으며, Docker를 끈 상태의 별도 실행은 하지 않았습니다.

검증 종료 시 로컬 bootRun은 종료했으며 Compose 앱(8080)과 PostgreSQL(5432)은 실행 중입니다. `docker compose down`으로 종료할 수 있습니다. Linux 실행 권한 보존을 위해 gradlew만 Git 인덱스에 100755로 등록했으며 커밋은 만들지 않았습니다.
