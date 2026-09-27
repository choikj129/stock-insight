# 코드 분석 가이드

| 항목 | 내용 |
|---|---|
| 상태 | 현재 코드 기준 (코드와 함께 갱신하는 기준 문서) |
| 기준 시점 | 2026-09-26, `main` 작업 트리 (커밋되지 않은 변경 포함, D-53 반영) |
| 관련 문서 | [제품 정의](../product.md), [시스템 설계](../architecture.md), [데이터·AI 분석 명세](../README.md), [설계 결정 기록](../decisions/README.md), [진행 현황](../status.md) |

이 문서는 **지금 있는 코드가 어떻게 구성되어 있고 실제로 어떤 순서로 실행되는지**를 설명한다. 무엇을 왜 만드는지는 product.md, 목표 설계는 architecture.md·spec/, 진행 현황은 status.md가 기준이다. 이 문서는 설계가 아니라 **구현 현황의 기준**이다. 설계 문서에 있어도 코드에 없는 것은 여기에 "없음"으로 적는다.

표기 규칙
- `클래스.메서드()` 뒤의 링크는 해당 코드 위치다.
- **[미확인]**: 코드나 설정으로 직접 확인하지 못했고, 프레임워크 기본 동작이나 추정에 기대는 내용이다.
- **[설계만]**: 설계 문서에는 있지만 코드에는 아직 없는 것이다.

> **유지 규칙.** 패키지·클래스 추가/삭제, 실행 경로(스케줄·HTTP·기동 동작) 변경, 설정 키 추가·변경, 테이블·데이터 흐름 변경, 트랜잭션·예외·재시도·동시성 규칙 변경이 생기면 같은 작업 안에서 이 문서의 해당 절을 고친다. 변경 시 확인할 절은 §16의 체크리스트를 따른다.

이 가이드는 세 파일이다(2026-09-27, 옛 `code-guide.md`). 절 번호는 옛 번호를 그대로 쓴다.

| 파일 | 절 | 내용 |
|---|---|---|
| README.md (이 파일) | §1~§3, §12~§16 | 한눈에 보기, 저장소 구조, 패키지 구조·의존 방향, 설계 의도, 코드를 읽는 순서, 테스트 구조·CI·gitleaks, 확인하지 못한 것과 알려진 한계, 변경 시 체크리스트 |
| [classes.md](classes.md) | §4 | 클래스별 책임: `common`, `ingest`, `company`, `disclosure`, `financial`, `signal`, `analysis`, `analysis.llm`, 주요 관계 |
| [runtime.md](runtime.md) | §5~§11 | 기동과 Bean 구성, 실행 경로, 실행 흐름 추적, 설정값과 사용 위치, 데이터 흐름과 타입 변환, DB 테이블, 트랜잭션·예외·재시도·동시성·시간 |

---

## 1. 한눈에 보기

현재 애플리케이션은 **화면이 없는 배치·AI 파이프라인**이다. Spring Boot 프로세스 하나가 OpenDART에서 데이터를 받아 PostgreSQL에 저장하고, 저장된 재무 데이터로 "기업 신호"를 판정해 저장하고, 그 신호와 사실표로 Anthropic Claude를 불러 "재무 쉬운 설명" 초안을 만든다.

```
                  OpenDART (HTTPS, 인증키는 쿼리 파라미터)
                        ▲
                        │ DartApi (인터페이스) ← DartClient (구현, 호출 간격·재시도)
                        │
 ┌──────────────────────┴───────────────────────────────────────────┐
 │ ingest: 수집 Job                                                    │
 │  05:00 CompanySyncJob ─────▶ CompanyService ─────▶ company, security, company_alias
 │  06:00 DisclosureSyncJob ──▶ DisclosureService ──▶ disclosure
 │        (평일 08~19시 30분마다 당일분)                   │ 정기공시 = 재무 수집 계기
 │  06:30 FinancialSyncJob ───▶ FinancialService ───▶ financial_report, financial_line
 └───────────────────────────────────────────────────────────────────┘
 ┌───────────────────────────────────────────────────────────────────┐
 │ signal: 판정 Job (외부 호출 없음)                                     │
 │  07:00 FinancialSignalJob ─▶ FinancialSummaryService (읽을 때 해석)
 │                           ─▶ FinancialSignalCalculator (순수 함수)
 │                           ─▶ CompanySignalService ──▶ company_signal
 └───────────────────────────────────────────────────────────────────┘
 ┌───────────────────────────────────────────────────────────────────┐
 │ analysis: AI 분석 (financial_explain만)                              │
 │  07:30 FinancialExplainJob ▶ FinancialExplainInputBuilder (사실표 구성)
 │                            ▶ LlmClient(AnthropicLlmClient) ──HTTPS──▶ Anthropic
 │                            ▶ FinancialExplainValidator (검증, 실패 시 1회 재생성)
 │                            ▶ AnalysisService ──▶ analysis (기본은 초안만, 게시는 설정으로 켬)
 └───────────────────────────────────────────────────────────────────┘
 공통: PipelineRunRecorder ─▶ pipeline_run   /   IngestCheckpointRepository ─▶ ingest_checkpoint
```

| 있는 것 | 없는 것 [설계만] |
|---|---|
| 기업 목록 동기화, 공시 목록 수집, 재무(주요계정) 수집, 재무 신호 계산 | 공시 신호·이벤트(`corporate_event`), 동종업계 비교 |
| AI 분석: 재무 쉬운 설명(`financial_explain`) 생성·검증·초안 저장(§4.9) | 다른 분석 종류(기업 이해·일일 변동·향후 전망·경쟁사 비교), 관리자 초안 미리보기 화면 |
| 실행 기록(`pipeline_run`), 수집 체크포인트(`ingest_checkpoint`) | 웹 화면(`web`), JSON API, 관리자(`admin`) |
| Actuator `/actuator/health` | 정적 콘텐츠(`content`), `company.ai_covered` 채우는 코드(D-23) |
| Flyway 마이그레이션 V1~V5 | 시세(`market`), 뉴스(`news`), 사업보고서 원문·S3 |

**AI 분석은 기본적으로 게시되지 않는다.** `app.analysis.financial-explain.publish=false`(기본값)이면 검증을 통과해도 `analysis` 테이블에 DRAFT로만 쌓이고, 화면에 나갈 게시본(`is_current`)이 되지 않는다(§4.9, D-39). 실제 인증키로 골든셋을 생성하고(work/3-4-verification-2.md §7.4.34) 사람 검토를 마쳤다(§7.4.35). 게시를 켤지는 아직 정하지 않았다.

