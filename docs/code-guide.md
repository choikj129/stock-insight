# 코드 분석 가이드

| 항목 | 내용 |
|---|---|
| 상태 | 현재 코드 기준 (코드와 함께 갱신하는 기준 문서) |
| 기준 시점 | 2026-09-26, `main` 작업 트리 (커밋되지 않은 변경 포함) |
| 관련 문서 | [제품 정의](product.md), [시스템 설계](architecture.md), [데이터·AI 분석 명세](ai-analysis.md), [설계 결정 기록](decisions.md), [구현 계획](implementation-plan.md) |

이 문서는 **지금 있는 코드가 어떻게 구성되어 있고 실제로 어떤 순서로 실행되는지**를 설명한다. 무엇을 왜 만드는지는 product.md, 목표 설계는 architecture.md·ai-analysis.md, 진행 현황은 implementation-plan.md가 기준이다. 이 문서는 설계가 아니라 **구현 현황의 기준**이다. 설계 문서에 있어도 코드에 없는 것은 여기에 "없음"으로 적는다.

표기 규칙
- `클래스.메서드()` 뒤의 링크는 해당 코드 위치다.
- **[미확인]**: 코드나 설정으로 직접 확인하지 못했고, 프레임워크 기본 동작이나 추정에 기대는 내용이다.
- **[설계만]**: 설계 문서에는 있지만 코드에는 아직 없는 것이다.

> **유지 규칙.** 패키지·클래스 추가/삭제, 실행 경로(스케줄·HTTP·기동 동작) 변경, 설정 키 추가·변경, 테이블·데이터 흐름 변경, 트랜잭션·예외·재시도·동시성 규칙 변경이 생기면 같은 작업 안에서 이 문서의 해당 절을 고친다. 변경 시 확인할 절은 §16의 체크리스트를 따른다.

---

## 목차

