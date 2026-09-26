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

### 비밀값 (API 키 등)

비밀값은 **저장소 밖 파일**에 두고 앱이 기동할 때 직접 읽는다. 터미널(`gradlew bootRun`)과 IntelliJ 실행 모두 같은 파일을 쓰므로 실행 설정에 키를 넣지 않는다. 인증키가 없어도 빌드와 테스트는 동작한다.

1. 홈 폴더에 `.stockinsight\secrets.yml`을 만든다 (Windows: `C:\Users\<사용자>\.stockinsight\secrets.yml`).
2. 저장소의 [secrets.example.yml](secrets.example.yml) 형식대로 값을 채운다.

```yaml
DART_API_KEY: 발급받은키
```

- 다른 위치의 파일을 쓰려면 환경 변수 `STOCKINSIGHT_SECRETS`에 경로를 지정한다.
- 같은 이름의 환경 변수(`DART_API_KEY` 등)가 있으면 파일보다 우선한다. 한 번만 다른 키로 실행해 볼 때 쓴다.
- 운영 서버는 `/etc/stockinsight/secrets.yml`(소유자만 읽기)을 쓰고, 파일이 없으면 기동하지 않는다.
- `secrets*.yml`, `.env*`는 `.gitignore`에 있고, CI는 gitleaks로 커밋 이력을 검사한다. 커밋 전에 로컬에서도 검사하려면 [gitleaks](https://github.com/gitleaks/gitleaks)를 설치해 `gitleaks git`을 실행한다. [.gitleaks.toml](.gitleaks.toml)은 기본 규칙에 Anthropic 키 규칙을 더하고, 확인된 오탐만 값 형식 단위로 허용한다(CI와 같은 gitleaks 8.24.3 기준, [코드 가이드 §14](docs/code-guide.md#14-테스트-구조)).
- 키가 노출되면 git 이력을 지우는 것으로는 부족하다. 즉시 재발급한다.

### OpenDART 수집

```bash
# 기동 직후 기업 목록 동기화를 한 번 실행한다. 결과는 pipeline_run 테이블에 남는다.
./gradlew bootRun --args='--app.ingest.company-sync.run-on-startup=true'
```

```bash
# 공시 목록 수집(초기 적재 포함)을 한 번 실행한다. 기업 목록 동기화가 먼저 끝나 있어야 한다.
./gradlew bootRun --args='--app.ingest.disclosure-sync.run-on-startup=true'
# 확인용으로 적재 기간을 줄이려면
./gradlew bootRun --args='--app.ingest.disclosure-sync.run-on-startup=true --app.ingest.disclosure-sync.initial-load-period=30d'
```

```bash
# 재무 수집(초기 적재 포함)을 한 번 실행한다. 기업 목록 동기화가 먼저 끝나 있어야 한다.
./gradlew bootRun --args='--app.ingest.financial-sync.run-on-startup=true'
# 확인용으로 초기 적재 대상 연도를 줄이려면 (0이면 올해만)
./gradlew bootRun --args='--app.ingest.financial-sync.run-on-startup=true --app.ingest.financial-sync.initial-load-years=0'
```

```bash
# 재무 신호 계산을 한 번 실행한다. 외부 호출 없이 DB에 있는 재무 데이터만 쓴다.
./gradlew bootRun --args='--app.signal.financial-signal.run-on-startup=true'
```

정기 실행은 `app.scheduler.enabled=true`(운영 프로필 기본값)일 때 돈다.

| 작업 | 시각 (KST) | 한 실행 호출 상한 |
|---|---|---|
| 기업 목록 동기화 (`company-sync`) | 매일 05:00 | `app.ingest.company-sync.max-calls-per-run` (10,000) |
| 공시 목록 수집 (`disclosure-sync`) | 매일 06:00 전체, 평일 08:00~19:30 30분마다 당일분 | `app.ingest.disclosure-sync.max-calls-per-run` (3,000) |
| 재무 수집 (`financial-sync`) | 매일 06:30 | `app.ingest.financial-sync.max-calls-per-run` (500) |
| 재무 신호 계산 (`financial-signal`) | 매일 07:00 | 없음(외부 호출이 없는 DB 계산) |

호출 상한에 닿으면 멈추고 남은 대상은 다음 실행이 이어서 처리한다. OpenDART 하루 한도는 인증키당 20,000건이다.

## 설계 문서

- [제품 정의](docs/product.md) — 제품의 목적, 대상 사용자, 핵심 개념, 원칙, 기능 판단 기준
- [시스템 설계](docs/architecture.md) — 아키텍처, 기술 선택, 데이터, 보안, 운영, MVP 범위, 확장 방향, 제외 기능
- [데이터·AI 분석 명세](docs/ai-analysis.md) — 기업 신호, 분석 종류(향후 전망 포함), 지문·재생성, 검증, 비용
- [설계 결정 기록](docs/decisions.md) — 주요 설계 결정과 이유
- [구현 계획](docs/implementation-plan.md) — 진행 현황과 다음 단계 작업 계획
- [코드 분석 가이드](docs/code-guide.md) — 현재 코드의 구조, 기동·실행 흐름, 설정·데이터 흐름, 실행 특성 (코드와 함께 갱신)