---

## 2. 저장소 구조

```
stockInsight/
├── build.gradle            Spring Boot 4.1.1, Java 21 툴체인, bootRun 기본 프로필 local
├── settings.gradle
├── compose.yaml            로컬 PostgreSQL 17 (127.0.0.1:5432, DB·계정 stockinsight, TZ=UTC)
├── secrets.example.yml     비밀값 파일 형식 예시 (실제 파일은 저장소 밖)
├── .github/workflows/ci.yml  gitleaks 비밀값 검사 + ./gradlew build (Testcontainers)
├── .gitleaks.toml          gitleaks 기본 규칙 + Anthropic 키 규칙 + 확인된 오탐(재무 사실 키) 허용
├── docs/                   설계·진행·결정·코드 가이드 (입구는 docs/README.md, 분리 정책은 그 §3)
└── src/
    ├── main/java/org/stockinsight/   애플리케이션 코드 (§3)
    ├── main/resources/
    │   ├── application.yml           공통 설정 (§8)
    │   ├── application-local.yml     로컬: compose DB, 비밀값 파일(optional)
    │   ├── application-prod.yml      운영: 환경 변수 DB, 비밀값 파일(필수), 스케줄러 켜짐
    │   ├── db/migration/V1~V7        Flyway 스키마 (§10)
    │   └── prompts/                  AI 시스템 프롬프트: common/style.md, financial_explain/v1.md·schema.json (§4.9)
    ├── test/java/org/stockinsight/   테스트 (§14)
    ├── test/resources/dart/          실제 OpenDART 응답 샘플 (출처는 그 폴더의 README.md)
    └── test/resources/golden/financial_explain/   골든셋 입력 JSON 고정본(`GoldenSetDumpRunner`가 만듦, §4.9·§6)
```

Gradle 모듈은 하나다(멀티모듈 없음, D-01). 기준 패키지는 `org.stockinsight`다. 설계 문서 일부에 남은 `org.stockinsight.stockinsight`는 옛 이름이다(status.md §2 3-0).

의존성(build.gradle): `spring-boot-starter-webmvc`, `-actuator`, `-data-jpa`, `-flyway`, `flyway-database-postgresql`, `postgresql`(runtime), `com.anthropic:anthropic-java:2.34.0`(Claude 호출, 내부적으로 고전 Jackson 2도 함께 들어온다). 테스트는 `-webmvc-test`, `-data-jpa-test`, `spring-boot-testcontainers`, Testcontainers PostgreSQL. HTTP 클라이언트(`RestClient`)와 Jackson 3(`tools.jackson`)은 webmvc 스타터로 들어온다.

두 Jackson이 공존한다: 도메인·저장 코드는 Jackson 3(`tools.jackson.databind.json.JsonMapper`)를 쓰고, `AnthropicLlmClient`만 Anthropic SDK가 요구하는 고전 Jackson 2(`com.fasterxml.jackson.databind`)로 출력 스키마를 파싱한다(§4.9). 둘을 섞지 않는다.

---

## 3. 패키지 구조와 의존 방향

```
org.stockinsight
├── StockInsightApplication       진입점
├── common/
│   ├── config/                   TimeConfig(Clock, 서비스 시간대), SchedulingConfig(@EnableScheduling 조건부)
│   └── pipeline/                 PipelineRunRecorder (pipeline_run)
├── ingest/                       ── 외부에서 가져오는 층
│   ├── dart/                     OpenDART 클라이언트·응답 DTO·상태 코드
│   ├── checkpoint/               ingest_checkpoint 읽기·쓰기
│   ├── company/                  기업 목록 동기화 Job·Scheduler·Properties
│   ├── disclosure/               공시 목록 수집 Job·Scheduler·Properties
│   └── financial/                재무 수집 Job·Scheduler·Properties
├── company/                      ── 도메인: 기업·종목 마스터 (JPA)
├── disclosure/                   ── 도메인: 공시 (JdbcClient)
├── financial/                    ── 도메인: 재무 저장 + 읽을 때 해석 (JdbcClient)
├── signal/                       ── 판정: 재무 신호 계산·저장 + 계산 Job
└── analysis/                     ── AI 분석: 입력 구성·검증·저장·동기화 Job
    └── llm/                      LLM 클라이언트(Anthropic SDK 어댑터)
```

**의존 방향** (화살표 = "사용한다")

```
ingest.company ─────▶ company, ingest.dart, ingest.checkpoint, common
ingest.disclosure ──▶ company, disclosure, ingest.dart, ingest.checkpoint, common
ingest.financial ───▶ company, disclosure, financial, ingest.dart, ingest.checkpoint, common
signal ─────────────▶ financial, ingest.checkpoint, common
analysis ───────────▶ company, financial, signal, analysis.llm, common
analysis.llm ───────▶ (도메인 패키지를 쓰지 않음, Anthropic SDK만)
company, disclosure, financial ─▶ (다른 도메인 패키지를 쓰지 않음)
```

- 도메인 패키지(`company`, `disclosure`, `financial`)는 서로를 참조하지 않는다. 여러 도메인을 엮는 일은 Job이 한다.
- `analysis`는 예외적으로 `company`·`financial`·`signal` 세 도메인을 모두 읽는다(§4.9) — AI 입력이 재무 요약과 신호를 엮은 결과물이라서, 그 조립 로직 자체가 이 패키지의 책임이다. `disclosure`는 직접 읽지 않는다(공시번호는 재무 보고서 행에 이미 있다).
- `analysis.llm`은 `analysis`에 대한 역의존성이 없다(순수 어댑터, `LlmClient` 인터페이스만 안다).
- 다른 패키지의 리포지토리는 쓰지 않는다. 리포지토리는 package-private이고(예: `CompanyRepositories`의 인터페이스들, `DisclosureRepository`, `FinancialRepository`, `CompanySignalRepository`, `AnalysisRepository`), 밖에서는 각 패키지의 `*Service`를 쓴다(architecture.md §7).
- 예외: `ingest.checkpoint.IngestCheckpointRepository`는 public이고, `signal`도 쓴다(`SIGNAL_FINANCIAL` 체크포인트). 이름은 "ingest"지만 실제로는 "작업별 처리 상태 저장소" 역할이다. `analysis`는 체크포인트를 쓰지 않는다 — 대상별 지문 비교(`AnalysisService.decide`)로 재시도를 직접 판단한다(§4.9).