1. [한눈에 보기](#1-한눈에-보기)
2. [저장소 구조](#2-저장소-구조)
3. [패키지 구조와 의존 방향](#3-패키지-구조와-의존-방향)
4. [클래스별 책임](#4-클래스별-책임)
5. [애플리케이션 기동과 Bean 구성](#5-애플리케이션-기동과-bean-구성)
6. [실행 경로](#6-실행-경로)
7. [실행 흐름 추적](#7-실행-흐름-추적)
8. [설정값과 사용 위치](#8-설정값과-사용-위치)
9. [데이터 흐름과 타입 변환](#9-데이터-흐름과-타입-변환)
10. [DB 테이블과 읽기·쓰기 주체](#10-db-테이블과-읽기쓰기-주체)
11. [실행 특성: 트랜잭션·예외·재시도·동시성·시간](#11-실행-특성-트랜잭션예외재시도동시성시간)
12. [설계 의도: 왜 이렇게 나눴나](#12-설계-의도-왜-이렇게-나눴나)
13. [코드를 읽는 순서](#13-코드를-읽는-순서)
14. [테스트 구조](#14-테스트-구조)
15. [확인하지 못한 것과 알려진 한계](#15-확인하지-못한-것과-알려진-한계)
16. [변경 시 문서 갱신 체크리스트](#16-변경-시-문서-갱신-체크리스트)

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

**AI 분석은 기본적으로 게시되지 않는다.** `app.analysis.financial-explain.publish=false`(기본값)이면 검증을 통과해도 `analysis` 테이블에 DRAFT로만 쌓이고, 화면에 나갈 게시본(`is_current`)이 되지 않는다(§4.9, D-39). 실제 유효한 인증키로 생성·사람 검토를 아직 하지 않았다(implementation-plan.md §7.4) — **[미확인]**.

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
├── docs/                   설계·계획·이 가이드
└── src/
    ├── main/java/org/stockinsight/   애플리케이션 코드 (§3)
    ├── main/resources/
    │   ├── application.yml           공통 설정 (§8)
    │   ├── application-local.yml     로컬: compose DB, 비밀값 파일(optional)
    │   ├── application-prod.yml      운영: 환경 변수 DB, 비밀값 파일(필수), 스케줄러 켜짐
    │   ├── db/migration/V1~V6        Flyway 스키마 (§10)
    │   └── prompts/                  AI 시스템 프롬프트: common/style.md, financial_explain/v1.md·schema.json (§4.9)
    ├── test/java/org/stockinsight/   테스트 (§14)
    ├── test/resources/dart/          실제 OpenDART 응답 샘플 (출처는 그 폴더의 README.md)
    └── test/resources/golden/financial_explain/   골든셋 입력 JSON 고정본(`GoldenSetDumpRunner`가 만듦, §4.9·§6)
```

Gradle 모듈은 하나다(멀티모듈 없음, D-01). 기준 패키지는 `org.stockinsight`다. 설계 문서 일부에 남은 `org.stockinsight.stockinsight`는 옛 이름이다(implementation-plan.md §2 3-0).

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

## 4. 클래스별 책임

### 4.1 `org.stockinsight` / `common`

| 클래스 | 종류 | 책임 |
|---|---|---|
| [StockInsightApplication](../src/main/java/org/stockinsight/StockInsightApplication.java) | `@SpringBootApplication` `@ConfigurationPropertiesScan` | 진입점. `org.stockinsight` 아래 컴포넌트 스캔과 `@ConfigurationProperties` 레코드 스캔의 기준 |
| [TimeConfig](../src/main/java/org/stockinsight/common/config/TimeConfig.java) | `@Configuration` | `Clock` 빈(`Clock.systemUTC()`)과 상수 `SERVICE_ZONE = Asia/Seoul`. 모든 시각은 이 `Clock`에서, 모든 "오늘"은 `SERVICE_ZONE` 기준으로 계산한다 |
| [SchedulingConfig](../src/main/java/org/stockinsight/common/config/SchedulingConfig.java) | `@Configuration` `@EnableScheduling` `@ConditionalOnBooleanProperty("app.scheduler.enabled")` | 설정이 true일 때만 `@Scheduled`를 활성화한다 |
| [PipelineRunRecorder](../src/main/java/org/stockinsight/common/pipeline/PipelineRunRecorder.java) | `@Component` | `pipeline_run` 기록. `start()`는 같은 작업의 남은 RUNNING 행을 FAILED로 정리한 뒤 새 행을 넣고 ID를 돌려준다. `finish()`는 상태·건수·메시지(최대 1,000자)를 남긴다. 상태: `RUNNING`, `SUCCEEDED`, `PARTIAL`(호출 상한 등으로 일부만), `FAILED` |

### 4.2 `ingest.dart` — OpenDART 연동

| 클래스 | 책임 |
|---|---|
| [DartApi](../src/main/java/org/stockinsight/ingest/dart/DartApi.java) | 수집기가 쓰는 OpenDART 기능 4개: `fetchCorpCodes()`, `fetchCompany(corpCode)`, `fetchDisclosures(date, type, page)`, `fetchKeyAccounts(corpCodes≤100, bsnsYear, reportCode)`. 모든 실패는 `DartApiException`. 테스트는 이 인터페이스를 가짜로 바꾼다 |
| [DartClient](../src/main/java/org/stockinsight/ingest/dart/DartClient.java) | `DartApi` 구현. `RestClient`로 GET, 응답 바이트를 Jackson 3 `JsonMapper`(자체 인스턴스)로 파싱. 요청 간 최소 간격(`RequestPacer`), 통신 오류·5xx만 재시도. 상태 013은 빈 결과로 바꾼다. 예외 메시지에 URL(인증키 포함)을 넣지 않는다 |
| [DartConfig](../src/main/java/org/stockinsight/ingest/dart/DartConfig.java) | `DartClient` 빈 등록. JDK `HttpClient`(연결 타임아웃) + `JdkClientHttpRequestFactory`(읽기 타임아웃) |
| [DartProperties](../src/main/java/org/stockinsight/ingest/dart/DartProperties.java) | `app.dart.*` 바인딩. `toString()`은 인증키를 가린다 |
| [DartStatus](../src/main/java/org/stockinsight/ingest/dart/DartStatus.java) | OpenDART 상태 코드 + 내부 상태. `stopsRun()`이 true면 실행 전체를 멈춘다: 010·011·012(키/IP), 020(한도 초과), 800(점검), 901, `MISSING_KEY`, `TRANSPORT_ERROR` |
| [DartApiException](../src/main/java/org/stockinsight/ingest/dart/DartApiException.java) | `DartStatus`를 가진 런타임 예외 |
| `CorpCodeXmlParser` | 고유번호 zip 안의 XML과 오류 XML 파싱 (세부 구현은 이 문서에서 추적하지 않음) |
| `DartCorpCode` | 고유번호 파일 한 줄. `isListed()` = 종목코드 있음 |
| `DartCompanyOverview` | `company.json` 응답 (시장 구분 `corp_cls`, 업종 `induty_code`, 결산월 `acc_mt` 등) |
| `DartDisclosurePage`, `DartDisclosure` | `list.json` 한 페이지와 공시 한 건. `hasNextPage()` = `page_no < total_page` |
| `DartKeyAccountResponse`, `DartKeyAccount` | `fnlttMultiAcnt.json` 응답과 계정 한 줄. 금액은 문자열 그대로 |

### 4.3 `ingest.checkpoint`

| 클래스 | 책임 |
|---|---|
| [IngestCheckpoint](../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpoint.java) | (소스, 대상 키)별 마지막 결과: 원천 버전, `SUCCESS`/`NO_DATA`/`ERROR`, 연속 오류 횟수, 마지막 시도·성공 시각, **`nextCheckAt`**(선택, D-43) |
| [IngestCheckpointRepository](../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpointRepository.java) | `findAllBySource(source)` → `Map<대상 키, 체크포인트>`. `record(...)` = `insert ... on conflict do update`(6개 인자 오버로드는 `nextCheckAt=null`로 위임). ERROR면 `attempt_count + 1`, 아니면 0. 성공 시각은 SUCCESS일 때만 갱신. `next_check_at`은 넘긴 값으로 그대로 덮어쓴다(null이면 지운다) |

작업별 체크포인트 사용

| 소스 | 대상 키 예 | 원천 버전 | 쓰는 곳 |
|---|---|---|---|
| `DART_COMPANY` | `00126380` (고유번호) | 고유번호 파일의 변경일 | `CompanySyncJob` |
| `DART_DISCLOSURE` | `2026-08-14:A` (날짜:유형) | 없음(null) | `DisclosureSyncJob` |
| `DART_FINANCIAL` | `00126380:2026:11012` | 처리한 계기 공시번호 중 최댓값(초기 적재는 null) | `FinancialSyncJob` |
| `SIGNAL_FINANCIAL` | `123` (기업 ID) | `fin-2:<재무 마지막 변경 시각>`. `nextCheckAt`을 씀(D-43): "최신 재무 미확인"이 아직 아니면 시간만으로 활성이 되는 날, 이미 활성이면 null | `FinancialSignalJob` |

`FinancialSignalJob`의 대상 판정은 원천 버전이 바뀐 기업뿐 아니라 **`nextCheckAt`이 지난 기업**도 포함한다(재무 변경 없이도 시간만으로 재판정, D-43). 이미 불러온 체크포인트 맵으로만 판단하므로 추가 조회는 없다.

### 4.4 `ingest.company` / `ingest.disclosure` / `ingest.financial` — 수집 Job

각 소스마다 세 클래스가 한 벌이다.

| 역할 | 기업 목록 | 공시 목록 | 재무 |
|---|---|---|---|
| 로직 (`@Component`) | [CompanySyncJob](../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java) | [DisclosureSyncJob](../src/main/java/org/stockinsight/ingest/disclosure/DisclosureSyncJob.java) | [FinancialSyncJob](../src/main/java/org/stockinsight/ingest/financial/FinancialSyncJob.java) |
| 실행 시점 (`@Component`, package-private) | `CompanySyncScheduler` | `DisclosureSyncScheduler` (전체 + 장중 두 개의 `@Scheduled`) | `FinancialSyncScheduler` |
| 설정 (`@ConfigurationProperties` 레코드) | `CompanySyncProperties` | `DisclosureSyncProperties` | `FinancialSyncProperties` |
| 작업명 / 체크포인트 소스 | `company-sync` / `DART_COMPANY` | `disclosure-sync` / `DART_DISCLOSURE` | `financial-sync` / `DART_FINANCIAL` |

Scheduler는 Job의 `run()`(공시는 `runToday()`도)을 부르기만 한다. 로직은 모두 Job에 있다.

### 4.5 `company` — 기업·종목 마스터 (JPA)

| 클래스 | 책임 |
|---|---|
| [CompanyService](../src/main/java/org/stockinsight/company/CompanyService.java) | 기업 데이터를 바꾸는 유일한 창구(클래스 수준 `@Transactional`). `upsertListed(ListedCompany)`: 사명이 바뀌면 이전 이름을 별칭으로 남기고, 범위를 판정해 ACTIVE/EXCLUDED로 저장하고, 보통주 `Security`를 upsert한다. `markDelistedExcept(codes, date)`: 목록에 없는 기업을 DELISTED로. 조회: `activeCompanyIdsByDartCorpCode()`, `activeFiscalMonthsByCompanyId()`, `countListed()` 등. 입력 DTO `ListedCompany` |
| [ListingScopePolicy](../src/main/java/org/stockinsight/company/ListingScopePolicy.java) | 대상 범위 판정. KONEX → 제외, KOSPI·KOSDAQ 외 → `OTHER_MARKET`, 이름에 "스팩"·"기업인수목적" → `SPAC`, "리츠"·"부동산투자회사" → `REIT` |
| [Company](../src/main/java/org/stockinsight/company/Company.java) | JPA 엔티티(`company`). 변경 메서드(`updateProfile`, `changeStatus`)는 package-private이라 `CompanyService`만 부른다. `updateProfile`이 초성(`KoreanInitials.of`)도 갱신 |
| [Security](../src/main/java/org/stockinsight/company/Security.java) | JPA 엔티티(`security`), `@ManyToOne(LAZY) Company`. 현재는 보통주(`COMMON`), 통화 `KRW` 고정 생성 |
| [CompanyAlias](../src/main/java/org/stockinsight/company/CompanyAlias.java) | JPA 엔티티(`company_alias`). 종류 `PREVIOUS_NAME`, `MANUAL`(추가 경로 없음) |
| [CompanyRepositories](../src/main/java/org/stockinsight/company/CompanyRepositories.java) | package-private Spring Data JPA 인터페이스 3개 (`CompanyRepository`, `SecurityRepository`, `CompanyAliasRepository`) |
| `CompanyStatus` | `ACTIVE`, `EXCLUDED`, `DELISTED` |
| `ExclusionReason` | 제외 사유 (`KONEX`, `SPAC`, `REIT`, `OTHER_MARKET`) |
| `Market` | `KOSPI`, `KOSDAQ`, `KONEX`, `OTHER`. `fromDartCorpCls("Y"/"K"/"N"/그 외)` |
| `ShareType` | `COMMON`, `PREFERRED`. 현재 저장 경로는 `COMMON`만 만든다 |
| `KoreanInitials` | 검색용 초성 문자열 생성 (예: 삼성전자 → ㅅㅅㅈㅈ, `CompanySyncJobTest`에서 확인) |

### 4.6 `disclosure` — 공시 (JdbcClient)

| 클래스 | 책임 |
|---|---|
| [DisclosureService](../src/main/java/org/stockinsight/disclosure/DisclosureService.java) | 공시 저장 규칙. `saveAll(List<NewDisclosure>)`: 공시번호 upsert 후 해당 기업들의 정정 공시를 원 공시에 다시 연결. `latestPeriodicTriggers()`: 재무 수집 계기. 입력 DTO `NewDisclosure`(공시번호 14자리 검증), 결과 `SaveResult` |
| [DisclosureRepository](../src/main/java/org/stockinsight/disclosure/DisclosureRepository.java) | package-private. `upsert`(값이 같으면 갱신하지 않음, `xmax = 0`으로 신규/갱신 구분), `relinkAmendments`(SQL 한 번으로 원 공시 연결), `latestPeriodicByCompanyAndBaseName`(기업·기본 보고서명별 최대 공시번호) |
| [ReportName](../src/main/java/org/stockinsight/disclosure/ReportName.java) | 보고서명 해석: 공백 정리, 기타정보(공백 두 칸 이상 뒤) 제거, `[기재정정]` 같은 앞 표시 분리 → `baseName`, `amendmentLabel` |
| `DisclosureType` | `PERIODIC`(A), `MAJOR_EVENT`(B), `EXCHANGE`(I). `dartCode()`로 조회 코드 |
| `Disclosure` | 조회용 레코드 (원 공시 공시번호 포함) |
| `PeriodicTrigger` | (기업 ID, 기본 보고서명, 최대 공시번호). `FinancialSyncJob`의 입력 |

### 4.7 `financial` — 재무 저장과 읽을 때 해석 (JdbcClient)

저장 쪽과 해석 쪽이 한 패키지에 있다. 저장은 원천 그대로(D-32), 해석은 읽을 때 한다.

| 클래스 | 쪽 | 책임 |
|---|---|---|
| [FinancialService](../src/main/java/org/stockinsight/financial/FinancialService.java) | 저장 | `replace(companyId, bsnsYear, reportCode, rows, expectedPeriodEndMonth)`: 손익 행의 `thstrm_dt`로 기간 식별, 계기 기간 검증, 연결/별도별로 내용이 같으면 그대로 두고 다르면 삭제 후 삽입, 응답에서 빠진 `fs_div` 삭제. `lastChangedByCompanyId()`: 신호 재계산 대상 판단용. `currentReceiptNo(companyId, periodEnd, fsDiv)`: 계정 행을 읽지 않고 `financial_report`만 조회(D-42 무효화 판정용, 재무 요약 전체를 다시 만들지 않는다) |
| [PeriodicReportName](../src/main/java/org/stockinsight/financial/PeriodicReportName.java) | 저장 | "사업/반기/분기보고서 (YYYY.MM)" → `QueryKey(bsnsYear, periodType, periodEndMonth)`. 분기보고서는 결산월 기준 +3개월이면 Q1, +9개월이면 Q3. 그 외 정기공시는 빈 값 |
| `FinancialAmounts` | 저장 | 금액 문자열 파싱 (쉼표, 음수, `"-"`·빈 값 → null) |
| `RawAccountLine` | 저장 | 수집기가 넘기는 계정 한 줄(문자열 그대로). `ingest`의 DTO에 `financial`이 의존하지 않도록 둔 경계 타입 |
| `StoredFinancialLine`, `StoredFinancialReport` | 공용 | 저장된 계정 행(금액 `BigDecimal`)과 보고서 한 벌(행 포함) |
| [FinancialRepository](../src/main/java/org/stockinsight/financial/FinancialRepository.java) | 공용 | package-private. 보고서·행 insert/delete/find, `findAllByCompany`(보고서+행 조인 한 번), `lastChangedByCompanyId`, `findReceiptNoByPeriodEnd`(가벼운 단일 조회, 행 없음) |
| `PeriodType` | 공용 | `Q1`(11013), `H1`(11012), `Q3`(11014), `FY`(11011). `fromReportCode`, `reportCode()` |
| `FinancialPeriodException`, `PeriodicReportNameException` | 저장 | 기간 식별 실패·기간 불일치, 보고서명 해석 실패 |
| [FinancialSummaryService](../src/main/java/org/stockinsight/financial/FinancialSummaryService.java) | 해석 | `summarize(companyId)` → `FinancialSummary`. 최신 기간의 연결/별도·통화로 기준 고정(D-37), 최근 12분기(1·2·3분기 실제 + 4분기 파생) + 최근 3개 사업연도, 재무상태표 항등식 점검, 데이터 이상 플래그. 저장하지 않는다 |
| [AccountMapper](../src/main/java/org/stockinsight/financial/AccountMapper.java) | 해석 | package-private. 계정명 → 지표(매출액, 영업이익 두 이름, 당기순이익 작은 `ord`, 자산·부채·자본총계, 자본금). 유동자산 유무로 `GENERAL`/`FINANCIAL` 판별 |
| `FinancialSummary`, `QuarterEntry`, `AnnualEntry`, `MetricValue`, `PeriodKey`, `SummaryFlag`, `FinancialFormat` | 해석 | 요약 결과 구조. `PeriodKey`가 신호 근거 키 형식(`flowBasisKey`, `stateBasisKey`, `debtJumpBasisKey`)과 표시 키를 만든다 |

### 4.8 `signal` — 재무 신호

| 클래스 | 책임 |
|---|---|
| [FinancialSignalJob](../src/main/java/org/stockinsight/signal/FinancialSignalJob.java) | 재무가 바뀐 기업 + `nextCheckAt`이 지난 기업(D-43)만 골라 기업 하나당 트랜잭션 하나로 요약 → 계산 → 반영. 반영 직후 그 기업의 최신 기간 근거 키(`stateBasisKey()`, 재무가 없으면 null)를 `applyFinancial`에 넘긴다. 철회 급증 경고 |
| `FinancialSignalScheduler`, `FinancialSignalProperties` | 실행 시점, `app.signal.financial-signal.*` |
| [FinancialSignalCalculator](../src/main/java/org/stockinsight/signal/FinancialSignalCalculator.java) | `calculate(FinancialSummary, asOf)` → `CalculationResult(drafts, staleRecheckAt)`. static 순수 함수. DB·Spring 없음. `assessStale(latest, asOf)`(package-private) → `StaleAssessment(stale, deadline, nextRecheckDate)`: 다음 기간 종료월 말일(`YearMonth...atEndOfMonth()`) + 기한일(60·120일) + 유예 7일로 판정한다(D-43). 이미 미확인이면 `nextRecheckDate=null`(그다음엔 규칙 버전·재무 변경만이 계기) |
| [FinancialRuleCatalog](../src/main/java/org/stockinsight/signal/FinancialRuleCatalog.java) | 문턱값·심각도 구간 상수와 `RULE_VERSION = "fin-2"`. `QUARTERLY_DEADLINE_DAYS=60`, `ANNUAL_DEADLINE_DAYS=120`, `STALE_GRACE_DAYS=7`은 서비스 내부 데이터 품질 판정 기준이며 실제 법정 제출기한이 아니다(D-43). 값을 바꾸면 버전을 올리고, 버전이 바뀌면 모든 기업이 다시 판정된다 |
| [CompanySignalService](../src/main/java/org/stockinsight/signal/CompanySignalService.java) | 신호 저장 규칙. `applyFinancial(companyId, drafts, ruleVersion, latestPeriodStateBasisKey)`: 초안을 자연키로 upsert(ACTIVE/PAST). 이번 초안에 없는 기존 신호는 WITHDRAWN이 기본이지만, `FIN_DATA_STALE`이고 그 근거 키가 `latestPeriodStateBasisKey`보다 앞선 기간이면(문자열 비교, `fiscalYearStart:Qn` 형식이라 사전식 비교가 시간순과 같다) PAST로 둔다(해소는 철회가 아니다, D-43). 행은 지우지 않는다(D-36) |
| [CompanySignalRepository](../src/main/java/org/stockinsight/signal/CompanySignalRepository.java) | package-private. upsert(상태가 바뀔 때만 `status_changed_at` 갱신), withdraw, `markPast`(D-43 해소 전용, 상태가 이미 PAST가 아닐 때만 `status_changed_at` 갱신), 조회. `calc_values`·`watch_metrics`는 자체 `JsonMapper`로 JSON 문자열을 만들어 `jsonb`로 캐스팅 |
| `SignalDraft` | 계산 결과(저장 전). 대리키 없음 |
| `CompanySignal` | 조회용 레코드 |
| `SignalType` | 변화·상태 6종(`FIN_REVENUE_CHANGE`, `FIN_OPERATING_MARGIN_CHANGE`, `FIN_OPERATING_TURN`, `FIN_DEBT_RATIO_JUMP`, `FIN_OPERATING_LOSS_STREAK`, `FIN_CAPITAL_IMPAIRMENT`) + 데이터 한계 7종(`FIN_DATA_*`) |
| `SignalNature`, `SignalDirection`, `SignalSeverity`, `SignalStatus` | 상태/변화, 긍정/부정/불확실, 낮음/중간/높음, 활성/이력/철회 |

### 4.9 `analysis` — AI 재무 쉬운 설명

첫 AI 기능이다(financial_explain 하나뿐). 입력은 코드가 만든 사실표·신호 참조뿐이고 원천 행·공시 원문·내부 ID·철회 신호는 넣지 않는다(D-38). 숫자·기간·신호는 AI 글에서 `{fin.*}`/`{per.*}`/`{sig.*}` 토큰으로만 나온다(D-39). 지문은 값이 아니라 식별자 집합의 해시다(D-40, D-08).

| 클래스 | 책임 |
|---|---|
| [FinancialExplainInputBuilder](../src/main/java/org/stockinsight/analysis/FinancialExplainInputBuilder.java) | `build(companyId)` → `Optional<BuildResult>`(AI 입력 + 값 스냅샷 + 지문). 재무 보고서가 없으면 빈 값. `FinancialSummaryService.summarize()` + `CompanySignalService.findByCompany()`로 최신 손익·연간·재무상태·흐름 사실을 만들고, 활성 신호 전부 + 이력 신호 최대 4개를 심각도·최근성 순으로 골라 기간·주제로 묶는다(ai-analysis.md §4.4.2). 재무상태표가 불일치(`FIN_DATA_INCONSISTENT` 활성)하면 재무상태 지표 전체를 `unavailable`로 두고 `structure` 섹션을 뺀다. `history` 섹션은 이력(PAST) 신호가 있을 때만 연다 — 흐름 사실(`revenue_yoy_run`·`operating_loss_run`)은 최신 기간까지 거꾸로 센 "지금 이어지는" 값이라 그 자체로는 `history`를 열지 않는다(`sales_profit`에서만 쓴다, D-45). 같은 데이터면 바이트 단위로 같은 JSON |
| [PeriodLabels](../src/main/java/org/stockinsight/analysis/PeriodLabels.java) | package-private. 기간 라벨(12월 결산 "2026년 2분기" / 그 밖 "2026.04~06(1분기)")과 값 표시 형식(비율은 부호 있는 소수 1자리 %, 개수는 정수). 금액은 통화와 관계없이 한국어 수 단위(조·억·만, 1만 미만은 콤마 정수) + 통화명(KRW=원, CNY=위안, USD=달러, JPY=엔, GBP=파운드, 그 밖은 " "+코드)이고 환산하지 않는다(D-41). 억·조 단위는 반올림 결과가 다음 단위의 경계(10000)에 닿으면 그 단위로 다시 계산해 올린다(예: 9,999.95억 → 1.0조) |
| [Fingerprint](../src/main/java/org/stockinsight/analysis/Fingerprint.java) | package-private. `compute(공시번호 집합, 신호 목록, unavailable 목록, 입력 구성 버전)` → SHA-256 해시. 정렬해 이어 붙인 문자열을 해시하므로 값 자체가 아니라 "무엇을 썼는가"가 바뀌어야 지문이 바뀐다(D-08, D-40, D-42). 데이터 한계 신호 코드(`FIN_DATA_*`) 자체가 아니라 `unavailable`(지표·사유) 집합을 쓴다 — 날짜로 바뀌는 "최신 재무 미확인" 같은 코드가 바뀌어도 지문은 그대로다 |
| [FinancialExplainInput](../src/main/java/org/stockinsight/analysis/FinancialExplainInput.java), [ValueSnapshot](../src/main/java/org/stockinsight/analysis/ValueSnapshot.java), [FinancialExplainOutput](../src/main/java/org/stockinsight/analysis/FinancialExplainOutput.java) | AI 입력 계약, 렌더링용 값 스냅샷(원값·출처 공시번호 포함, AI에는 안 감), AI 출력 계약(`overview`/`sales_profit`/`structure`/`history`, 없는 섹션은 null) |
| [FinancialExplainValidator](../src/main/java/org/stockinsight/analysis/FinancialExplainValidator.java) | `validate(output, input)` → `ValidationResult`(통과 여부 + 실패 규칙 번호 목록). ai-analysis.md §4.4.7 규칙 1~10(스키마·토큰·숫자·반복·강도어·방향 일치·이력 시제·`unavailable`·금지 표현·분량) 전부 코드 상수·정규식으로 판정. 규칙 7은 흐름 사실 토큰(`revenue_yoy_run`·`operating_loss_run`)이 `history`에 나오는 경우도 실패로 잡는다(D-45). DB·Spring 없는 순수 클래스(`new`로 직접 생성, 빈 아님) |
| [FinancialExplainPrompt](../src/main/java/org/stockinsight/analysis/FinancialExplainPrompt.java) | package-private. `classpath:prompts/`에서 스타일 가이드+역할·규칙 프롬프트, 스키마 JSON을 기동 시 한 번 읽어 상수로 들고 있는다(`PROMPT_VERSION = "fx-v1"`, `SCHEMA_VERSION = "fx-schema-1"`). `userMessage(inputJson, 실패규칙목록)`이 데이터 구분자(`<data>`)로 감싸고, 재시도 때는 실패 규칙 번호만 덧붙인다(AI 출력 원문은 되돌리지 않는다) |
| [FinancialExplainProperties](../src/main/java/org/stockinsight/analysis/FinancialExplainProperties.java) | `app.analysis.financial-explain.*` 바인딩: cron, `run-on-startup`, `golden-set-company-ids`, `daily-budget-usd`/`monthly-budget-usd`(USD, 모든 분석 종류 공유), `publish`(기본 false) |
| [FinancialExplainJob](../src/main/java/org/stockinsight/analysis/FinancialExplainJob.java) | 작업명 `analysis-financial-explain`. 대상 = `company.ai_covered` ∪ 골든셋 목록(지금은 `ai_covered`가 전부 false라 골든셋만) 순차 처리. 대상마다 입력 구성 → `AnalysisService.decide()`로 건너뜀/재시도 판단 → LLM 호출 → 검증 → 실패 시 실패 규칙만 프롬프트에 덧붙여 1회 재생성 → 저장. `publish=true`일 때만 검증 통과 결과를 `AnalysisService.publish()`로 승격한다. 예산 초과 시 남은 대상을 건너뛰고 `PARTIAL` |
| [AnalysisService](../src/main/java/org/stockinsight/analysis/AnalysisService.java) | 저장 규칙(§6.3 상태 전이)만 담당, 생성·검증은 하지 않는다. `decide()`: 같은 지문의 게시·숨김·초안이 있으면 `SKIP_UP_TO_DATE`, REJECTED/FAILED면 24시간 지나야 `PROCEED`(`SKIP_BACKOFF`), 같은 지문 누적 3회 실패 + 프롬프트 버전 그대로면 `SKIP_MAX_FAILURES`. `spentSince(시각)`: 예산 확인용 비용 합(분석 종류 무관) |
| [AnalysisRepository](../src/main/java/org/stockinsight/analysis/AnalysisRepository.java) | package-private. `insert`, `publish`(기존 게시본 내리고 새 행 올림, 두 update), `findCurrent`, `findLatestByFingerprint`, `countFailuresByFingerprint`, `sumCostSince`. `jsonb` 컬럼은 Jackson 3 `JsonMapper`로 문자열화 |
| [AnalysisRenderer](../src/main/java/org/stockinsight/analysis/AnalysisRenderer.java) | 화면 조립 전 단계(화면 자체는 없음). `render()`: 토큰 → 값 스냅샷 표시 값, HTML 이스케이프, 모르는 토큰은 예외. `isInvalidated(current, companyId)`(D-42): 입력 전체를 다시 만들지 않는다. 스냅샷이 참조한 신호의 자연키로 `CompanySignalService.findByCompany`(기업당 한 번) 조회 후 상태·방향만 비교(철회되었거나 방향이 바뀌면 무효화)하고, 참조한 사실의 (기간 종료일, 기준) 조합마다 `FinancialService.currentReceiptNo`로 현재 공시번호만 확인(다르거나 없으면 무효화)한다. 새 보고서 도착·활성→이력 전환·심각도/규칙 버전 변경만으로는 무효화하지 않는다. `limitMessages(companyId)`: 스냅샷이 아니라 현재 활성 `FIN_DATA_*` 신호로 매번 새로 만든다(헤더는 렌더링 시점 현재 값, D-42) |
| [StaticContent](../src/main/java/org/stockinsight/analysis/StaticContent.java) | package-private. 신호 유형·방향별 배지 이름, 제한 코드별 문구, AI 생성 표시·무효화 안내 문구. 전부 코드 상수(AI가 만들지 않음) |
| [GoldenSetDumpRunner](../src/main/java/org/stockinsight/analysis/GoldenSetDumpRunner.java) | `@Profile("goldenset")`인 `ApplicationRunner`. AI를 호출하지 않는다. 골든셋 목록의 §4.4.2 입력 JSON을 로컬 DB에서 만들어 `src/test/resources/golden/financial_explain/{companyId}.json`에 쓴다. **구현하지 않은 것**: 저장된 입력 파일을 다시 읽어 재생성하는 경로(implementation-plan.md §7.2의 7번) |
| `AnalysisKind`, `AnalysisStatus`, `TargetType` | `FINANCIAL_EXPLAIN`(하나뿐) / `DRAFT`·`PUBLISHED`·`REJECTED`·`FAILED`·`HIDDEN` / `COMPANY`(하나뿐) |

### 4.10 `analysis.llm` — Anthropic SDK 어댑터

| 클래스 | 책임 |
|---|---|
| [LlmClient](../src/main/java/org/stockinsight/analysis/llm/LlmClient.java) | `generate(systemPrompt, userInput, jsonSchema)` → `LlmResult`. 실패는 모두 `LlmException`(또는 그 하위 `LlmOutputRejectedException`). 테스트는 이 인터페이스를 `FakeLlmClient`로 바꾼다 |
| [AnthropicLlmClient](../src/main/java/org/stockinsight/analysis/llm/AnthropicLlmClient.java) | 실제 구현. 인증키 없으면 호출 전에 `LlmException`(네트워크 호출 없음). 원시 JSON 스키마 문자열을 고전 Jackson 2 `JsonNode`로 읽어 `JsonOutputFormat.Schema`의 property bag으로 옮긴다. 시스템 프롬프트는 프롬프트 캐싱(`CacheControlEphemeral`)을 건 `TextBlockParam` 하나로 보낸다. `StopReason.MAX_TOKENS`/`REFUSAL`이면 사용량은 챙기고 `LlmOutputRejectedException`으로 던진다(§7.3 검증 실패와 동급). 응답의 `Usage`로 비용을 계산한다(`app.llm.*-cost-per-million`). **인증키·프롬프트 전문은 예외 메시지·로그에 남기지 않는다**(예외 메시지는 SDK 예외 클래스 이름만) |
| [LlmResult](../src/main/java/org/stockinsight/analysis/llm/LlmResult.java) | `outputJson`(출력 거부 시 null), `model`, 토큰 3종, `costUsd` |
| [LlmException](../src/main/java/org/stockinsight/analysis/llm/LlmException.java), [LlmOutputRejectedException](../src/main/java/org/stockinsight/analysis/llm/LlmOutputRejectedException.java) | 호출 자체 실패(재시도 안 함, FAILED로 기록) / 호출은 됐지만 응답을 못 씀(과금된 사용량을 `partialUsage()`로 들고 있음, 검증 실패처럼 1회 재시도) |
| [LlmProperties](../src/main/java/org/stockinsight/analysis/llm/LlmProperties.java) | `app.llm.*` 바인딩(`api-key`, `model`, `max-tokens`, `timeout`, `max-retries`, 단가 3종). `toString()`은 키를 가린다 |
| [LlmConfig](../src/main/java/org/stockinsight/analysis/llm/LlmConfig.java) | `AnthropicOkHttpClient.builder()`로 `AnthropicClient` 만들고 `AnthropicLlmClient` 빈 등록. 인증키가 없어도 빌드는 되고("missing" 자리표시자), 실제 실패는 `AnthropicLlmClient.generate()`의 첫 줄에서 난다 |

### 4.11 주요 관계 요약

```
*Scheduler ──호출──▶ *Job ──▶ DartApi(DartClient)                    외부
                         ├─▶ CompanyService / DisclosureService / FinancialService   도메인 쓰기
                         ├─▶ IngestCheckpointRepository                처리 상태
                         ├─▶ PipelineRunRecorder                       실행 기록
                         └─▶ TransactionTemplate                       대상 하나 = 트랜잭션 하나

FinancialSignalJob ──▶ FinancialService.lastChangedByCompanyId()
                   ──▶ FinancialSummaryService.summarize() ──▶ FinancialRepository, AccountMapper
                   ──▶ FinancialSignalCalculator.calculate()  (순수)
                   ──▶ CompanySignalService.applyFinancial() ──▶ CompanySignalRepository

FinancialExplainJob ──▶ FinancialExplainInputBuilder.build() ──▶ FinancialSummaryService, CompanySignalService
                    ──▶ AnalysisService.decide()             ──▶ AnalysisRepository
                    ──▶ LlmClient.generate()                 ──▶ AnthropicLlmClient ──HTTPS──▶ Anthropic
                    ──▶ FinancialExplainValidator.validate()   (순수)
                    ──▶ AnalysisService.save() / .publish()  ──▶ AnalysisRepository ──▶ analysis
```

---

## 5. 애플리케이션 기동과 Bean 구성

### 5.1 실행 방법별 프로필

| 실행 | 활성 프로필 | 근거 |
|---|---|---|
| `./gradlew bootRun` | `local` (환경 변수 `SPRING_PROFILES_ACTIVE`가 있으면 그 값) | build.gradle의 `bootRun` 설정 |
| 운영 | `prod` | application-prod.yml. 운영 기동 방식(이미지·compose)은 아직 코드에 없다 [설계만] |
| `./gradlew test` | 없음 (application.yml만) | 데이터소스는 Testcontainers가 제공 |
| IDE에서 `main()` 직접 실행 | 실행 설정에 따름 | application.yml에는 데이터소스 설정이 없으므로 `local` 프로필을 지정해야 한다 [미확인: 프로필 없이 실행한 결과는 확인하지 않음] |

### 5.2 기동 순서

`StockInsightApplication.main()` → `SpringApplication.run()` 이후의 흐름이다. 1~3, 7~9는 Spring Boot 표준 동작이고, 이 프로젝트에서 무엇이 연결되는지를 함께 적었다.

1. **설정 읽기.** `application.yml` → `application-{profile}.yml`. `local`이면 `spring.config.import: optional:file:${STOCKINSIGHT_SECRETS:${user.home}/.stockinsight/secrets.yml}`로 저장소 밖 비밀값 파일을 읽는다(없어도 기동). `prod`는 `optional`이 없어 파일이 없으면 기동에 실패한다. 같은 이름의 환경 변수는 파일보다 우선한다(README). `app.dart.api-key: ${DART_API_KEY:}`가 이 값을 받는다.
2. **`@ConfigurationProperties` 바인딩.** `@ConfigurationPropertiesScan`이 `org.stockinsight` 아래 레코드 7개를 등록한다: `DartProperties`(`app.dart`), `CompanySyncProperties`, `DisclosureSyncProperties`, `FinancialSyncProperties`(`app.ingest.*`), `FinancialSignalProperties`(`app.signal.financial-signal`), `FinancialExplainProperties`(`app.analysis.financial-explain`), `LlmProperties`(`app.llm`). `Duration`(`200ms`, `30d`)과 `Period`(`3y`) 변환은 Spring Boot가 한다.
3. **DataSource.** `spring.datasource.*` (local: compose DB, prod: `DB_URL` 등 환경 변수, test: `@ServiceConnection` 컨테이너).
4. **Flyway.** `classpath:db/migration`의 V1~V6를 적용한다(V5 = `analysis` 테이블, V6 = `ingest_checkpoint.next_check_at`). 스키마는 Flyway만 관리한다.
5. **JPA/Hibernate.** `ddl-auto: validate` — `Company`, `Security`, `CompanyAlias` 매핑이 Flyway가 만든 스키마와 맞지 않으면 기동에 실패한다. `hibernate.jdbc.time_zone: UTC`, `open-in-view: false`. 컬럼명은 Spring Boot 기본 명명 전략(camelCase → snake_case)으로 매핑된다(엔티티에 `@Column`이 없다).
6. **Bean 생성.** §5.3 표.
7. **스케줄링.** `app.scheduler.enabled`가 true일 때만 `SchedulingConfig`가 로드되어 `@EnableScheduling`이 켜진다. Scheduler 빈 자체는 항상 만들어지지만, 꺼져 있으면 `@Scheduled`가 처리되지 않아 cron이 등록되지 않는다. 기본값 false, `prod`는 true.
8. **웹 서버.** webmvc 스타터로 내장 서블릿 컨테이너가 뜬다. 포트 설정이 없어 기본 8080이다. 노출된 엔드포인트는 `/actuator/health`뿐이다(`management.endpoints.web.exposure.include: health`). 애플리케이션 컨트롤러는 없다.
9. **`ApplicationReadyEvent`.** 다섯 Scheduler(`CompanySyncScheduler`·`DisclosureSyncScheduler`·`FinancialSyncScheduler`·`FinancialSignalScheduler`·`FinancialExplainScheduler`)의 `runOnStartup()`이 각각 설정(`run-on-startup`)을 보고, true면 **가상 스레드 하나를 새로 띄워** `job::run`을 실행한다. 이 경로는 `app.scheduler.enabled`와 무관하게 동작한다. 여러 개를 켜면 동시에 시작된다(§11.4). `goldenset` 프로필을 함께 켰으면 `GoldenSetDumpRunner`(`ApplicationRunner`)도 이 시점에 한 번 돈다(§4.9).

### 5.3 Bean 구성

| Bean | 등록 방법 | 의존성 (생성자 주입) |
|---|---|---|
| `Clock` | `TimeConfig.clock()` `@Bean` | — |
| `SchedulingConfig` | `@Configuration` + 조건 | — |
| `PipelineRunRecorder` | `@Component` | `JdbcClient`, `Clock` |
| `IngestCheckpointRepository` | `@Repository` | `JdbcClient` |
| `DartClient` (타입 `DartApi`로 주입됨) | `DartConfig.dartClient()` `@Bean` | `DartProperties` |
| `CompanyService` | `@Service` | `CompanyRepository`, `SecurityRepository`, `CompanyAliasRepository`, `ListingScopePolicy`, `Clock` |
| `CompanyRepository` 등 3개 | Spring Data JPA 자동 등록 | — |
| `ListingScopePolicy` | `@Component` | — |
| `DisclosureService` | `@Service` | `DisclosureRepository`, `Clock` |
| `DisclosureRepository` | `@Repository` | `JdbcClient` |
| `FinancialService` | `@Service` | `FinancialRepository`, `Clock` |
| `FinancialSummaryService` | `@Service` | `FinancialRepository` |
| `FinancialRepository` | `@Repository` | `JdbcClient` |
| `CompanySignalService` | `@Service` | `CompanySignalRepository`, `Clock` |
| `CompanySignalRepository` | `@Repository` | `JdbcClient` |
| `CompanySyncJob` | `@Component` | `DartApi`, `CompanyService`, `IngestCheckpointRepository`, `PipelineRunRecorder`, `CompanySyncProperties`, `TransactionTemplate`, `Clock` |
| `DisclosureSyncJob` | `@Component` | `DartApi`, `CompanyService`, `DisclosureService`, `IngestCheckpointRepository`, `PipelineRunRecorder`, `DisclosureSyncProperties`, `TransactionTemplate`, `Clock` |
| `FinancialSyncJob` | `@Component` | `DartApi`, `CompanyService`, `DisclosureService`, `FinancialService`, `IngestCheckpointRepository`, `PipelineRunRecorder`, `FinancialSyncProperties`, `TransactionTemplate`, `Clock` |
| `FinancialSignalJob` | `@Component` | `FinancialSummaryService`, `CompanySignalService`, `IngestCheckpointRepository`, `PipelineRunRecorder`, `FinancialService`, `TransactionTemplate`, `Clock` |
| `*Scheduler` 5개 | `@Component` (package-private) | 해당 Job, 해당 Properties |
| `AnthropicClient` | `LlmConfig.llmClient()`가 내부에서 `AnthropicOkHttpClient.builder()`로 만듦(별도 빈 아님) | `LlmProperties` |
| `LlmClient` (타입, 구현은 `AnthropicLlmClient`) | `LlmConfig.llmClient()` `@Bean` | `LlmProperties` |
| `FinancialExplainInputBuilder` | `@Service` | `CompanyService`, `FinancialSummaryService`, `CompanySignalService` |
| `AnalysisService` | `@Service` | `AnalysisRepository` |
| `AnalysisRepository` | `@Repository` | `JdbcClient` |
| `AnalysisRenderer` | `@Service` | `FinancialExplainInputBuilder` |
| `FinancialExplainJob` | `@Component` | `CompanyService`, `FinancialExplainInputBuilder`, `AnalysisService`, `FinancialExplainProperties`, `LlmClient`, `PipelineRunRecorder`, `Clock`. `FinancialExplainValidator`는 빈이 아니라 필드에서 직접 `new`(순수 클래스, §4.9) |
| `GoldenSetDumpRunner` | `@Component` `@Profile("goldenset")` | `FinancialExplainInputBuilder`, `FinancialExplainProperties` |
| `JdbcClient`, `TransactionTemplate`, 트랜잭션 매니저 | Spring Boot 자동 구성 | 코드에서 직접 정의하지 않음. data-jpa가 있으므로 트랜잭션 매니저는 JPA 트랜잭션 매니저이고, 같은 DataSource를 쓰는 `JdbcClient`도 같은 트랜잭션에 참여한다 (Spring 표준 동작) |

주입은 모두 생성자 주입이고, 생성자는 package-private이다(같은 패키지나 Spring만 생성). `@Autowired` 필드 주입은 테스트에만 있다.

---

## 6. 실행 경로

| 경로 | 트리거 | 호출 | 조건 |
|---|---|---|---|
| 정기 스케줄 | `@Scheduled(cron = "${...cron}", zone = "Asia/Seoul")` | `CompanySyncScheduler.scheduled()` → `CompanySyncJob.run()` 등 | `app.scheduler.enabled=true` |
| 장중 공시 | `DisclosureSyncScheduler.intraday()` (`intraday-cron`) | `DisclosureSyncJob.runToday()` | 같음 |
| 기동 직후 1회 | `ApplicationReadyEvent` → `runOnStartup()` | 새 가상 스레드에서 `job.run()` | 해당 `run-on-startup=true` |
| 기동 직후 1회(개발용) | `ApplicationReadyEvent` → `GoldenSetDumpRunner.run()` | 골든셋 입력 JSON을 파일로 씀, AI 호출 없음 | `--spring.profiles.active=...,goldenset` |
| HTTP | `GET /actuator/health` | Actuator | 항상. 애플리케이션 HTTP 경로는 없음 |
| 테스트 | `@SpringBootTest`에서 `job.run()` 직접 호출, 또는 static 메서드 직접 호출 | §14 | — |

정기 스케줄 시각 (KST, application.yml 기본값)

| 시각 | 작업 | 전제 |
|---|---|---|
| 매일 05:00 | `company-sync` | — |
| 매일 06:00 | `disclosure-sync` 전체 | ACTIVE 기업이 있어야 함 |
| 평일 08:00~19:30, 30분마다 | `disclosure-sync` 당일분 | 같음 |
| 매일 06:30 | `financial-sync` | ACTIVE 기업, 계기용 `disclosure` |
| 매일 07:00 | `financial-signal` | `financial_report` 데이터 |
| 매일 07:30 | `analysis-financial-explain` | `company_signal` 계산 완료. 대상이 비어 있으면(골든셋 미설정 + `ai_covered` 전부 false) 즉시 `SUCCEEDED`로 끝난다 |

작업 사이의 순서는 **시각으로만** 맞춘다. 앞 작업의 완료를 기다리는 코드는 없다. 앞 작업이 늦게 끝나도 다음 작업은 그 시점 DB 상태로 돈다.

---

## 7. 실행 흐름 추적

모든 Job은 같은 골격을 가진다. 먼저 골격을 보고, 작업별로 끝까지 따라간다.

### 7.0 공통 골격 (`run()`)

[CompanySyncJob.run()](../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java:70) 기준. 나머지 Job도 같다.

```
run()
 ├─ running.compareAndSet(false, true)   실패하면 "이미 실행 중" → Result.skipped() (pipeline_run 기록 없음)
 ├─ runId = runRecorder.start(JOB_NAME)  남은 RUNNING → FAILED 정리, 새 RUNNING 행
 ├─ try   result = sync(progress)
 │        runRecorder.finish(runId, SUCCEEDED | PARTIAL, ...)
 ├─ catch DartApiException               실행 중단 대상 오류 → FAILED "중단: ..."   (FinancialSignalJob에는 없음)
 ├─ catch RuntimeException               그 밖의 오류 → FAILED "오류: ..."
 └─ finally running.set(false)
```

`sync()` 안에서는 공통적으로
1. 체크포인트를 읽어 처리할 대상을 우선순위대로 만든다.
2. 호출 상한(`max-calls-per-run`)에 닿을 때까지 대상 하나씩 처리한다.
3. 외부 호출은 트랜잭션 밖에서, DB 반영 + 체크포인트 SUCCESS/NO_DATA는 `TransactionTemplate` 안에서 한다.
4. 대상 하나의 오류는 체크포인트 ERROR로 남기고 다음 대상으로 넘어간다. `stopsRun()`인 `DartApiException`만 위로 던져 실행을 멈춘다.
5. 남은 대상이 있으면 `PARTIAL`, 없으면 `SUCCEEDED`. 요약 문자열이 `pipeline_run.message`가 된다.

### 7.1 기업 목록 동기화 (`company-sync`)

```
CompanySyncScheduler.scheduled()                      매일 05:00
└─ CompanySyncJob.run()                               :70
   └─ sync(progress)                                  :97
      ├─ dart.fetchCorpCodes()                        호출 1회 (calls++)
      │   └─ DartClient.get("/corpCode.xml")          zip이 아니면 오류 XML로 보고 예외
      │      └─ CorpCodeXmlParser.parseCorpCodes()    → List<DartCorpCode>
      ├─ filter(DartCorpCode::isListed)               종목코드 있는 기업만
      ├─ checkListedCount(size)                       :177  < min-listed-count 또는 기존 대비 max-delisted-ratio 넘게 감소 → IllegalStateException → FAILED
      ├─ companyService.markDelistedExcept(codes, today)   별도 트랜잭션(@Transactional). 목록에 없는 기업 DELISTED, security.delisted_on = 오늘(감지일)
      ├─ dueForOverview(listed, checkpoints)           :151  우선순위: 체크포인트 없음(4) > 변경일 바뀜(3) > 직전 ERROR(2) > refresh-after 경과(1). 0은 제외
      └─ for corp in due (calls < max-calls-per-run)
         └─ syncOne(corp)                             :122
            ├─ dart.fetchCompany(corpCode)            트랜잭션 밖. 013 → Optional.empty()
            ├─ transaction {
            │    있으면 companyService.upsertListed(toListedCompany(corp, overview))
            │         ├─ findByDartCorpCode 또는 new Company
            │         ├─ 이름이 바뀌었으면 CompanyAlias(PREVIOUS_NAME) 추가
            │         ├─ company.updateProfile(...)  (초성 갱신)
            │         ├─ ListingScopePolicy.classify(market, name, legalName) → ACTIVE/EXCLUDED + 사유
            │         └─ Security(COMMON) upsert: ticker, market, delisted_on = null
            │    checkpoints.record(DART_COMPANY, corpCode, modifyDate, SUCCESS | NO_DATA)
            │  }
            └─ catch: stopsRun → throw / 그 밖 → checkpoints.record(ERROR) (트랜잭션 밖)
```

`toListedCompany()`의 값 선택: 종목코드는 기업개황 → 고유번호 파일 순, 이름은 `stock_name` → `corp_name` → 고유번호 파일 순, 결산월은 1~12만 인정.

### 7.2 공시 목록 수집 (`disclosure-sync`)

```
DisclosureSyncScheduler.scheduled()  06:00  → DisclosureSyncJob.run()      = execute(false)
DisclosureSyncScheduler.intraday()   장중   → DisclosureSyncJob.runToday() = execute(true)
└─ execute(todayOnly)                                   :82  (run/runToday가 running 플래그 하나를 공유)
   └─ sync(todayOnly)                                   :109
      ├─ companyService.activeCompanyIdsByDartCorpCode() 비어 있으면 IllegalStateException (빈 날짜가 확정되는 것 방지)
      ├─ dueUnits(today, todayOnly, checkpoints)        :180
      │    TODAY: 오늘 × 3유형 (항상)
      │    todayOnly가 아니면 어제부터 initial-load-period 전까지 거슬러 가며
      │      체크포인트 없음 → BACKFILL,  확정 아님 → CATCH_UP
      │      확정 = ERROR가 아니고 마지막 시도 날짜(KST)가 그 날짜보다 뒤 (isSettled :207)
      │    순서: TODAY → CATCH_UP → BACKFILL (각각 최근 날짜부터)
      └─ for unit in due
         └─ syncUnit(unit)                              :134
            ├─ do { dart.fetchDisclosures(date, type, page++) } while (page.hasNextPage())
            │    페이지마다 calls++. 도중에 상한에 닿으면 기록 없이 false → 루프 종료(그 단위는 다음 실행에서 다시)
            │    received: LinkedHashMap<공시번호, DartDisclosure> (페이지 밀림 중복 제거)
            ├─ ACTIVE 기업 공시만 → NewDisclosure 로 변환 (접수일 BASIC_ISO_DATE 파싱)
            ├─ transaction {
            │    disclosureService.saveAll(targets)
            │      ├─ 각 공시: ReportName.parse(reportName) → DisclosureRepository.upsert()   INSERTED/UPDATED/UNCHANGED
            │      └─ relinkAmendments(해당 기업들)   정정 공시 → 같은 기업·같은 base_report_name의 앞선 최초 제출 중 가장 최근
            │    checkpoints.record(DART_DISCLOSURE, "날짜:유형", null, 받은 게 없으면 NO_DATA 아니면 SUCCESS)
            │  }
            └─ catch: stopsRun → throw / 그 밖 → record(ERROR)
```

### 7.3 재무 수집 (`financial-sync`)

```
FinancialSyncScheduler.scheduled()  06:30
└─ FinancialSyncJob.run()                              :88
   └─ sync()                                           :115
      ├─ companyService.activeCompanyIdsByDartCorpCode()   비어 있으면 IllegalStateException
      ├─ companyService.activeFiscalMonthsByCompanyId()
      ├─ minBsnsYear = 올해 − initial-load-years
      ├─ resolveTriggers(disclosureService.latestPeriodicTriggers(), ...)       :153
      │    PeriodicTrigger(기업, 기본 보고서명, 최대 공시번호)마다
      │      ACTIVE 아님 → 건너뜀
      │      PeriodicReportName.resolve(baseName, 결산월) → QueryKey    해석 실패 → resolutionErrors++, 경고 로그
      │      정기보고서 아님 또는 bsnsYear < minBsnsYear → 건너뜀
      │      → Map<TargetKey(고유번호, bsnsYear, reportCode), TriggerInfo(공시번호, 기간 종료월)>  같은 키면 큰 공시번호
      ├─ dueCatchUpUnits(triggers, checkpoints)          :185
      │    체크포인트 ERROR 전부 + (체크포인트가 있고 원천 버전이 null이거나 계기 공시번호가 더 큰 것)
      ├─ dueBackfillUnits(...)                           :205
      │    올해 ~ minBsnsYear × 4개 보고서 × ACTIVE 기업 중 체크포인트 없는 것
      ├─ groupIntoUnits(): (bsnsYear, reportCode)별로 기업 100개씩 묶어 Unit    :221
      │    처리 순서: CATCH_UP 묶음 전부 → BACKFILL 묶음. 각각 연도 내림차순
      └─ for unit in due (calls < max-calls-per-run)
         └─ processUnit(unit)                            :247   호출 1회 = 묶음 1개
            ├─ dart.fetchKeyAccounts(corpCodes, bsnsYear, reportCode)
            │    stopsRun → throw / 그 밖 → 묶음의 모든 기업 ERROR 기록 후 return
            ├─ 응답을 corp_code별로 그룹
            └─ for corpCode in unit
               └─ processCompany(key, companyId, rows, trigger)        :274
                  ├─ rows 비어 있음 → transaction { record(NO_DATA, 원천 버전 = 계기 공시번호) }
                  ├─ DartKeyAccount → RawAccountLine
                  └─ transaction {
                       financialService.replace(companyId, bsnsYear, reportCode, raw, 계기의 기간 종료월)   :49
                         ├─ identifyPeriod(): 손익(IS) 행 thstrm_dt "YYYY.MM.DD ~ YYYY.MM.DD" → 회계연도 시작일, 기간 종료일
                         ├─ 계기가 있고 종료월이 다르면 FinancialPeriodException (저장 안 함)
                         ├─ 응답에 없는 fs_div의 기존 보고서 삭제 (financial_line은 on delete cascade)
                         └─ fs_div별: 기간·통화·공시번호·모든 행이 같으면 그대로(unchanged)
                                      다르면 기존 삭제 → insertReport → insertLines(행마다 insert)
                       record(SUCCESS, 원천 버전 = 계기 공시번호 또는 null)
                     }
                     catch FinancialPeriodException / RuntimeException → record(ERROR)
```

### 7.4 재무 신호 계산 (`financial-signal`)

```
FinancialSignalScheduler.scheduled()  07:00
└─ FinancialSignalJob.run()                            :61
   └─ sync()                                           :83
      ├─ financialService.lastChangedByCompanyId()      기업별 max(financial_report.updated_at). 비어 있으면 IllegalStateException
      ├─ checkpoints.findAllBySource(SIGNAL_FINANCIAL)   기업별 체크포인트 한 번에 로드(nextCheckAt 포함)
      ├─ 대상 = (체크포인트 없음 | ERROR | 원천 버전("fin-2:<마지막 변경 시각>")이 다름)
      │         OR (체크포인트.nextCheckAt이 있고 오늘 ≥ nextCheckAt)   ← D-43, 추가 조회 없음
      ├─ fullRecompute = 대상 > 전체의 50%  (철회 급증 경고를 끄는 기준)
      └─ for companyId in 대상
         └─ transaction {
              summary = FinancialSummaryService.summarize(companyId)                 :39
                ├─ FinancialRepository.findAllByCompany()  보고서+행 조인 한 번 → List<StoredFinancialReport>
                ├─ latestReport(): period_end 최댓값, 같으면 연결(CFS) 우선 → 기준(fs_div)·통화 고정 (D-37)
                ├─ buildQuarters(): 회계연도별 Q1·H1·Q3 → QuarterEntry(AccountMapper.map)
                │                   FY가 있으면 4분기 파생 = 연간 − 3분기 누적 (통화·회계연도 시작일 같을 때, 파생 매출 음수면 무효)
                │                   period_end 내림차순 12개
                ├─ buildAnnual(): FY 보고서 최근 3개 → AnnualEntry (12개월이 아니면 irregular)
                ├─ detectBasisGap(): 창 안에 다른 기준에만 있는 기간 → BASIS_GAP
                └─ buildFlags(): NON_KRW, NOT_APPLICABLE_FORMAT, BASIS_GAP, INCONSISTENT_BALANCE, DERIVED_INVALID
              (drafts, staleRecheckAt) = FinancialSignalCalculator.calculate(summary, today)  :35
                ├─ 흐름 기간 = 파생이 아닌 분기 + 연간
                │    각 기간: 흑자·적자 전환(turnSignal) → 없으면 영업이익률 변화 → 매출 증감
                │    active = 그 기간 종료일 == 최신 기간 종료일
                ├─ 부채비율 급등: 회계연도마다 처음 성립한 분기 하나. active = 최신 회계연도
                ├─ 영업적자 지속: 이어진(45~135일 간격) 적자 분기 4개 이상 구간마다
                ├─ 자본잠식: 자본총계 < 자본금인 이어진 구간마다
                └─ 데이터 한계 7종 (항상 active, 방향 UNCERTAIN, 심각도 LOW)
                     └─ FIN_DATA_STALE: assessStale(latest, today) → (stale?, deadline, nextRecheckDate)
                          다음 기간 종료월 말일 + 60/120일(3분기 latest면 120) + 유예 7일. D-43
              latestKey = summary.quarters()[0].key().stateBasisKey() (재무 없으면 null)
              result = CompanySignalService.applyFinancial(companyId, drafts, "fin-2", latestKey)  :33
                ├─ 기존 비철회 자연키 집합
                ├─ 초안마다 CompanySignalRepository.upsert(status = active ? ACTIVE : PAST)
                └─ 초안에 없던 기존 자연키
                     ├─ FIN_DATA_STALE이고 근거 키 < latestKey → markPast() (PAST, 해소는 철회가 아니다)
                     └─ 그 외 → withdraw() (WITHDRAWN)
              checkpoints.record(SIGNAL_FINANCIAL, companyId, 원천 버전, SUCCESS, "반영 n·철회 m", staleRecheckAt)
            }
            catch RuntimeException → record(ERROR) (트랜잭션 밖), failed++
      ├─ 규칙 전체 재판정이 아닌데 철회 발생 기업 > 대상의 10% → 경고 로그 + 요약에 "[경고: 철회 급증]"
      │    (STALE이 markPast로 처리된 것은 withdrawn 카운트에 들어가지 않는다)
      └─ 실패가 있으면 PARTIAL, 없으면 SUCCEEDED
```

### 7.5 종단 시나리오: 새 정기공시가 신호가 되기까지

어떤 ACTIVE 기업(12월 결산)이 "반기보고서 (2026.06)"을 낸 뒤 같은 보고서를 `[기재정정]`으로 다시 냈다고 하자(가상의 예시). 코드상 경로는 다음과 같다.

| 시점 | 작업 | 일어나는 일 | 결과 |
|---|---|---|---|
| 제출 당일 장중 | `disclosure-sync` 당일분 | `list.json`(A) 페이지에서 두 공시를 받는다. `ReportName.parse`: 정정본은 `baseName = "반기보고서 (2026.06)"`, `amendmentLabel = "기재정정"`. `relinkAmendments`가 정정본의 `original_id`를 원 공시로 연결 | `disclosure` 2행 |
| 다음 날 06:00 | `disclosure-sync` 전체 | 전날 날짜를 다시 읽고(마지막 시도가 그 날짜 당일이었으므로 미확정) 확정 | 체크포인트 확정 |
| 06:30 | `financial-sync` | `latestPeriodicByCompanyAndBaseName`이 이 기업의 "반기보고서 (2026.06)"에 대해 **정정본 공시번호**(더 큰 번호)를 준다 → `PeriodicReportName.resolve` → (2026, H1, 2026-06) → `TargetKey(고유번호, 2026, 11012)`. 체크포인트의 원천 버전보다 크므로 CATCH_UP. `fnlttMultiAcnt` 응답의 `thstrm_dt` 종료월 2026-06이 계기와 같으면 `replace` | 값이 바뀌었으면 `financial_report` 삭제 후 재삽입 → `updated_at` 갱신. 체크포인트 원천 버전 = 정정본 공시번호 |
| 07:00 | `financial-signal` | `lastChangedByCompanyId`의 시각이 바뀌어 대상. 요약 → 계산 → 반영 | 예: 반기 매출이 전년 동기 대비 +30% 이상이면 `FIN_REVENUE_CHANGE`, 근거 키 `2026-01-01:H1`, ACTIVE. 정정으로 조건이 사라진 신호는 WITHDRAWN |

값이 전혀 바뀌지 않은 정정(`[첨부정정]` 등)이라도 공시번호가 바뀌면 `sameContent`가 false(공시번호 비교 포함)가 되어 재삽입되고, 그 결과 다음 신호 계산 대상이 된다. 신호 결과가 같으면 upsert만 일어나고 `status_changed_at`은 그대로다.

### 7.6 AI 재무 쉬운 설명 동기화 (`analysis-financial-explain`)

다른 Job과 골격이 다르다. 체크포인트 대신 지문 비교로 재시도를 판단하고, 외부 호출(Anthropic)이 검증 대상 콘텐츠를 만든다. §7.0의 공통 `run()` 골격(`running` 플래그, `pipeline_run` 기록)은 그대로 쓴다.

```
FinancialExplainScheduler.scheduled()  07:30
└─ FinancialExplainJob.run()
   └─ sync(progress)
      ├─ targets() = companyService.aiCoveredCompanyIds() ∪ properties.goldenSetCompanyIds()  (LinkedHashSet, 중복 제거)
      │    비어 있으면 즉시 SUCCEEDED("대상 없음")
      ├─ dayStart/monthStart 계산 (Asia/Seoul 기준)
      └─ for companyId in targets
         ├─ isBudgetExceeded(dayStart, monthStart)?  analysisService.spentSince(...) ≥ daily/monthly-budget-usd
         │    true면 남은 대상을 건너뛰고 루프 종료(PARTIAL, "[예산 소진으로 중단]")
         └─ processTarget(companyId)
            ├─ inputBuilder.build(companyId)  비어 있으면(재무 보고서 없음) skippedNoData++
            ├─ analysisService.decide(COMPANY, companyId, FINANCIAL_EXPLAIN, fingerprint, PROMPT_VERSION, now)
            │    SKIP_UP_TO_DATE | SKIP_BACKOFF | SKIP_MAX_FAILURES → 카운터만 올리고 다음 대상
            │    PROCEED → generate(companyId, built)
            └─ generate(): 최대 2회 시도
               ├─ 1회차: llmClient.generate(SYSTEM_PROMPT, userMessage(inputJson, null), SCHEMA_JSON)
               │    LlmOutputRejectedException(잘림/거절) → 사용량만 누적, 실패 규칙 "LLM_OUTPUT_REJECTED"로 2회차 진행
               │    LlmException(그 밖 호출 실패) → 재시도 없이 CALL_FAILED로 확정, 루프 종료
               │    성공 → jsonMapper.readValue(outputJson, FinancialExplainOutput.class)
               │         파싱 실패(JacksonException) → 실패 규칙 "INVALID_JSON"으로 2회차 진행
               │         파싱 성공 → validator.validate(output, input)
               │              통과 → SUCCESS로 확정, 루프 종료
               │              실패 → 실패 규칙 번호 목록으로 2회차 진행(원문은 되돌리지 않음, userMessage에 규칙 번호만 덧붙임)
               ├─ 2회차도 실패하면 REJECTED로 확정(토큰·비용은 두 시도 합산)
               ├─ analysisService.save(NewAnalysis(status, output(REJECTED/FAILED면 null), input, snapshot, 버전 4종, 토큰·비용, 실패 규칙, 시도 횟수), now)
               └─ SUCCESS && properties.publish() → analysisService.publish(id, ...)  (false면 DRAFT로 남음, D-39)
      └─ 요약 문자열(대상/초안/게시/거절/실패/건너뜀 3종/데이터없음 개수) → pipeline_run.message
```

- **1회 재생성만 한다**(§4.4.7). 실패 규칙은 시스템 프롬프트가 아니라 사용자 메시지 끝에 붙는다 — 시스템 프롬프트를 고정 문자열로 유지해야 프롬프트 캐싱이 걸린다(§8, ai-analysis.md §8).
- **예산은 분석 종류를 가리지 않는다**(`sumCostSince`가 `analysis` 테이블 전체를 합산). 지금은 `financial_explain`뿐이라 사실상 이 작업만의 예산이다.
- **무효화는 이 흐름 밖이다.** `FinancialExplainJob`은 새로 만들 뿐 기존 게시본을 내리지 않는다. 게시본을 보여줄 때(아직 없는 화면 코드가) `AnalysisRenderer.isInvalidated()`를 불러야 한다(§4.9).

### 8.1 `app.*` (application.yml)

| 키 | 기본값 | 바인딩 | 코드 사용 위치 |
|---|---|---|---|
| `app.scheduler.enabled` | `false` (prod `true`) | — | `SchedulingConfig`의 `@ConditionalOnBooleanProperty` |
| `app.dart.api-key` | `${DART_API_KEY:}` | `DartProperties.apiKey` | `DartClient.get()`: 비어 있으면 `MISSING_KEY`(실행 중단). 쿼리 `crtfc_key` |
| `app.dart.base-url` | `https://opendart.fss.or.kr/api` | `baseUrl` | `DartClient` 생성자 `restClientBuilder.baseUrl` |
| `app.dart.min-interval` | `200ms` | `minInterval` | `DartClient.RequestPacer` |
| `app.dart.connect-timeout` | `5s` | `connectTimeout` | `DartConfig` (`HttpClient.connectTimeout`) |
| `app.dart.read-timeout` | `60s` | `readTimeout` | `DartConfig` (`setReadTimeout`) |
| `app.dart.max-attempts` | `3` | `maxAttempts` | `DartClient.get()` 재시도 횟수 |
| `app.dart.retry-backoff` | `2s` | `retryBackoff` | `DartClient.get()`: 대기 = backoff × 시도 번호 |
| `app.ingest.company-sync.cron` | `0 0 5 * * *` | `CompanySyncProperties.cron` | `@Scheduled`가 플레이스홀더로 직접 읽음. **레코드 필드는 코드에서 쓰지 않는다** |
| `...company-sync.run-on-startup` | `false` | `runOnStartup` | `CompanySyncScheduler.runOnStartup()` |
| `...company-sync.max-calls-per-run` | `10000` | `maxCallsPerRun` | `CompanySyncJob.sync()` (고유번호 1회 포함) |
| `...company-sync.refresh-after` | `30d` | `refreshAfter` | `CompanySyncJob.dueForOverview()` 우선순위 1 |
| `...company-sync.min-listed-count` | `1000` | `minListedCount` | `checkListedCount()` |
| `...company-sync.max-delisted-ratio` | `0.1` | `maxDelistedRatio` | `checkListedCount()` |
| `app.ingest.disclosure-sync.cron` | `0 0 6 * * *` | `cron` (필드 미사용) | `DisclosureSyncScheduler.scheduled()` |
| `...disclosure-sync.intraday-cron` | `0 */30 8-19 * * MON-FRI` | `intradayCron` (필드 미사용) | `DisclosureSyncScheduler.intraday()` |
| `...disclosure-sync.run-on-startup` | `false` | `runOnStartup` | `DisclosureSyncScheduler.runOnStartup()` (전체 실행) |
| `...disclosure-sync.max-calls-per-run` | `3000` | `maxCallsPerRun` | `DisclosureSyncJob.sync()`, `syncUnit()` |
| `...disclosure-sync.initial-load-period` | `3y` (`Period`) | `initialLoadPeriod` | `DisclosureSyncJob.dueUnits()` |
| `app.ingest.financial-sync.cron` | `0 30 6 * * *` | `cron` (필드 미사용) | `FinancialSyncScheduler.scheduled()` |
| `...financial-sync.run-on-startup` | `false` | `runOnStartup` | `FinancialSyncScheduler.runOnStartup()` |
| `...financial-sync.max-calls-per-run` | `500` | `maxCallsPerRun` | `FinancialSyncJob.sync()` (묶음 1개 = 1호출) |
| `...financial-sync.initial-load-years` | `3` | `initialLoadYears` | `FinancialSyncJob.sync()`: `minBsnsYear`, 계기 필터와 초기 적재 범위 |
| `app.signal.financial-signal.cron` | `0 0 7 * * *` | `cron` (필드 미사용) | `FinancialSignalScheduler.scheduled()` |
| `...financial-signal.run-on-startup` | `false` | `runOnStartup` | `FinancialSignalScheduler.runOnStartup()` |
| `app.analysis.financial-explain.cron` | `0 30 7 * * *` | `cron` (필드 미사용) | `FinancialExplainScheduler.scheduled()` |
| `...financial-explain.run-on-startup` | `false` | `runOnStartup` | `FinancialExplainScheduler.runOnStartup()` |
| `...financial-explain.golden-set-company-ids` | `[]` | `goldenSetCompanyIds` | `FinancialExplainJob.targets()`, `GoldenSetDumpRunner.run()` |
| `...financial-explain.daily-budget-usd` | `5` | `dailyBudgetUsd` | `FinancialExplainJob.isBudgetExceeded()` |
| `...financial-explain.monthly-budget-usd` | `60` | `monthlyBudgetUsd` | 같음 |
| `...financial-explain.publish` | `false` | `publish` | `FinancialExplainJob.generate()`: true여야 검증 통과 결과를 게시본으로 승격 |
| `app.llm.api-key` | `${ANTHROPIC_API_KEY:}` | `LlmProperties.apiKey` | `AnthropicLlmClient.generate()`: 비어 있으면 호출 전에 `LlmException` |
| `app.llm.model` | `claude-sonnet-5` | `model` | `MessageCreateParams.model(String)` |
| `app.llm.max-tokens` | `2000` | `maxTokens` | `MessageCreateParams.maxTokens(long)` |
| `app.llm.timeout` | `60s` | `timeout` | `AnthropicOkHttpClient.builder().timeout()` |
| `app.llm.max-retries` | `2` | `maxRetries` | `AnthropicOkHttpClient.builder().maxRetries()`(SDK 자체 재시도, 통신 오류 등) |
| `app.llm.input-cost-per-million` / `cache-read-cost-per-million` / `output-cost-per-million` | `2` / `0.2` / `10`(USD) | 동명 필드 | `AnthropicLlmClient.estimateCost()`(ai-analysis.md §9, 2026-06 기준 가격) |

코드 상수로 고정된 값(설정으로 바꿀 수 없음): 재무 묶음 크기 100(`FinancialSyncJob.BATCH_SIZE`, `DartClient.MAX_KEY_ACCOUNT_CORP_CODES`), 공시 페이지 크기 100, 재무 요약 창(12분기·3년), 재무상태표 허용 오차 0.5%, 분기 연속 간격 45~135일, 신호 문턱값 전체(`FinancialRuleCatalog`), 철회 급증 10%·전체 재판정 50%, 메시지 길이(`pipeline_run` 1,000자, 체크포인트 500자), 재무 쉬운 설명 최대 재시도 2회(`FinancialExplainJob.MAX_ATTEMPTS`), 이력 신호 최대 4개(`FinancialExplainInputBuilder.MAX_PAST_SIGNALS`), 실패 백오프 24시간·최대 실패 3회(`AnalysisService`), 프롬프트·스키마 버전 문자열(`FinancialExplainPrompt`).

### 8.2 Spring 설정

| 키 | 값 | 영향 |
|---|---|---|
| `spring.threads.virtual.enabled` | `true` | Spring Boot가 요청 처리·작업 실행기에 가상 스레드를 쓰게 한다. 스케줄러 실행 방식에 주는 영향은 §11.4 [미확인] |
| `spring.jpa.open-in-view` | `false` | 웹 요청 동안 영속성 컨텍스트를 열어 두지 않는다 |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 엔티티-스키마 불일치 시 기동 실패 |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | JPA가 시각을 UTC로 읽고 쓴다 |
| `spring.flyway.locations` | `classpath:db/migration` | 마이그레이션 위치 |
| `management.endpoints.web.exposure.include` | `health` | 노출 엔드포인트 |
| `spring.config.import` (local/prod) | 비밀값 파일 | §5.2의 1 |
| `spring.datasource.*` (local/prod) | compose DB / 환경 변수 | §5.2의 3 |

---

## 9. 데이터 흐름과 타입 변환

### 9.1 기업

```
corpCode.xml (zip)  ──CorpCodeXmlParser──▶ DartCorpCode (ingest.dart)
company.json        ──JsonMapper─────────▶ DartCompanyOverview (ingest.dart)
                         │ CompanySyncJob.toListedCompany()   값 선택·결산월 검증·Market.fromDartCorpCls
                         ▼
                    CompanyService.ListedCompany (company 패키지의 입력 DTO)
                         │ CompanyService.upsertListed()
                         ▼
                    Company / Security / CompanyAlias (JPA 엔티티)  ──Hibernate──▶ company, security, company_alias
읽기: Company → Map<고유번호, 기업 ID>, Map<기업 ID, 결산월>  (다른 수집 Job이 씀)
```

### 9.2 공시

```
list.json ──JsonMapper──▶ DartDisclosurePage { List<DartDisclosure> }
            │ DisclosureSyncJob.syncUnit(): 공시번호로 중복 제거, ACTIVE 필터, 접수일 파싱
            ▼
          DisclosureService.NewDisclosure (공시번호 14자리 검증, 비고 strip)
            │ ReportName.parse() → name / baseName / amendmentLabel
            ▼
          DisclosureRepository.upsert() (SQL) ──▶ disclosure
          DisclosureRepository.relinkAmendments() ──▶ disclosure.original_id
읽기: disclosure (PERIODIC) ──group by──▶ PeriodicTrigger ──▶ FinancialSyncJob
```

### 9.3 재무

```
PeriodicTrigger ──PeriodicReportName.resolve()──▶ QueryKey ──▶ TargetKey + TriggerInfo ──▶ Unit(묶음)
fnlttMultiAcnt.json ──JsonMapper──▶ DartKeyAccountResponse { List<DartKeyAccount> }  (금액·ord 모두 문자열)
            │ FinancialSyncJob.toRawLine()
            ▼
          RawAccountLine (financial 패키지의 입력 타입, 여전히 문자열)
            │ FinancialService.replace(): 기간 식별, FinancialAmounts.parse → BigDecimal, ord → int
            ▼
          StoredFinancialLine ──FinancialRepository──▶ financial_report (1) ─< financial_line (N)
```

### 9.4 신호

```
financial_report + financial_line ──findAllByCompany──▶ List<StoredFinancialReport>
            │ FinancialSummaryService: 기준 고정, AccountMapper.map → MappedAccounts(MetricValue ×7)
            ▼
          FinancialSummary { quarters: List<QuarterEntry>, annual: List<AnnualEntry>, flags: List<SummaryFlag> }
            │ FinancialSignalCalculator: (내부) FlowPeriod → 판정
            ▼
          List<SignalDraft>  (calcValues: Map, watchMetrics: List)
            │ CompanySignalService.applyFinancial(): active → ACTIVE/PAST, 누락 → WITHDRAWN
            ▼
          CompanySignalRepository.upsert(): Map/List → JSON 문자열 → jsonb ──▶ company_signal
읽기: company_signal ──▶ CompanySignal (FinancialExplainInputBuilder가 읽는다. 화면은 [설계만])
```

### 9.5 AI 재무 쉬운 설명

```
FinancialSummaryService.summarize() + CompanySignalService.findByCompany()
            │ FinancialExplainInputBuilder.build()
            │   FactCollector: 사실 키·표시 값 생성, 재무상태표 불일치면 재무상태 전체를 unavailable로
            │   selectSignals(): 활성 전부 + 이력 최대 4개, 데이터 한계 신호 제외
            │   Fingerprint.compute(): 공시번호 집합 + 신호 튜플 + unavailable 집합 + 입력 구성 버전 → SHA-256
            ▼
          BuildResult { FinancialExplainInput(AI로 감), ValueSnapshot(렌더링용, 안 감), fingerprint }
            │ FinancialExplainPrompt.userMessage(JSON, 이전 실패 규칙)  ──JsonMapper(Jackson 3)──▶ 문자열
            ▼
          LlmClient.generate(SYSTEM_PROMPT, userMessage, SCHEMA_JSON)
            │ AnthropicLlmClient: JsonValue.fromJsonNode(고전 Jackson 2 JsonNode)로 스키마 변환 ──HTTPS──▶ Anthropic
            ▼
          LlmResult { outputJson, model, 토큰 3종, costUsd }
            │ jsonMapper.readValue(outputJson, FinancialExplainOutput.class)  (Jackson 3)
            ▼
          FinancialExplainOutput { overview, sales_profit, structure, history }
            │ FinancialExplainValidator.validate(output, input)  (순수, DB 없음)
            ▼
          ValidationResult { valid, failedRules }
            │ AnalysisService.save(NewAnalysis)  ──JsonMapper(Jackson 3)──▶ jsonb 3종
            ▼
          analysis 행 (DRAFT, publish=true면 PUBLISHED로 승격)
읽기: AnalysisRenderer.render() ──▶ 토큰을 ValueSnapshot 표시 값으로 치환 (화면 코드는 아직 없음)
읽기: AnalysisRenderer.isInvalidated(current, companyId) ──▶ CompanySignalService.findByCompany + FinancialService.currentReceiptNo만 조회(D-42, 입력 재구성 없음)
```

### 9.6 변환 원칙 (코드에서 관찰되는 것)

- `ingest.dart`의 DTO(`Dart*`)는 `ingest` 밖으로 나가지 않는다. 도메인 서비스의 입력은 도메인 패키지가 정의한 타입이다(`ListedCompany`, `NewDisclosure`, `RawAccountLine`). 그래서 도메인 패키지는 OpenDART 응답 형식에 의존하지 않는다.
- 원천 값은 저장 시점에 해석하지 않는다. 재무 금액은 파싱만 하고 지표 매핑·비율·4분기는 읽을 때 계산한다(D-32).
- JPA 엔티티는 `company` 패키지에만 있다. 공시·재무·신호·분석·실행 기록은 `JdbcClient`와 레코드를 쓴다(대량 upsert·`on conflict`·`jsonb`를 SQL로 직접 다루기 위함. architecture.md §3의 "일반 CRUD는 JPA, 대량 upsert는 JDBC").
- `analysis`만 다른 도메인 패키지(`company`·`financial`·`signal`)를 직접 읽는다(§3). AI 입력이 그 세 도메인을 엮은 결과물이라 예외로 둔 것이고, 반대 방향(도메인이 `analysis`를 읽는 것)은 없다.
- Jackson이 두 버전 공존한다: 도메인 코드는 Jackson 3(`tools.jackson`), `AnthropicLlmClient`만 Anthropic SDK 요구로 고전 Jackson 2(`com.fasterxml.jackson`)를 스키마 변환에 쓴다(§2).

---

## 10. DB 테이블과 읽기·쓰기 주체

| 테이블 | 마이그레이션 | 쓰는 곳 | 읽는 곳 | 키·특이사항 |
|---|---|---|---|---|
| `company` | V1 | `CompanyService`(JPA) | `CompanyService` 조회 → 모든 수집 Job | 고유번호 unique. 상태 ACTIVE/EXCLUDED/DELISTED. `ai_covered`는 기본 false이고 바꾸는 코드 없음 |
| `security` | V1 | `CompanyService` | `CompanyService.commonSecurityOf` (테스트) | (company_id, share_type) unique |
| `company_alias` | V1 | `CompanyService` (사명 변경) | `CompanyService.aliasesOf` | (company_id, alias) unique |
| `ingest_checkpoint` | V1, `next_check_at` 컬럼은 V6 | `IngestCheckpointRepository.record` (4개 Job) | `findAllBySource` (4개 Job) | PK (source, target_key). `next_check_at`은 `SIGNAL_FINANCIAL`만 쓴다(D-43) |
| `pipeline_run` | V1 | `PipelineRunRecorder` | 코드에서 읽지 않음 (운영자가 SQL로 확인) | 작업명·시작 시각 인덱스 |
| `disclosure` | V2 | `DisclosureRepository.upsert`, `relinkAmendments` | `latestPeriodicByCompanyAndBaseName` (재무 수집), `findByReceiptNo` | 공시번호 unique, `original_id` 자기 참조 |
| `financial_report` | V3 | `FinancialRepository` (via `FinancialService.replace`) | `FinancialSummaryService`, `lastChangedByCompanyId`, `currentReceiptNo`(`AnalysisRenderer.isInvalidated`) | (company_id, bsns_year, report_code, fs_div) unique |
| `financial_line` | V3 | 같음 | 같음 | (report_id, ord) unique, 보고서 삭제 시 cascade |
| `company_signal` | V4 | `CompanySignalRepository`(`upsert`/`withdraw`/`markPast`) | `CompanySignalService.findByCompany` (`FinancialExplainInputBuilder`, `AnalysisRenderer.isInvalidated`/`limitMessages`, 테스트) | (company_id, signal_type, basis_key) unique. 행을 지우지 않음 |
| `analysis` | V5 | `AnalysisRepository`(`FinancialExplainJob`이 부름) | `AnalysisRepository.findCurrent`/`findLatestByFingerprint`/`sumCostSince` (`AnalysisService`, `AnalysisRenderer`) | (target_type, target_key, analysis_kind) 부분 유일 인덱스(`is_current`일 때만), (target_type, target_key, analysis_kind, fingerprint) 일반 인덱스. `model` 컬럼은 null 허용(호출 자체가 실패해 응답을 못 받은 FAILED 행은 모델을 모른다) |

architecture.md §4.2의 나머지 테이블(`price_daily`, `market_holiday`, `document_section`, `news_item`, `competitor`, `corporate_event`, `content_entry`)은 아직 없다 [설계만].

---

## 11. 실행 특성: 트랜잭션·예외·재시도·동시성·시간

### 11.1 트랜잭션

| 위치 | 경계 | 비고 |
|---|---|---|
| 모든 Job | 외부 호출은 트랜잭션 밖. 대상 하나의 DB 반영 + 체크포인트 SUCCESS/NO_DATA를 `TransactionTemplate` 하나로 묶는다 | 저장과 "처리했다"는 기록이 함께 커밋되거나 함께 롤백된다. 중간에 멈춰도 다음 실행이 같은 대상을 다시 처리한다 |
| ERROR 체크포인트 기록 | 트랜잭션 밖 (`JdbcClient` 자동 커밋) | 반영 트랜잭션이 롤백돼도 오류 기록은 남는다 |
| `CompanyService` | 클래스 수준 `@Transactional`, 조회는 `readOnly` | Job의 `TransactionTemplate` 안에서 불리면 그 트랜잭션에 참여한다. `markDelistedExcept`처럼 Job이 트랜잭션 밖에서 부르면 자체 트랜잭션 하나(상장폐지 표시 전체가 한 번에) |
| `DisclosureService`, `FinancialService`, `CompanySignalService` | 클래스 수준 `@Transactional` | 같음 |
| `FinancialSummaryService.summarize` | `@Transactional(readOnly = true)` | 신호 Job에서는 바깥 쓰기 트랜잭션에 참여한다 |
| `AnalysisService` | 클래스 수준 `@Transactional`, 조회·`decide()`·`spentSince()`는 `readOnly` | `save()`와 `publish()`는 각각 별도 트랜잭션(`FinancialExplainJob`이 두 호출 사이에서 묶지 않는다) — 저장은 항상 되고, 게시 승격만 실패해도 DRAFT 행은 남는다 |
| `FinancialExplainInputBuilder.build` | `@Transactional(readOnly = true)` | `FinancialExplainJob`은 이 호출을 감싸는 트랜잭션이 없다(외부 LLM 호출이 중간에 끼므로 하나로 묶을 수 없음) |
| `PipelineRunRecorder` | 트랜잭션 없음 (자동 커밋) | 실행 기록은 작업 결과와 독립적으로 남는다 |

단위: 기업 목록 = 기업 하나, 공시 = 날짜·유형 하나(모든 페이지), 재무 = 기업·조회 키 하나(연결·별도 함께), 신호 = 기업 하나, 재무 쉬운 설명 = 기업 하나(입력 구성은 트랜잭션 안, LLM 호출·검증은 트랜잭션 밖, 저장은 별도 트랜잭션).

### 11.2 예외 처리

| 예외 | 발생 위치 | 처리 |
|---|---|---|
| `DartApiException` + `stopsRun()` | `DartClient` (키 없음·키 오류·한도 초과·점검·재시도 후 통신 실패) | Job 루프에서 다시 던짐 → `run()`에서 FAILED "중단: ..." |
| `DartApiException` (그 밖: 013 외 상태, 형식 오류, 4xx) | `DartClient` | 해당 대상 ERROR, 다음 대상 계속. 재무는 묶음의 모든 기업 ERROR |
| `IllegalStateException` (전제 조건) | 상장사 수 이상, ACTIVE 없음, 재무 없음 | `run()`에서 FAILED "오류: ..." (상장폐지 처리 전에 멈춤) |
| `FinancialPeriodException` | 기간 식별 실패, 계기 기간 불일치 | 그 기업·기간 ERROR, 저장 안 함 |
| `PeriodicReportNameException` | 분기보고서 결산월 불일치·미상 | 그 계기를 건너뜀(`resolutionErrors`), 경고 로그. 체크포인트 기록 없음 |
| 그 밖 `RuntimeException` | 대상 처리 중 | 해당 대상 ERROR + 경고 로그 |
| 판정 실패 (신호) | 요약·계산·반영 중 | 그 기업 ERROR, `PARTIAL` |
| `LlmException`(순수, `LlmOutputRejectedException` 아님) | `AnthropicLlmClient.generate()`: 인증키 없음, SDK `AnthropicException`(통신·인증·API 오류) | `FinancialExplainJob`: 재시도 없이 그 대상 `FAILED`로 저장, 다음 대상 계속 |
| `LlmOutputRejectedException` (`LlmException`의 하위) | `AnthropicLlmClient.generate()`: `StopReason.MAX_TOKENS`/`REFUSAL`, 텍스트 블록 없음 | `FinancialExplainJob`: 검증 실패와 같게 다뤄 1회 재생성. 과금된 사용량은 `partialUsage()`로 넘어와 누적된다 |
| 검증 실패 (`ValidationResult.valid() == false`) | `FinancialExplainValidator.validate()` | 1회 재생성. 두 번째도 실패하면 그 대상 `REJECTED`로 저장(이전 게시본 유지, 부분 게시 없음) |
| `JacksonException` (출력 JSON 파싱 실패) | `FinancialExplainJob.generate()`: `jsonMapper.readValue(outputJson, ...)` | 검증 실패와 같게 다뤄 1회 재생성(실패 규칙 `INVALID_JSON`) |

### 11.3 재시도

| 층 | 방식 |
|---|---|
| HTTP (한 호출 안) | `DartClient.get()`만. `ResourceAccessException`(통신 오류·타임아웃)과 `HttpServerErrorException`(5xx)만 최대 `max-attempts`회, 대기 `retry-backoff × 시도 번호`. OpenDART 상태 코드 오류와 4xx는 재시도하지 않는다 |
| 실행 간 | 체크포인트 ERROR가 다음 실행의 대상이 된다. 기업개황 우선순위 2, 공시 CATCH_UP, 재무 CATCH_UP, 신호 대상. 횟수 제한이나 대기 시간은 없고 매 실행 다시 시도한다(`attempt_count`는 기록만 한다) |
| NO_DATA | 반복 요청하지 않는다. 재무는 새 계기 공시가 와야 다시 받는다 |
| LLM 호출(한 호출 안) | `AnthropicOkHttpClient`의 `maxRetries`(`app.llm.max-retries`, 기본 2) — SDK가 내부에서 통신 오류 등을 재시도한다. 코드가 직접 재시도 루프를 쓰지 않는다 |
| LLM 검증 실패(같은 실행 안) | `FinancialExplainJob`이 최대 2회 시도(최초 1 + 재생성 1). 실패 규칙 번호만 사용자 메시지에 덧붙인다(§7.6) |
| LLM 실행 간(다음 스케줄) | 체크포인트가 아니라 `AnalysisService.decide()`의 지문 비교로 판단: REJECTED/FAILED는 24시간 지나야 재시도, 같은 지문 누적 3회 실패면 프롬프트 버전이 바뀌기 전까지 멈춘다(ai-analysis.md §6.1) |

재시도 라이브러리(Resilience4j 등)는 쓰지 않는다(architecture.md §3). Anthropic SDK 자체의 재시도(`maxRetries`)는 예외다 — SDK가 제공하는 것을 그대로 설정으로 노출했다.

### 11.4 동시성

- **같은 작업의 중복 실행**: Job마다 `AtomicBoolean running`. 이미 실행 중이면 곧바로 `skipped`를 돌려주고 `pipeline_run`에 남기지 않는다. `DisclosureSyncJob`은 전체·장중 실행이 플래그 하나를 공유한다. 이 보호는 **JVM 하나 안에서만** 유효하다. 서버가 여러 대면 스케줄러를 한 대에서만 켠다(architecture.md §2.3).
- **다른 작업끼리**: 서로 막지 않는다. 동시에 돌 수 있는지는 스케줄러 실행 방식에 달려 있다. 코드에 스케줄러 스레드 설정이 없고 `spring.threads.virtual.enabled=true`이므로, Spring Boot 기본 구성에서는 작업마다 가상 스레드에서 실행되어 겹칠 수 있다고 본다 [미확인: 실행으로 확인하지 않음]. 기동 직후 실행(`run-on-startup`)은 작업마다 별도 가상 스레드이므로 여러 개를 켜면 확실히 동시에 돈다.
- **OpenDART 호출 간격**: `DartClient`는 빈 하나이고 `RequestPacer.await()`가 `synchronized`이므로, 여러 작업이 동시에 돌아도 앱 전체에서 호출 사이 최소 간격(`min-interval`)이 지켜진다. 대신 동시에 도는 작업들은 서로의 호출 속도를 나눠 쓴다.
- **한 작업 안**: 순차 처리다. 병렬 호출·병렬 저장은 없다. `FinancialExplainJob`도 대상을 하나씩 순차로 처리한다 — ai-analysis.md §6.2가 정한 "동시 호출 수 설정(초기값 4)"은 구현하지 않았다(정확성을 먼저 확인하려는 의도적 범위 축소, implementation-plan.md §7.2, §15).
- **남은 RUNNING 정리**: `PipelineRunRecorder.start()`가 같은 작업명의 RUNNING 행을 FAILED로 바꾼다(프로세스가 죽어 끝나지 못한 실행 정리).

### 11.5 멱등성과 재개

- 저장은 모두 자연키 upsert 또는 "같으면 그대로"다: 기업(고유번호), 종목(기업+종류), 공시(공시번호), 재무(기업+조회 키+fs_div, 내용 비교), 신호(기업+유형+근거 키). 같은 입력으로 다시 돌려도 행이 늘지 않는다. **예외**: `analysis`는 새 시도마다 새 행을 쌓는다(감사·재시도 이력 보존이 목적, upsert가 아니다) — 대신 같은 지문이면 `AnalysisService.decide()`가 호출 자체를 건너뛰어 중복 생성을 막는다(`FinancialExplainJobTest#rerunWithSameFingerprintDoesNotCallLlmAgain`으로 확인).
- 작업 큐가 없다. "무엇을 처리할지"는 매 실행마다 체크포인트와 현재 데이터에서 다시 계산한다(D-07과 같은 방식). 그래서 호출 상한·중단·재시작 뒤에도 다음 실행이 이어받는다. 안정 상태의 재실행은 호출 0회로 끝난다(implementation-plan.md §3-2.1, §3-3.2). `analysis`는 체크포인트 대신 지문을 쓰지만 같은 성질이다.

### 11.6 시간

| 대상 | 기준 |
|---|---|
| 시각(`timestamptz`) | `Clock.systemUTC()`로 만들고 UTC로 저장 (Hibernate `jdbc.time_zone: UTC`, compose `TZ=UTC`) |
| "오늘", 날짜 확정 판단 | `LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE))`, 즉 KST |
| cron | `zone = "Asia/Seoul"` |
| 공시 기준일 | OpenDART `rcept_dt` (공시번호에서 뽑지 않음) |
| 재무 기간 | 응답 `thstrm_dt` (결산월 설정을 믿지 않음, D-33) |
| AI 예산의 "오늘"·"이번 달" 경계 | `FinancialExplainJob.sync()`가 `now.atZone(SERVICE_ZONE)`으로 그날 00:00·그달 1일 00:00을 계산해 `analysisService.spentSince()`에 넘긴다 |

테스트는 `Clock` 빈을 `MutableClock`으로 바꿔 시간을 움직인다.

### 11.7 로그·보안

- 인증키는 쿼리 파라미터로 전송된다. `DartClient`는 예외·로그에 URL을 쓰지 않고, 통신 예외는 클래스 이름이나 HTTP 상태만 남긴다. `DartProperties.toString()`은 키를 가린다.
- Anthropic 인증키는 HTTP 헤더로 전송된다(SDK가 처리, 코드가 직접 다루지 않음). `AnthropicLlmClient`는 예외 메시지에 SDK 예외의 **클래스 이름만** 남기고(`e.getClass().getSimpleName()`), 원문 메시지·요청 본문은 남기지 않는다. `LlmProperties.toString()`은 키를 가린다. 프롬프트 전문(시스템 프롬프트·사용자 입력)은 어디에도 로그로 남기지 않는다(ai-analysis.md 보안 요구사항) — 실제 401 오류로 확인함(잘못된 키로 1회 수동 호출, 예외 메시지에 키가 없음을 확인하고 테스트는 삭제, implementation-plan.md §7.4).
- 로그는 SLF4J. Job 완료 시 info로 요약, 대상 실패는 warn, 실행 실패는 error.

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
| `publish` 설정을 꺼 둔 채로 구현 완료 | 골든셋 사람 검토를 통과하기 전에는 실제 화면에 나갈 수 없게 하는 안전장치. 검증기를 통과해도 초안(DRAFT)에서 멈춘다 | D-39, ai-analysis.md §4.4.8 |
| `FinancialExplainJob`을 순차 처리로 먼저 구현 | 동시 호출 제한은 처리량 문제이고, 검증·재시도·무효화·예산은 정확성 문제다. 첫 AI 기능에서는 정확성을 먼저 확인했다 | implementation-plan.md §7.2 |

---

## 13. 코드를 읽는 순서

처음 읽을 때 (약 반나절 분량)

1. **[README.md](../README.md), 이 문서 §1·§6** — 무엇이 언제 도는지.
2. **[application.yml](../src/main/resources/application.yml), 마이그레이션 V1~V4** — 설정과 테이블.
3. **[StockInsightApplication](../src/main/java/org/stockinsight/StockInsightApplication.java), [TimeConfig](../src/main/java/org/stockinsight/common/config/TimeConfig.java), [SchedulingConfig](../src/main/java/org/stockinsight/common/config/SchedulingConfig.java), [PipelineRunRecorder](../src/main/java/org/stockinsight/common/pipeline/PipelineRunRecorder.java)** — 기동과 공통 부품.
4. **[CompanySyncScheduler](../src/main/java/org/stockinsight/ingest/company/CompanySyncScheduler.java) → [CompanySyncJob](../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java) → [CompanyService](../src/main/java/org/stockinsight/company/CompanyService.java) → [ListingScopePolicy](../src/main/java/org/stockinsight/company/ListingScopePolicy.java)** — **분석 시작점.** 가장 단순한 Job으로 §7.0 골격, 체크포인트, 서비스 쓰기 경로를 익힌다.
5. **[DartApi](../src/main/java/org/stockinsight/ingest/dart/DartApi.java) → [DartClient](../src/main/java/org/stockinsight/ingest/dart/DartClient.java) → [DartStatus](../src/main/java/org/stockinsight/ingest/dart/DartStatus.java)** — 외부 호출, 재시도, 실행 중단 규칙.
6. **[IngestCheckpointRepository](../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpointRepository.java)** — 재개의 원리.
7. **[DisclosureSyncJob](../src/main/java/org/stockinsight/ingest/disclosure/DisclosureSyncJob.java) → [DisclosureService](../src/main/java/org/stockinsight/disclosure/DisclosureService.java) → [ReportName](../src/main/java/org/stockinsight/disclosure/ReportName.java) → [DisclosureRepository](../src/main/java/org/stockinsight/disclosure/DisclosureRepository.java)** — 날짜 확정, 정정 연결.
8. **[FinancialSyncJob](../src/main/java/org/stockinsight/ingest/financial/FinancialSyncJob.java) → [PeriodicReportName](../src/main/java/org/stockinsight/financial/PeriodicReportName.java) → [FinancialService](../src/main/java/org/stockinsight/financial/FinancialService.java) → [FinancialRepository](../src/main/java/org/stockinsight/financial/FinancialRepository.java)** — 가장 복잡한 수집. 계기·묶음·기간 검증.
9. **[FinancialSummaryService](../src/main/java/org/stockinsight/financial/FinancialSummaryService.java) → [AccountMapper](../src/main/java/org/stockinsight/financial/AccountMapper.java) → `QuarterEntry`/`PeriodKey`** — 원천 행이 시계열이 되는 곳.
10. **[FinancialSignalCalculator](../src/main/java/org/stockinsight/signal/FinancialSignalCalculator.java) + [FinancialRuleCatalog](../src/main/java/org/stockinsight/signal/FinancialRuleCatalog.java) → [CompanySignalService](../src/main/java/org/stockinsight/signal/CompanySignalService.java) → [FinancialSignalJob](../src/main/java/org/stockinsight/signal/FinancialSignalJob.java)** — 제품의 핵심인 변화 판정. `calculate()`부터.
11. **[FinancialExplainInputBuilder](../src/main/java/org/stockinsight/analysis/FinancialExplainInputBuilder.java) → [FinancialExplainValidator](../src/main/java/org/stockinsight/analysis/FinancialExplainValidator.java) → [FinancialExplainJob](../src/main/java/org/stockinsight/analysis/FinancialExplainJob.java) → [AnthropicLlmClient](../src/main/java/org/stockinsight/analysis/llm/AnthropicLlmClient.java)** — AI 분석. 앞의 10단계(특히 4·9)를 먼저 이해해야 입력이 어디서 오는지 알 수 있다.

목적별 시작점

| 하려는 일 | 먼저 볼 곳 |
|---|---|
| 새 OpenDART API 추가 | `DartApi`, `DartClient`, 기존 `*SyncJob` 한 개, 가짜 `DartApi`를 가진 테스트들 |
| 새 수집 작업 추가 | §7.0 골격, `*Scheduler`·`*Properties`, application.yml, 이 문서 §6·§8 |
| 신호 규칙 변경 | `FinancialRuleCatalog`(버전 올리기), `FinancialSignalCalculator`, `FinancialSignalCalculatorTest`, ai-analysis.md §3 |
| 재무 해석 변경 | `AccountMapper`, `FinancialSummaryService`, `FinancialSummaryServiceTest` |
| 수집이 왜 안 됐는지 조사 | `pipeline_run.message`, `ingest_checkpoint` (소스별 대상 키 §4.3), 각 Job의 `due*` 메서드 |
| AI 입력·사실표 변경 | `FinancialExplainInputBuilder`, `PeriodLabels`, `Fingerprint`, `FinancialExplainInputBuilderTest`, ai-analysis.md §4.4.2 |
| 검증 규칙 추가·조정 | `FinancialExplainValidator`, `FinancialExplainValidatorTest`(경계값 27건), ai-analysis.md §4.4.7·§7 |
| 프롬프트·모델 변경 | `src/main/resources/prompts/financial_explain/`, `FinancialExplainPrompt`(버전 상수), `LlmProperties.model` |
| AI 동기화가 왜 건너뛰었는지 조사 | `pipeline_run.message`(`analysis-financial-explain`), `AnalysisService.decide()`, `analysis` 테이블의 `status`·`fingerprint`·`failure_reasons` |

---

## 14. 테스트 구조

| 종류 | 파일 | 방식 |
|---|---|---|
| 통합(Job 전체) | `CompanySyncJobTest`, `DisclosureSyncJobTest`, `DisclosureSyncCallLimitTest`, `FinancialSyncJobTest`, `FinancialSyncCallLimitTest`, `FinancialSignalJobTest`, `FinancialSignalStaleJobTest`, `FinancialExplainJobTest`, `AnalysisRendererTest` | `@SpringBootTest` + `@Import(TestcontainersConfiguration, Fake*Config)`. 테스트 안의 `FakeDartConfig`/`FakeLlmConfig`/`FixedClockConfig`가 `FakeDartApi`·`FakeLlmClient`·`MutableClock`을 `@Primary` 빈으로 등록해 `DartApi`·`LlmClient`·`Clock` 주입을 대체한다. `@SpringBootTest(properties = ...)`로 호출 상한·`publish` 등을 줄인다. 테스트마다 `truncate ... restart identity cascade`(공유되는 `MutableClock` 싱글턴은 `truncate`로 리셋되지 않으므로, 남은 값에 좌우되지 않도록 `@BeforeEach`에서 먼저 명시적으로 날짜를 맞춘다, `FinancialSignalStaleJobTest`) |
| 클라이언트 | `DartClientTest` | `src/test/resources/dart/`의 실제 응답 샘플 (세부 방식은 이 문서에서 추적하지 않음) |
| 단위 | `ListingScopePolicyTest`, `ReportNameTest`, `PeriodicReportNameTest`, `FinancialServiceTest`, `FinancialSummaryServiceTest`, `FinancialSignalCalculatorTest`, `FinancialExplainValidatorTest`, `PeriodLabelsTest` | 파일명 기준 분류. 각 파일이 Spring 컨텍스트를 쓰는지는 이 문서에서 확인하지 않음. `FinancialExplainValidatorTest`는 Spring 없이 `new FinancialExplainValidator()`로 직접 검증기를 만들어 §4.4.7 규칙 1~10의 경계값 27건을 확인한다. `FinancialSignalCalculatorTest`는 `assessStale`의 12·3·6월 결산 기한 경계도 확인한다(D-43) |
| 입력 구성(통합) | `FinancialExplainInputBuilderTest` | `@SpringBootTest` + Testcontainers. 사실표·섹션·`unavailable`·지문(정정 시 변경 포함)·결정적 입력(같은 데이터 → 같은 JSON)을 실제 신호 계산(`FinancialSignalJob`)까지 거쳐 확인 |
| 컨텍스트 | `StockInsightApplicationTests` | 기동 확인 |

- `TestcontainersConfiguration`: `postgres:17` 컨테이너를 `@ServiceConnection`으로 데이터소스에 연결한다. 테스트는 프로필이 없으므로 비밀값 파일을 읽지 않고, 인증키 없이 돈다.
- `FakeLlmClient`(`analysis.llm` 테스트 패키지): 준비해 둔 `LlmResult`/예외를 호출 순서대로 돌려주고 호출 인자(시스템 프롬프트·사용자 입력·스키마)를 기록한다. `FinancialExplainJobTest`가 이걸로 재시도·거절·호출 실패·예산·게시 여부·중복 호출 방지를 인증키 없이 확인한다.
- 골든셋: `src/test/resources/golden/financial_explain/*.json`은 자동 테스트가 아니라 `GoldenSetDumpRunner`(§4.9)가 로컬 실 데이터로 만든 고정 입력이다. 프롬프트·모델을 바꿀 때 사람이 비교하는 참고 자료다.
- CI(`ci.yml`): gitleaks 비밀값 검사, JDK 21에서 `./gradlew build`(테스트 포함). 실패하면 테스트 리포트를 업로드한다. Anthropic 인증키 없이도 전부 통과한다(`FakeLlmClient`).
- gitleaks 설정(`.gitleaks.toml`)
  - CI의 `gitleaks-action@v3`는 gitleaks **8.24.3**을 `detect --redact -v --exit-code=2`로 실행한다. `--config`를 넘기지 않으므로 gitleaks가 저장소 루트의 `.gitleaks.toml`을 자동으로 읽는다(디버그 로그 `using existing gitleaks config .gitleaks.toml`). push는 `--log-opts=--no-merges --first-parent <base>^..<head>` 범위만 검사한다.
  - 기본 규칙(`[extend] useDefault = true`)을 모두 쓴다.
  - 8.24.3 기본 규칙에는 Anthropic 키 규칙이 없다(실제 `sk-ant-api03-…` 형식 키도 탐지되지 않음을 확인). 그래서 최신 gitleaks의 `anthropic-api-key`, `anthropic-admin-api-key` 규칙을 원문 그대로 추가했다. gitleaks 버전을 올려 기본 규칙에 포함되면 중복되므로 이 두 규칙을 지운다.
  - 허용은 재무 사실 키 하나뿐이다: `^fin\.[a-z]+(?:_[a-z]+)*\.\d{4}-(?:0[1-9]|1[0-2])\.Q[1-4]$`(비밀값 문자열 전체와 일치해야 함). 이 값은 `"key": ...` 형태라서 `generic-api-key`에 걸린다(골든셋 JSON, ai-analysis.md). 기간 키(`YYYY-MM.Qn`)만 있는 값은 기본 규칙에 걸리지 않으므로 허용하지 않는다. 지표 자리에 숫자를 허용하지 않는 것은 무작위 문자열이 허용 형식을 흉내 내는 것을 막기 위함이다.
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
| 유효한 Anthropic 인증키로 실제 초안 생성·골든셋 사람 검토 | 하지 않았다. 코드·입력 구성은 실제 DB 데이터(12개사)로 확인했고, 인증·네트워크 경로는 잘못된 키로 1회 수동 확인했다. 첫 시도/재시도 통과율·토큰·비용·지연은 기록이 없다(implementation-plan.md §7.4·§7.5) |

**AI 분석의 알려진 한계** (2026-09-25, 3-4 세션에서 실제 데이터로 확인. 판단 대기 항목은 implementation-plan.md §3)

- ~~`AccountMapper`가 "영업수익"을 인식하지 못한다~~ → 오진이다(implementation-plan.md §8.1). 주요계정 API에는 "영업수익" 계정명이 없다. 금융형 증권·투자사는 "매출액"으로 와서 이미 "영업수익" 원값 경로(증가율 없음)를 탄다(골든셋 SV인베스트먼트). 은행·보험(DB손해보험 등)은 매출 계정 자체가 없다(`ACCOUNT_MISSING`).
- ~~비원화 금액이 축약·구분자 없이 원값+통화코드로 표시된다~~ → D-41로 수정 완료(2026-09-26). 통화 무관 한국어 수 단위(조·억·만) + 통화명, 환산 없음. `revenue_yoy_run`도 최신 기간 `revenue_yoy`를 줄 수 있을 때만 준다. `PeriodLabelsTest`(13건) + 실데이터 12개사 재덤프로 확인(implementation-plan.md §8.7).
- ~~`AnalysisRenderer.isInvalidated()`가 지문 전체 재계산-비교 방식이라 과잉 무효화한다~~ → D-42로 수정 완료(2026-09-26). 스냅샷이 참조한 신호 자연키·사실의 (기간 종료일, 기준)만 조회해 판정하고, 입력 전체를 다시 만들지 않는다. `AnalysisRendererTest`(4건)로 확인.
- ~~`FIN_DATA_STALE`이 첫 전체 실행 뒤 새로 기한이 지나는 기업을 다시 판정하지 않는다~~ → D-43으로 수정 완료(2026-09-26). 기한 60·120일(다음 기간 종료월 말일 기준) + 유예 7일, 판정이 남긴 재판정일(`ingest_checkpoint.next_check_at`)이 지난 기업만 추가로 다시 판정, 해소는 이력(PAST). `FinancialSignalCalculatorTest`(6건)·`FinancialSignalStaleJobTest`(4건) + 실데이터(로컬 DB 전체 재판정, 규칙 버전 `fin-2` 반영 확인)로 확인.
- `FinancialExplainJob`은 순차 처리다. ai-analysis.md §6.2의 동시 호출 수 설정은 구현하지 않았다.
- 골든셋 입력 파일(`src/test/resources/golden/financial_explain/`)을 다시 읽어 재생성하는 경로가 없다. 지금은 `GoldenSetDumpRunner`(입력 JSON만 파일로 저장, AI 호출 없음)만 있다.
- `company.ai_covered`를 채우는 코드가 없다(D-23 미구현, §3). `FinancialExplainJob`의 대상은 지금 전부 `golden-set-company-ids`에서 온다.

**코드상 관찰되는 한계** (변경 제안이 아니라 현재 동작의 기록. 판단 대기 항목은 implementation-plan.md §3)

- 상장폐지 기업 중 고유번호 파일에 종목코드가 남는 기업은 DELISTED가 아니라 EXCLUDED(`OTHER_MARKET`)로 저장된다(implementation-plan.md §3).
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
| 신호 규칙·버전 변경 | §4.8, §7.4 (규칙 세부는 ai-analysis.md가 기준) |
| AI 입력·검증 규칙·프롬프트·모델·예산 변경 | §4.9, §4.10, §7.6, §8, §9.5 (규칙 세부는 ai-analysis.md가 기준) |
| 테스트 구조·CI 변경 | §14 |
| 미확인 항목을 확인했거나 한계가 해소됨 | §15 |
| 설계 문서에만 있던 것을 구현 | §1의 "없는 것", §10의 [설계만] 목록에서 옮긴다 |
