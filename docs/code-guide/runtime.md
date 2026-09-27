# 코드 가이드 — 실행·설정·데이터

> 기동과 Bean 구성(§5), 실행 경로(§6), 흐름 추적(§7), 설정값(§8), 데이터 흐름(§9), DB 테이블(§10), 실행 특성(§11).

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
4. **Flyway.** `classpath:db/migration`의 V1~V7를 적용한다(V5 = `analysis` 테이블, V6 = `ingest_checkpoint.next_check_at`, V7 = `analysis.attempts`). 스키마는 Flyway만 관리한다.
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

[CompanySyncJob.run()](../../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java:70) 기준. 나머지 Job도 같다.

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
      ├─ 대상 = (체크포인트 없음 | ERROR | 원천 버전("fin-3:<마지막 변경 시각>")이 다름)
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
                │    매출·영업이익률 변화의 지속 = FlowRuns로 센 그 기간까지의 같은 부호 연속 수 (전환은 null)
                │    active = 그 기간 종료일 == 최신 기간 종료일
                ├─ 부채비율 급등: 회계연도마다 처음 성립한 분기 하나. active = 최신 회계연도
                ├─ 영업적자 지속: 이어진(45~135일 간격) 적자 분기 4개 이상 구간마다
                ├─ 자본잠식: 자본총계 < 자본금인 이어진 구간마다
                └─ 데이터 한계 7종 (항상 active, 방향 UNCERTAIN, 심각도 LOW)
                     └─ FIN_DATA_STALE: assessStale(latest, today) → (stale?, deadline, nextRecheckDate)
                          다음 기간 종료월 말일 + 60/120일(3분기 latest면 120) + 유예 7일. D-43
              latestKey = summary.quarters()[0].key().stateBasisKey() (재무 없으면 null)
              result = CompanySignalService.applyFinancial(companyId, drafts, "fin-3", latestKey)  :33
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
               │              실패 → 실패 규칙 목록으로 2회차 진행(원문은 되돌리지 않음, userMessage에 규칙마다 retryGuidance 안내 한 줄)
               │    (어느 경우든 이 시도를 AnalysisAttempt로 attempts에 추가: 결과·실패 규칙·출력 또는 파싱 실패 원문·사유·검증기 버전, D-53)
               ├─ 2회차도 실패하면 REJECTED로 확정(토큰·비용은 두 시도 합산)
               ├─ analysisService.save(NewAnalysis(status, output(REJECTED/FAILED면 null), input, snapshot, 버전 4종, 토큰·비용, 마지막 시도 실패 규칙, 시도 횟수, attempts), now)
               └─ SUCCESS && properties.publish() → analysisService.publish(id, ...)  (false면 DRAFT로 남음, D-39)
      └─ 요약 문자열(대상/초안/게시/거절/실패/건너뜀 3종/데이터없음 개수) → pipeline_run.message