---

## 12. 설계 의도: 왜 이렇게 나눴나

| 구조 | 이유 | 근거 |
|---|---|---|
| 단일 모듈·단일 프로세스, 패키지로 기능 분리 | 1인 개발·운영에서 배포·디버깅·트랜잭션을 한 곳에서 | D-01 |
| `ingest`(수집)와 도메인 패키지 분리 | 도메인 저장 규칙이 외부 API 형식·호출 정책과 독립. 소스를 바꾸거나 추가해도 도메인은 그대로 | architecture.md §7, §9.5 |
| `DartApi` 인터페이스 | 인증키 없이 가짜 OpenDART로 Job 전체를 테스트 | `DartApi` 주석, §14 |
| 도메인마다 "서비스만 쓰기 경로" + package-private 리포지토리 | 변경 규칙(범위 판정, 별칭, 정정 연결, 통째 교체, 철회)을 한 곳에 모으고 우회를 막는다 | architecture.md §7 |
| Job / Scheduler / Properties 3분할 | 실행 시점과 로직을 분리. 테스트와 기동 직후 실행이 같은 `run()`을 쓴다 | 각 Scheduler 주석 |
| 체크포인트 + 호출 상한 + 우선순위 | OpenDART 하루 20,000건을 여러 작업이 나눠 쓰고, 초기 적재를 며칠에 나눠도 이어받는다 | architecture.md §4.4 |
| 재무를 원천 행 그대로 저장, 읽을 때 해석 | 매핑·규칙을 바꿔도 재수집 없이 다시 계산 | D-32 |
| 계산기를 순수 함수로 분리 | 판정 규칙을 DB 없이 단위 테스트. "판정은 코드"(D-06)의 핵심을 가장 검증하기 쉬운 형태로 | D-06, D-35 |
| 규칙 버전을 체크포인트 원천 버전에 포함 | 문턱값을 바꾸면 모든 기업이 자동으로 다시 판정된다 | `FinancialRuleCatalog` |
| 신호 행을 지우지 않고 상태로 관리 | 과거 변화(이력)를 타임라인에 쓰고, 정정으로 사라진 신호를 구분 | D-29, D-36 |
| 기업 JPA / 나머지 JdbcClient | 기업은 일반 CRUD, 나머지는 upsert·조인·`jsonb`를 SQL로 명확하게 | architecture.md §3 |
| `Clock` 빈 주입 | 날짜 경계(확정, 초기 적재 범위, 데이터 신선도)를 테스트에서 재현 | §11.6 |
| AI 입력을 코드가 만든 사실표·신호 참조로 좁힘 (원천 행·원문·공시번호·내부 ID 제외) | 입력에 없는 값은 출력에 나올 수 없다. 프롬프트 주입 경로를 줄이고, 프롬프트 캐싱이 걸리도록 시스템 프롬프트를 고정 문자열로 유지 | D-38 |
| AI 출력의 숫자·기간·신호를 전부 토큰(`{fin.*}`/`{per.*}`/`{sig.*}`)으로 강제 | "글자에 숫자가 있는가"만으로 숫자 오류를 기계적으로 검증할 수 있다. 계산·비교·판정은 코드가 하고 AI는 풀어 쓰기만 한다 | D-39 |
| 지문을 값 해시가 아니라 식별자 집합(공시번호·신호 자연키 등) 해시로 계산 | 표시 형식만 바뀌어도 지문이 바뀌면 불필요한 대량 재생성이 된다(D-08). 정정·철회처럼 "다시 만들어야 하는" 변화만 지문을 바꾼다 | D-08, D-40 |
| 검증 실패 1회 재생성 후 거절, 부분 게시 없음 | 설명 일부만 보이면 맥락이 끊겨 오해가 생긴다. 이전 게시본을 유지하는 편이 안전하다 | D-39 |
| `publish` 설정을 꺼 둔 채로 구현 완료 | 골든셋 사람 검토를 통과하기 전에는 실제 화면에 나갈 수 없게 하는 안전장치. 검증기를 통과해도 초안(DRAFT)에서 멈춘다 | D-39, spec/financial-explain.md §4.4.8 |
| `FinancialExplainJob`을 순차 처리로 먼저 구현 | 동시 호출 제한은 처리량 문제이고, 검증·재시도·무효화·예산은 정확성 문제다. 첫 AI 기능에서는 정확성을 먼저 확인했다 | work/3-4-financial-explain.md §7.2 |

---

## 13. 코드를 읽는 순서

처음 읽을 때 (약 반나절 분량)

