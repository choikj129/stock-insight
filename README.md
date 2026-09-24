# stock-insight
일반 투자자를 위한 AI 기반 기업 분석 서비스

## 로컬 개발

필요한 것: Docker. JDK 21은 Gradle 툴체인이 자동으로 내려받는다.

```bash
docker compose up -d        # 로컬 PostgreSQL 17
./gradlew bootRun           # local 프로필로 실행 (http://localhost:8080/actuator/health)
./gradlew build             # 빌드 + 테스트 (Testcontainers가 PostgreSQL을 띄운다)
```

| 프로필 | 용도 | DB |
|---|---|---|
| `local` | 로컬 개발 (`bootRun` 기본값) | `compose.yaml`의 PostgreSQL |
| `prod` | 운영 | 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| (테스트) | `./gradlew test` | Testcontainers PostgreSQL |

DB 스키마는 Flyway(`src/main/resources/db/migration`)만 관리한다.

### OpenDART 수집

인증키는 환경 변수 `DART_API_KEY`로만 넣는다(운영은 SSM Parameter Store). 인증키가 없어도 빌드와 테스트는 동작한다.

```bash
# 기동 직후 기업 목록 동기화를 한 번 실행한다. 결과는 pipeline_run 테이블에 남는다.
DART_API_KEY=발급받은키 ./gradlew bootRun --args='--app.ingest.company-sync.run-on-startup=true'
```

정기 실행은 `app.scheduler.enabled=true`(운영 프로필 기본값)일 때 매일 05:00(KST)에 돈다. 한 번 실행에서 쓰는 호출 수는 `app.ingest.company-sync.max-calls-per-run`으로 제한하고, 남은 대상은 다음 실행이 이어서 처리한다.

## 설계 문서

- [제품 정의](docs/product.md) — 제품의 목적, 대상 사용자, 핵심 개념, 원칙, 기능 판단 기준
- [시스템 설계](docs/architecture.md) — 아키텍처, 기술 선택, 데이터, 보안, 운영, MVP 범위, 확장 방향, 제외 기능
- [데이터·AI 분석 명세](docs/ai-analysis.md) — 기업 신호, 분석 종류(향후 전망 포함), 지문·재생성, 검증, 비용
- [설계 결정 기록](docs/decisions.md) — 주요 설계 결정과 이유