```

- **1회 재생성만 한다**(§4.4.7). 실패 규칙은 시스템 프롬프트가 아니라 사용자 메시지 끝에 붙는다 — 시스템 프롬프트를 고정 문자열로 유지해야 프롬프트 캐싱이 걸린다(§8, spec/analyses.md §8).
- **예산은 분석 종류를 가리지 않는다**(`sumCostSince`가 `analysis` 테이블 전체를 합산). 지금은 `financial_explain`뿐이라 사실상 이 작업만의 예산이다.
- **무효화는 이 흐름 밖이다.** `FinancialExplainJob`은 새로 만들 뿐 기존 게시본을 내리지 않는다. 게시본을 보여줄 때(아직 없는 화면 코드가) `AnalysisRenderer.isInvalidated()`를 불러야 한다(§4.9).

## 8. 설정값과 사용 위치

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
| `app.llm.input-cost-per-million` / `cache-read-cost-per-million` / `output-cost-per-million` | `2` / `0.2` / `10`(USD) | 동명 필드 | `AnthropicLlmClient.estimateCost()`(spec/analyses.md §9, 2026-06 기준 가격) |

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
            │   FactCollector: 사실 키·표시 값 생성(흑자·적자 상태·전환 사실 포함), 재무상태표 불일치면 재무상태 전체를 unavailable로
            │   assignSectionFacts(): 네 섹션 배정 + relations(R2·R5) → 사실표·스냅샷·factKeys·기간 라벨을 배정분으로 좁힘
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
          ValidationResult { valid, failedRules }  ──(시도마다)──▶ AnalysisAttempt { attempt, outcome, failedRules, output|rawOutput, detail, validatorVersion }
            │ AnalysisService.save(NewAnalysis)  ──JsonMapper(Jackson 3)──▶ jsonb 5종(result_json, input_json, value_snapshot, failure_reasons, attempts)
            ▼
          analysis 행 (DRAFT, publish=true면 PUBLISHED로 승격)
읽기: AnalysisRenderer.render() ──▶ 토큰을 ValueSnapshot 표시 값으로 치환, 결과 전체 HTML 이스케이프 (화면 코드는 아직 없음)
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
| `analysis` | V5, `attempts` 컬럼은 V7 | `AnalysisRepository`(`FinancialExplainJob`이 부름) | `AnalysisRepository.findCurrent`/`findLatestByFingerprint`/`sumCostSince` (`AnalysisService`, `AnalysisRenderer`) | (target_type, target_key, analysis_kind) 부분 유일 인덱스(`is_current`일 때만), (target_type, target_key, analysis_kind, fingerprint) 일반 인덱스. `model` 컬럼은 null 허용(호출 자체가 실패해 응답을 못 받은 FAILED 행은 모델을 모른다). `failure_reasons`는 마지막 시도의 규칙만, `attempts`는 시도별 전체 기록(거절 출력 포함). `attempts`는 코드에서 읽지 않는다(운영자·분석용 SQL, D-53). V7 이전 행은 `attempts`가 null |

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
| LLM 검증 실패(같은 실행 안) | `FinancialExplainJob`이 최대 2회 시도(최초 1 + 재생성 1). 실패 규칙마다 프롬프트 표현으로 된 안내 한 줄(`FinancialExplainPrompt.retryGuidance`)만 사용자 메시지에 덧붙이고, 시도마다 `attempts`에 기록한다(§7.6, D-53) |
| LLM 실행 간(다음 스케줄) | 체크포인트가 아니라 `AnalysisService.decide()`의 지문 비교로 판단: REJECTED/FAILED는 24시간 지나야 재시도, 같은 지문 누적 3회 실패면 프롬프트 버전이 바뀌기 전까지 멈춘다(spec/analyses.md §6.1) |

재시도 라이브러리(Resilience4j 등)는 쓰지 않는다(architecture.md §3). Anthropic SDK 자체의 재시도(`maxRetries`)는 예외다 — SDK가 제공하는 것을 그대로 설정으로 노출했다.

### 11.4 동시성

- **같은 작업의 중복 실행**: Job마다 `AtomicBoolean running`. 이미 실행 중이면 곧바로 `skipped`를 돌려주고 `pipeline_run`에 남기지 않는다. `DisclosureSyncJob`은 전체·장중 실행이 플래그 하나를 공유한다. 이 보호는 **JVM 하나 안에서만** 유효하다. 서버가 여러 대면 스케줄러를 한 대에서만 켠다(architecture.md §2.3).
- **다른 작업끼리**: 서로 막지 않는다. 동시에 돌 수 있는지는 스케줄러 실행 방식에 달려 있다. 코드에 스케줄러 스레드 설정이 없고 `spring.threads.virtual.enabled=true`이므로, Spring Boot 기본 구성에서는 작업마다 가상 스레드에서 실행되어 겹칠 수 있다고 본다 [미확인: 실행으로 확인하지 않음]. 기동 직후 실행(`run-on-startup`)은 작업마다 별도 가상 스레드이므로 여러 개를 켜면 확실히 동시에 돈다.
- **OpenDART 호출 간격**: `DartClient`는 빈 하나이고 `RequestPacer.await()`가 `synchronized`이므로, 여러 작업이 동시에 돌아도 앱 전체에서 호출 사이 최소 간격(`min-interval`)이 지켜진다. 대신 동시에 도는 작업들은 서로의 호출 속도를 나눠 쓴다.
- **한 작업 안**: 순차 처리다. 병렬 호출·병렬 저장은 없다. `FinancialExplainJob`도 대상을 하나씩 순차로 처리한다 — spec/analyses.md §6.2가 정한 "동시 호출 수 설정(초기값 4)"은 구현하지 않았다(정확성을 먼저 확인하려는 의도적 범위 축소, work/3-4-financial-explain.md §7.2, §15).
- **남은 RUNNING 정리**: `PipelineRunRecorder.start()`가 같은 작업명의 RUNNING 행을 FAILED로 바꾼다(프로세스가 죽어 끝나지 못한 실행 정리).

### 11.5 멱등성과 재개

- 저장은 모두 자연키 upsert 또는 "같으면 그대로"다: 기업(고유번호), 종목(기업+종류), 공시(공시번호), 재무(기업+조회 키+fs_div, 내용 비교), 신호(기업+유형+근거 키). 같은 입력으로 다시 돌려도 행이 늘지 않는다. **예외**: `analysis`는 새 시도마다 새 행을 쌓는다(감사·재시도 이력 보존이 목적, upsert가 아니다) — 대신 같은 지문이면 `AnalysisService.decide()`가 호출 자체를 건너뛰어 중복 생성을 막는다(`FinancialExplainJobTest#rerunWithSameFingerprintDoesNotCallLlmAgain`으로 확인).
- 작업 큐가 없다. "무엇을 처리할지"는 매 실행마다 체크포인트와 현재 데이터에서 다시 계산한다(D-07과 같은 방식). 그래서 호출 상한·중단·재시작 뒤에도 다음 실행이 이어받는다. 안정 상태의 재실행은 호출 0회로 끝난다(work/3-collection-signals.md §3-2.1, §3-3.2). `analysis`는 체크포인트 대신 지문을 쓰지만 같은 성질이다.

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
- Anthropic 인증키는 HTTP 헤더로 전송된다(SDK가 처리, 코드가 직접 다루지 않음). `AnthropicLlmClient`는 예외 메시지에 SDK 예외의 **클래스 이름만** 남기고(`e.getClass().getSimpleName()`), 원문 메시지·요청 본문은 남기지 않는다. `LlmProperties.toString()`은 키를 가린다. 프롬프트 전문(시스템 프롬프트·사용자 입력)은 어디에도 로그로 남기지 않는다(spec/ 보안 요구사항) — 실제 401 오류로 확인함(잘못된 키로 1회 수동 호출, 예외 메시지에 키가 없음을 확인하고 테스트는 삭제, work/3-4-verification-1.md §7.4).
- 로그는 SLF4J. Job 완료 시 info로 요약, 대상 실패는 warn, 실행 실패는 error.

---