1. **[README.md](../../README.md), 이 문서 §1·§6** — 무엇이 언제 도는지.
2. **[application.yml](../../src/main/resources/application.yml), 마이그레이션 V1~V4** — 설정과 테이블.
3. **[StockInsightApplication](../../src/main/java/org/stockinsight/StockInsightApplication.java), [TimeConfig](../../src/main/java/org/stockinsight/common/config/TimeConfig.java), [SchedulingConfig](../../src/main/java/org/stockinsight/common/config/SchedulingConfig.java), [PipelineRunRecorder](../../src/main/java/org/stockinsight/common/pipeline/PipelineRunRecorder.java)** — 기동과 공통 부품.
4. **[CompanySyncScheduler](../../src/main/java/org/stockinsight/ingest/company/CompanySyncScheduler.java) → [CompanySyncJob](../../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java) → [CompanyService](../../src/main/java/org/stockinsight/company/CompanyService.java) → [ListingScopePolicy](../../src/main/java/org/stockinsight/company/ListingScopePolicy.java)** — **분석 시작점.** 가장 단순한 Job으로 §7.0 골격, 체크포인트, 서비스 쓰기 경로를 익힌다.
5. **[DartApi](../../src/main/java/org/stockinsight/ingest/dart/DartApi.java) → [DartClient](../../src/main/java/org/stockinsight/ingest/dart/DartClient.java) → [DartStatus](../../src/main/java/org/stockinsight/ingest/dart/DartStatus.java)** — 외부 호출, 재시도, 실행 중단 규칙.
6. **[IngestCheckpointRepository](../../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpointRepository.java)** — 재개의 원리.
7. **[DisclosureSyncJob](../../src/main/java/org/stockinsight/ingest/disclosure/DisclosureSyncJob.java) → [DisclosureService](../../src/main/java/org/stockinsight/disclosure/DisclosureService.java) → [ReportName](../../src/main/java/org/stockinsight/disclosure/ReportName.java) → [DisclosureRepository](../../src/main/java/org/stockinsight/disclosure/DisclosureRepository.java)** — 날짜 확정, 정정 연결.
8. **[FinancialSyncJob](../../src/main/java/org/stockinsight/ingest/financial/FinancialSyncJob.java) → [PeriodicReportName](../../src/main/java/org/stockinsight/financial/PeriodicReportName.java) → [FinancialService](../../src/main/java/org/stockinsight/financial/FinancialService.java) → [FinancialRepository](../../src/main/java/org/stockinsight/financial/FinancialRepository.java)** — 가장 복잡한 수집. 계기·묶음·기간 검증.
9. **[FinancialSummaryService](../../src/main/java/org/stockinsight/financial/FinancialSummaryService.java) → [AccountMapper](../../src/main/java/org/stockinsight/financial/AccountMapper.java) → `QuarterEntry`/`PeriodKey`** — 원천 행이 시계열이 되는 곳.
10. **[FinancialSignalCalculator](../../src/main/java/org/stockinsight/signal/FinancialSignalCalculator.java) + [FinancialRuleCatalog](../../src/main/java/org/stockinsight/signal/FinancialRuleCatalog.java) → [CompanySignalService](../../src/main/java/org/stockinsight/signal/CompanySignalService.java) → [FinancialSignalJob](../../src/main/java/org/stockinsight/signal/FinancialSignalJob.java)** — 제품의 핵심인 변화 판정. `calculate()`부터.
11. **[FinancialExplainInputBuilder](../../src/main/java/org/stockinsight/analysis/FinancialExplainInputBuilder.java) → [FinancialExplainValidator](../../src/main/java/org/stockinsight/analysis/FinancialExplainValidator.java) → [FinancialExplainJob](../../src/main/java/org/stockinsight/analysis/FinancialExplainJob.java) → [AnthropicLlmClient](../../src/main/java/org/stockinsight/analysis/llm/AnthropicLlmClient.java)** — AI 분석. 앞의 10단계(특히 4·9)를 먼저 이해해야 입력이 어디서 오는지 알 수 있다.

목적별 시작점

| 하려는 일 | 먼저 볼 곳 |
|---|---|
| 새 OpenDART API 추가 | `DartApi`, `DartClient`, 기존 `*SyncJob` 한 개, 가짜 `DartApi`를 가진 테스트들 |
| 새 수집 작업 추가 | §7.0 골격, `*Scheduler`·`*Properties`, application.yml, 이 문서 §6·§8 |
| 신호 규칙 변경 | `FinancialRuleCatalog`(버전 올리기), `FinancialSignalCalculator`, `FinancialSignalCalculatorTest`, spec/signals-financial.md §3 |
| 재무 해석 변경 | `AccountMapper`, `FinancialSummaryService`, `FinancialSummaryServiceTest` |
| 수집이 왜 안 됐는지 조사 | `pipeline_run.message`, `ingest_checkpoint` (소스별 대상 키 §4.3), 각 Job의 `due*` 메서드 |
| AI 입력·사실표 변경 | `FinancialExplainInputBuilder`, `PeriodLabels`, `Fingerprint`, `FinancialExplainInputBuilderTest`, spec/financial-explain.md §4.4.2 |
| 검증 규칙 추가·조정 | `FinancialExplainValidator`(규칙을 바꾸면 `VERSION`도 올림), `FinancialExplainValidatorTest`(경계값 37건), `FinancialExplainPrompt.retryGuidance`(새 규칙에 재시도 안내 추가)·`FinancialExplainPromptTest`, spec/financial-explain.md §4.4.7·§7 |
| 거절된 출력의 원인 조사 | `analysis.attempts`(시도별 결과·실패 규칙·출력·파싱 실패 원문·검증기 버전, D-53). V7 이전 행은 null |
| 프롬프트·모델 변경 | `src/main/resources/prompts/financial_explain/`, `FinancialExplainPrompt`(버전 상수), `LlmProperties.model` |
| AI 동기화가 왜 건너뛰었는지 조사 | `pipeline_run.message`(`analysis-financial-explain`), `AnalysisService.decide()`, `analysis` 테이블의 `status`·`fingerprint`·`failure_reasons` |

---

## 14. 테스트 구조

| 종류 | 파일 | 방식 |
|---|---|---|
| 통합(Job 전체) | `CompanySyncJobTest`, `DisclosureSyncJobTest`, `DisclosureSyncCallLimitTest`, `FinancialSyncJobTest`, `FinancialSyncCallLimitTest`, `FinancialSignalJobTest`, `FinancialSignalStaleJobTest`, `FinancialExplainJobTest`, `AnalysisRendererTest` | `@SpringBootTest` + `@Import(TestcontainersConfiguration, Fake*Config)`. 테스트 안의 `FakeDartConfig`/`FakeLlmConfig`/`FixedClockConfig`가 `FakeDartApi`·`FakeLlmClient`·`MutableClock`을 `@Primary` 빈으로 등록해 `DartApi`·`LlmClient`·`Clock` 주입을 대체한다. `@SpringBootTest(properties = ...)`로 호출 상한·`publish` 등을 줄인다. 테스트마다 `truncate ... restart identity cascade`(공유되는 `MutableClock` 싱글턴은 `truncate`로 리셋되지 않으므로, 남은 값에 좌우되지 않도록 `@BeforeEach`에서 먼저 명시적으로 날짜를 맞춘다, `FinancialSignalStaleJobTest`) |
| 클라이언트 | `DartClientTest` | `src/test/resources/dart/`의 실제 응답 샘플 (세부 방식은 이 문서에서 추적하지 않음) |
| 단위 | `ListingScopePolicyTest`, `ReportNameTest`, `PeriodicReportNameTest`, `FinancialServiceTest`, `FinancialSummaryServiceTest`, `FinancialSignalCalculatorTest`, `FinancialExplainValidatorTest`, `FinancialExplainPromptTest`, `FinancialExplainGoldenContractTest`, `PeriodLabelsTest`, `KoreanTextTest`, `AnalysisRendererDisplayTest` | 파일명 기준 분류. `KoreanTextTest`(10건)는 fx-v10 사람 검토 사례("만위안로"·"위안였어요"·"억원예요")의 조사 보정, 받침 없는 값(달러·%·%p·분기), 'ㄹ' 받침·숫자 읽기, 바꾸지 않는 경우(0·외국 통화 코드·"이고"·"는데"), 기간 라벨의 괄호, 문장 경계, D-58의 `isLeadingBadge`·`isNameBadge`(이름 자리·앞머리·값과 서술어 사이)를 확인한다. `AnalysisRendererDisplayTest`(7건)는 Spring 없이 `new AnalysisRenderer(null, null)`로 조사 보정, 수준 비율 '+' 없음·문장 안 변화량 무부호(D-58)·음수 수준, 저장 표시 값으로의 폴백, 문장 목록, 이스케이프, 앞머리 배지 괄호 표기·이름 배지 유지(D-58)를 확인한다. `FinancialExplainValidatorTest`의 D-56 테스트 3건은 규칙 14(값과 서술어 사이 실패, 문장·절 맨 앞·"신호" 앞·기간 부사어 뒤 통과)와 규칙 7(흐름 사실 문장의 기간 토큰)을 확인한다. 각 파일이 Spring 컨텍스트를 쓰는지는 이 문서에서 확인하지 않음. `FinancialExplainValidatorTest`는 Spring 없이 `new FinancialExplainValidator()`로 직접 검증기를 만들어 §4.4.7 규칙 1~13의 경계값 37건을 확인한다(그중 7건은 문장 분리 경계, D-53: "보다"·"그보다"·"주요/필요/중요"가 문장을 나누지 않음, 실제 문장 수가 상한을 넘으면 여전히 규칙 10, 마침표 없는 '요' 끝은 여전히 경계, "보다"를 사이에 둔 규칙 5 통과·규칙 6 검출). `FinancialExplainValidatorTest`의 D-54 테스트 9건은 규칙 6(변화량을 수준으로 씀, 부호가 섞인 문장의 두 방향), 규칙 7(활성 신호가 자기 섹션을 벗어남, 활성은 개요 허용·이력은 불허), 규칙 11(개요·`structure`의 배정 밖 사실, 배정 없는 섹션은 제한 없음), 과거 입력의 이전 판정 유지를 확인한다. D-55 테스트 20건은 fx-input-4 모양의 입력(네 섹션 배정·관계 항목)으로 규칙 6(신호 대응표·부채비율 급등·매출 연속 흐름·R2는 입력 값만 따름·SAME·평가어·716 오탐 해소), 규칙 12(상태 단어·손실·손해, "영업적자" 예외, 전환·이어짐 서술어), 규칙 13(늘 금지, 종류별 비교 근거, 대조 근거, 근거 없는 증감, "그보다 앞선"은 비교 아님), 규칙 7(`history` 순서·시간 어휘), 규칙 9(내부 용어, 실제 2715 문장), 규칙 11(`sales_profit`·`history`)을 확인한다. `FinancialExplainValidatorTest`의 D-58 테스트 8건은 규칙 14 확장(배지·지표 결합: 872형 배지만 있는 절, 716형 다른 지표에 붙은 배지, history처럼 배정이 없는 섹션은 검사 안 함), 규칙 13 확장("(으)로" 서로 다른 지표 잇기 대 같은 지표 값+비교), 규칙 6 절 단위(820형 부호가 섞인 문장의 어휘 자리 바꿈), 규칙 5·13의 정도 어휘(많이 있음/없음, 조금·약간·살짝 항상 실패)를 확인한다. `FinancialExplainPromptTest`는 재시도 안내가 모든 실패 코드(규칙 1~14, `LLM_OUTPUT_REJECTED`, `INVALID_JSON`)에 서로 다른 문구로 있고, 검증기 3이 프롬프트 "절대 금지 7"을 가리키며, 모르는 코드는 예외임을 확인한다. D-58·D-59 테스트 6건은 `PROMPT_VERSION`이 `fx-v13`임, 재시도 안내(14의 "자기 지표", 13의 "(으)로"·"조금", 6의 "절"·"방향 없는 말", 3이 다른 규칙을 함께 푼다는 설명, 재시도 메시지에서 3이 맨 앞에 옴), 입력 구조 설명에 `display`·`label`이 없고 `kind`가 있음, R9 예시가 배지+사실 부착형임을 확인한다. `FinancialSignalCalculatorTest`는 `assessStale`의 12·3·6월 결산 기한 경계도 확인한다(D-43) |
| 입력 구성(통합) | `FinancialExplainInputBuilderTest` | `@SpringBootTest` + Testcontainers. 사실표·섹션·`unavailable`·지문(정정 시 변경 포함)·결정적 입력(같은 데이터 → 같은 JSON)을 실제 신호 계산(`FinancialSignalJob`)까지 거쳐 확인. D-54 테스트 6건: 잠식이 없으면 `structure`는 부채비율·변화량뿐, 과거 기간에 시작된 활성 자본잠식은 최신 기간·`structure`·최신 사실로 이어지고 같은 기간 이력 신호와 묶이지 않음, NONE 개요 우선순위와 비재배정, CHANGED 개요는 첫 묶음 변화량만, 금융형 `structure`는 자본총계뿐, 재무상태표 불일치 + 활성 자본잠식이면 사실 없이 `structure`를 연다(활성 신호는 `company_signal`에 직접 넣어 재현). D-54 보완·D-55 테스트 11건: 사실표 = 배정 합집합·스냅샷 일치, 순이익 전환 사실 하나(표시·스냅샷 원값·출처), 값 0이면 상태·전환 없음, 둘 다 흑자면 R2 + 전년 영업이익, 둘 다 적자면 R2 없이 R5, 신호 없는 영업이익 전환(일반형 2% 미만·금융형 분기)은 우선순위 6의 전환 사실, 전환 신호가 첫 묶음이면 전환 사실은 개요, 0 근처 순이익 전환도 전환 사실, `history` 시간순·이력 `factKeys` 비움, 금지 용어와 모순되는 사실 배정 안 함(2715형). D-59 테스트 3건: 매출·영업이익률·부채비율 사실 이름이 실제 부호(감소·하락)를 담음, 12개월이 아닌 회계연도(결산기 변경 재현)는 우선순위 7("최근 사업연도 사실")에서 빠짐, 비12월 결산은 AI 입력에 라벨 없이 `kind=ANNUAL`만 있고 값 스냅샷에만 라벨이 있음 |
| 컨텍스트 | `StockInsightApplicationTests` | 기동 확인 |

- `TestcontainersConfiguration`: `postgres:17` 컨테이너를 `@ServiceConnection`으로 데이터소스에 연결한다. 테스트는 프로필이 없으므로 비밀값 파일을 읽지 않고, 인증키 없이 돈다.
- `FakeLlmClient`(`analysis.llm` 테스트 패키지): 준비해 둔 `LlmResult`/예외를 호출 순서대로 돌려주고 호출 인자(시스템 프롬프트·사용자 입력·스키마)를 기록한다. `FinancialExplainJobTest`가 이걸로 재시도·거절·호출 실패·예산·게시 여부·중복 호출 방지를 인증키 없이 확인한다. 재시도 사용자 메시지에 규칙 번호가 아니라 `retryGuidance` 문구가 들어가는지, 시도별 기록(`attempts`)이 결과 종류마다(통과·거절·JSON 파싱 실패·잘림·호출 실패) 저장되고 거절 행의 `result_json`은 비어 있는지도 확인한다.
- 골든셋: `src/test/resources/golden/financial_explain/*.json`은 `GoldenSetDumpRunner`(§4.9)가 로컬 실 데이터로 만든 고정 입력이다(`fx-input-6`으로 2026-09-27 재생성, §7.4.34). 프롬프트·모델을 바꿀 때 사람이 비교하는 참고 자료이고, `FinancialExplainGoldenContractTest`(DB 없음, 12개사 매개변수 테스트)가 이 파일로 D-54 보완·D-55·D-59 입력 계약을 점검한다: 사실표 = 배정 합집합, 섹션 상한, 회사별 `sales_profit` 개수(§7.4.26 모의, 2386은 D-59로 4→3), 상태·전환 사실의 부호(표시 문구는 값 스냅샷에만 있어 fixture로는 확인 못 함, D-59), 관계 항목 형태, `history` 시간순, 금지 용어와 모순되는 사실 없음, 기간 종류(`kind`, 라벨 없음). 입력 구성기를 바꾸면 fixture를 다시 만들고 이 테스트로 확인한다.
- CI(`ci.yml`): gitleaks 비밀값 검사, JDK 21에서 `./gradlew build`(테스트 포함). 실패하면 테스트 리포트를 업로드한다. Anthropic 인증키 없이도 전부 통과한다(`FakeLlmClient`).
- gitleaks 설정(`.gitleaks.toml`)
  - CI의 `gitleaks-action@v3`는 gitleaks **8.24.3**을 `detect --redact -v --exit-code=2`로 실행한다. `--config`를 넘기지 않으므로 gitleaks가 저장소 루트의 `.gitleaks.toml`을 자동으로 읽는다(디버그 로그 `using existing gitleaks config .gitleaks.toml`). push는 `--log-opts=--no-merges --first-parent <base>^..<head>` 범위만 검사한다.
  - 기본 규칙(`[extend] useDefault = true`)을 모두 쓴다.
  - 8.24.3 기본 규칙에는 Anthropic 키 규칙이 없다(실제 `sk-ant-api03-…` 형식 키도 탐지되지 않음을 확인). 그래서 최신 gitleaks의 `anthropic-api-key`, `anthropic-admin-api-key` 규칙을 원문 그대로 추가했다. gitleaks 버전을 올려 기본 규칙에 포함되면 중복되므로 이 두 규칙을 지운다.
  - 허용은 재무 사실 키 하나뿐이다: `^fin\.[a-z]+(?:_[a-z]+)*\.\d{4}-(?:0[1-9]|1[0-2])\.Q[1-4]$`(비밀값 문자열 전체와 일치해야 함). 이 값은 `"key": ...` 형태라서 `generic-api-key`에 걸린다(골든셋 JSON, spec/). 기간 키(`YYYY-MM.Qn`)만 있는 값은 기본 규칙에 걸리지 않으므로 허용하지 않는다. 지표 자리에 숫자를 허용하지 않는 것은 무작위 문자열이 허용 형식을 흉내 내는 것을 막기 위함이다.
  - 키 형식(`PeriodKey.displayKey()`, `FinancialExplainInputBuilder`의 `"fin." + 지표 + "." + 기간 키`)을 바꾸면 이 정규식도 함께 고친다. 허용 범위를 파일·경로 단위로 넓히지 않는다.
  - 설정을 바꿀 때 검증: CI와 같은 버전(8.24.3)으로 ① `gitleaks detect --source . -v --log-level=debug`(이력 전체, 설정 자동 인식), ② `gitleaks detect --source . --no-git`(작업 트리), ③ 가짜 비밀값을 넣은 임시 저장소에서 탐지 여부를 확인한다. 2026-09-26 검증 결과: 기본 규칙만으로 141건(모두 `fdb0ef4`의 재무 사실 키) → 이 설정으로 0건. 가짜 일반 API 키·DART형 hex 키·Anthropic 키·관리자 키·허용 형식 우회값(지표에 숫자, 앞뒤 덧붙임, 잘못된 월, 허용 키와 같은 줄의 비밀값)은 모두 탐지됐다.

---

## 15. 확인하지 못한 것과 알려진 한계

**이 문서에서 확인하지 못한 것**

| 항목 | 상태 |
|---|---|
| 스케줄된 작업끼리 동시에 도는지 | Spring Boot 기본 스케줄러 구성(가상 스레드 사용 시)에 따른다고 판단. 실행 확인 없음 (§11.4) |
| 프로필 없이 IDE에서 실행할 때의 결과 | 확인하지 않음 (§5.1) |
| `CorpCodeXmlParser`, `KoreanInitials`, `FinancialAmounts`, `DartDisclosure`의 세부 구현 | 주석·호출부만 확인 |
| 단위 테스트 각각의 구성 | 파일명만 확인 |
| 운영 배포 방식 | 코드·스크립트 없음 [설계만] (architecture.md §9) |
| 골든셋 사람 검토 | fx-v10 12개에 대해 1차 사람 검토를 했고(2026-09-27), 결과를 D-56으로 반영했다(§7.4.29). fx-v11 실제 재검증(12/12 시도 22회, §7.4.30) 뒤 2차 사람 검토에서 개요 완전성 계약 충돌(872)을 확인해 D-57로 반영했다(§7.4.31, `fxv-7`·`fx-v12`). fx-v12 실제 재검증(9/12 시도 23회, §7.4.32)에서 개요 완전성이 100%(첫 묶음에 신호가 둘인 4개사 8회 전부) 확인됐다. fx-v12 결과를 분석해 배지·지표 결합·"로" 잇기·절 단위 방향(D-58)과 표시 값·라벨 제거(D-59)로 반영했다(§7.4.33). fx-v13 실제 재검증(11/12 시도 15회, §7.4.34)에서 **첫 시도 규칙 3이 0/12**(fx-v12 11/12)로 확인됐다. fx-v13 12개로 §4.4.8 사람 검토를 마쳤고(2026-09-27, §7.4.35) 사실 오류·금지 표현 0건, D-58·D-59를 현재 상태로 확정했다. 820 규칙 14 반복, 1640 "분기로 세면", 1640 `NONE` 대체 사실의 화면상 전달, 자본잠식 표현은 관찰 사항이다. 게시(`publish`)는 여전히 꺼져 있다. 토큰·비용은 `analysis` 행과 시도 기록에 있다 |

**AI 분석의 알려진 한계** (2026-09-25, 3-4 세션에서 실제 데이터로 확인. 판단 대기 항목은 status.md §3)

- ~~`AccountMapper`가 "영업수익"을 인식하지 못한다~~ → 오진이다(work/3-4-financial-explain.md §8.1). 주요계정 API에는 "영업수익" 계정명이 없다. 금융형 증권·투자사는 "매출액"으로 와서 이미 "영업수익" 원값 경로(증가율 없음)를 탄다(골든셋 SV인베스트먼트). 은행·보험(DB손해보험 등)은 매출 계정 자체가 없다(`ACCOUNT_MISSING`).
- ~~비원화 금액이 축약·구분자 없이 원값+통화코드로 표시된다~~ → D-41로 수정 완료(2026-09-26). 통화 무관 한국어 수 단위(조·억·만) + 통화명, 환산 없음. `revenue_yoy_run`도 최신 기간 `revenue_yoy`를 줄 수 있을 때만 준다. `PeriodLabelsTest`(13건) + 실데이터 12개사 재덤프로 확인(work/3-4-financial-explain.md §8.7).
- ~~`AnalysisRenderer.isInvalidated()`가 지문 전체 재계산-비교 방식이라 과잉 무효화한다~~ → D-42로 수정 완료(2026-09-26). 스냅샷이 참조한 신호 자연키·사실의 (기간 종료일, 기준)만 조회해 판정하고, 입력 전체를 다시 만들지 않는다. `AnalysisRendererTest`(4건)로 확인.
- ~~`FIN_DATA_STALE`이 첫 전체 실행 뒤 새로 기한이 지나는 기업을 다시 판정하지 않는다~~ → D-43으로 수정 완료(2026-09-26). 기한 60·120일(다음 기간 종료월 말일 기준) + 유예 7일, 판정이 남긴 재판정일(`ingest_checkpoint.next_check_at`)이 지난 기업만 추가로 다시 판정, 해소는 이력(PAST). `FinancialSignalCalculatorTest`(6건)·`FinancialSignalStaleJobTest`(4건) + 실데이터(로컬 DB 전체 재판정, 규칙 버전 `fin-2` 반영 확인)로 확인.
- `FinancialExplainJob`은 순차 처리다. spec/analyses.md §6.2의 동시 호출 수 설정은 구현하지 않았다.
- 골든셋 입력 파일(`src/test/resources/golden/financial_explain/`)을 다시 읽어 재생성하는 경로가 없다. 지금은 `GoldenSetDumpRunner`(입력 JSON만 파일로 저장, AI 호출 없음)만 있다.
- `company.ai_covered`를 채우는 코드가 없다(D-23 미구현, §3). `FinancialExplainJob`의 대상은 지금 전부 `golden-set-company-ids`에서 온다.
- ~~검증기 규칙 6의 증감 사전이 "높아지"·"낮아지"·"좋아지"·"나빠지" 어간의 '지+었' 축약 과거형("높아졌어요"·"낮아졌어요")을 못 잡는다~~ → 수정 완료(2026-09-26). 각 어간의 축약형 6개(져·졌·진·질·짐·집)를 사전에 추가했다. "높아요"·"낮아요"(수준 서술) 오탐은 생기지 않는다(축약형만 추가, 어간 자체는 그대로). 신호 방향·영업이익 부호·평가어(개선/악화)·전환 오탐 등 §7.4.19에서 함께 확인한 나머지 항목은 이번에 다루지 않고 별도 설계 판단 대상으로 남겼다(work/3-4-verification-1.md §7.4.19). `FinancialExplainValidatorTest`(축약형 6건 + 오탐 방지 1건)로 확인.
- D-54·D-54 보완·D-55(2026-09-27 구현, `fx-input-5`·`fx-v10`·`fxv-5`): 네 섹션의 사실 배정과 관계 서술을 코드가 정한다. 변화량이 0이면 부호가 "+"라 "늘었어요/높아졌어요"를 요구한다(드물다). 사실 이름에 "증가"가 든 매출 증가율은 이름만으로도 증감 어휘가 있는 것으로 세진다. 남은 한계: (1) 검증은 문장 단위라 한 문장 안에서 근거가 있는 절과 없는 절이 같은 방향 어휘를 쓰면 함께 통과한다(절 단위 귀속). (2) 관계 어휘 목록은 열린 집합이다 — 목록 밖 비교 어휘(웃돌다 등)와 흑자·적자의 서술형 동의어("이익을 냈다")는 사람 검토 대상이다. (3) 토큰과 문장 주어의 불일치("영업이익은 {fin.net_income…}"), 비교 기준 어휘 오류("부채비율 변화에 전년 같은 분기보다")는 검사하지 않는다. (4) R2는 두 영업이익 토큰이 같은 문장에 있어야 근거가 된다 — 나눠 쓰면 맞는 주장도 규칙 13이다(608, D-55 재검토 조건). (5) 0 근처의 흑자·적자 뒤바뀜도 전환 사실로 쓰인다(크기는 값으로 보인다, D-54 보완 재검토 조건). (6) 첫 시도 규칙 3(기간 라벨·표시 값 복사)이 12개사 중 9~10곳이다(D-38 재검토 대상).
- D-56(2026-09-27): (1) AI 입력의 표시 값은 아직 수준 비율에도 '+'가 붙는다(렌더러만 바뀜, 다음 입력 구성 변경 때 맞춤). (2) 조사 보정은 0으로 끝나는 숫자·외국 통화 코드 뒤에서는 하지 않는다. (3) 사업보고서 기간과 파생 4분기가 같은 기간 키다 — 흐름 사실 문장의 기간 토큰만 막았고, 키 자체는 나누지 않았다. (4) "분기로 세면"·"지난 회계연도 말"·전환 문장 형태는 프롬프트 지침이고 검증기는 보지 않는다. (5) 섹션별 비교 기준 날짜 표시와 문장별 줄바꿈 화면은 화면 구현 때 한다(렌더러는 문장 목록만 준비).
- D-57(2026-09-27): 개요 완전성은 `overview`에만 적용하고 `sales_profit`·`structure`는 그대로 자유롭다 — 완전성을 넓히면 D-54 보완이 의도한 우선순위 목록의 유연성과 충돌한다(기존 검증기 테스트 다수가 그 자유를 전제로 짜여 있음을 확인). 입력 계약(`assignSectionFacts`)은 바꾸지 않았다 — 개요 배정을 하나로 줄이는 대안은 737에서 순이익 전환이 상한 밖으로 밀려나는 새 손실을 만들어 기각했다(모의로 확인). 첫 시도 규칙 3(기간 라벨·표시 값 복사)은 이번에도 다루지 않았고 D-38 재검토로 남겼다. 실제 재검증(fx-v12, §7.4.32)에서 개요 완전성이 8/8(100%)로 확인됐고 새 규칙 11 오탐은 0건이었다.
- D-58·D-59(2026-09-27): fx-v12 사람 검토에서 나온 배지·문장 결합, 배지·지표 결합, "로" 잇기, 절 단위 방향(D-58), 첫 시도 라벨·표시 값 복사·12개월 아닌 회계연도(D-59)를 반영했다. 실제 재검증(fx-v13, §7.4.34)에서 **첫 시도 규칙 3이 10~11/12 → 0/12**로 줄어 D-59의 가설(표시 문자열이 옆에 있으면 그대로 옮겨 적는다)을 확인했다. 자동 검증은 9/12 → 11/12. 남은 거절(820, 규칙 14)은 배지가 다른 절의 사실에 붙은 개별 사례로, 737형과 같은 성격이라 완화하지 않았다. 남은 한계: (1)~(6)은 그대로다(아래에서 옮기지 않았다). AI 입력에서 표시 값·라벨을 빼도 사람이 읽는 결과(렌더링)는 그대로이므로, 이 변경은 입력 계약과 검증기·프롬프트·렌더러에만 영향이 있다.
- 문장 분리에서 제외하는 말은 "보다"·"주요"·"필요"·"중요"뿐이다. 그 밖에 '다/요'로 끝나지만 문장 끝이 아닌 말은 여전히 경계로 세진다(더 엄격한 쪽으로 오판, D-53).
- ~~2715처럼 최신 분기에만 계정이 없는 기업의 `unavailable`과 연간 사실의 모순~~ → D-54 보완으로 해소(2026-09-27): 이름이 금지 용어·`unavailable`과 모순되는 사실은 배정하지 않는다. ~~`schema.json`의 `history` 설명과 D-45의 충돌~~ → 설명을 고쳤다. `style.md`의 "한 문장에 한 가지 사실"과 `v1.md`의 묶어쓰기 지침(R2·R6처럼 한 문장에 써야 하는 관계 포함)의 충돌은 남아 있다.

**코드상 관찰되는 한계** (변경 제안이 아니라 현재 동작의 기록. 판단 대기 항목은 status.md §3)

- 상장폐지 기업 중 고유번호 파일에 종목코드가 남는 기업은 DELISTED가 아니라 EXCLUDED(`OTHER_MARKET`)로 저장된다(status.md §3).
- 확정된 공시 날짜는 다시 읽지 않는다. 그 날짜에 뒤늦게 추가된 공시는 놓친다(같은 문서 §3).
- 작업 간 순서는 cron 시각으로만 보장된다. 앞 작업이 늦게 끝나면 다음 작업은 그 시점 데이터로 돈다.
- ERROR 대상은 횟수 제한 없이 매 실행 다시 시도한다.
- `financial_line`은 행마다 insert한다(초기 적재가 느림, 같은 문서 §3).
- `*Properties.cron` 레코드 필드는 바인딩만 되고 코드에서 읽지 않는다(`@Scheduled`가 플레이스홀더로 직접 읽음).
- `company.ai_covered`를 설정하는 코드는 없다.
- `company_signal`, `security`, `company_alias`를 읽는 운영 코드(화면·AI)는 아직 없다.

---
## 16. 변경 시 문서 갱신 체크리스트

코드를 바꾸면 해당하는 줄을 확인하고, 해당 절을 같은 작업 안에서 고친다. 끝나면 머리말의 "기준 시점"을 갱신한다.

| 변경 | 고칠 절 |
|---|---|
| 패키지·클래스 추가/삭제/이름 변경, 책임 이동 | §3, §4, §13 (필요하면 §12) |
| 새 Job·스케줄·기동 동작·HTTP 엔드포인트 | §1, §5.2, §5.3, §6, §7(새 흐름 추적 추가) |
| 설정 키 추가·변경·기본값 변경 | §8 (프로필 파일 포함) |
| Bean 등록 방식·의존성 변경 | §5.3 |
| 테이블·컬럼·마이그레이션 추가 | §10, §9 |
| DTO·레코드 추가, 변환 경로 변경 | §9 |
| 트랜잭션 경계, 예외 분류(`DartStatus.stopsRun` 포함), 재시도, 동시성 보호 변경 | §11 |
| 신호 규칙·버전 변경 | §4.8, §7.4 (규칙 세부는 spec/README.md가 기준) |
| AI 입력·검증 규칙·프롬프트·모델·예산 변경 | §4.9, §4.10, §7.6, §8, §9.5 (규칙 세부는 spec/README.md가 기준) |
| 테스트 구조·CI 변경 | §14 |
| 미확인 항목을 확인했거나 한계가 해소됨 | §15 |
| 설계 문서에만 있던 것을 구현 | §1의 "없는 것", §10의 [설계만] 목록에서 옮긴다 |
