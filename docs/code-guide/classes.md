# 코드 가이드 — 클래스별 책임

> §4. 수집·도메인·신호(§4.1~§4.8), `analysis`(§4.9·§4.10), 주요 관계(§4.11).

## 4. 클래스별 책임

### 4.1 `org.stockinsight` / `common`

| 클래스 | 종류 | 책임 |
|---|---|---|
| [StockInsightApplication](../../src/main/java/org/stockinsight/StockInsightApplication.java) | `@SpringBootApplication` `@ConfigurationPropertiesScan` | 진입점. `org.stockinsight` 아래 컴포넌트 스캔과 `@ConfigurationProperties` 레코드 스캔의 기준 |
| [TimeConfig](../../src/main/java/org/stockinsight/common/config/TimeConfig.java) | `@Configuration` | `Clock` 빈(`Clock.systemUTC()`)과 상수 `SERVICE_ZONE = Asia/Seoul`. 모든 시각은 이 `Clock`에서, 모든 "오늘"은 `SERVICE_ZONE` 기준으로 계산한다 |
| [SchedulingConfig](../../src/main/java/org/stockinsight/common/config/SchedulingConfig.java) | `@Configuration` `@EnableScheduling` `@ConditionalOnBooleanProperty("app.scheduler.enabled")` | 설정이 true일 때만 `@Scheduled`를 활성화한다 |
| [PipelineRunRecorder](../../src/main/java/org/stockinsight/common/pipeline/PipelineRunRecorder.java) | `@Component` | `pipeline_run` 기록. `start()`는 같은 작업의 남은 RUNNING 행을 FAILED로 정리한 뒤 새 행을 넣고 ID를 돌려준다. `finish()`는 상태·건수·메시지(최대 1,000자)를 남긴다. 상태: `RUNNING`, `SUCCEEDED`, `PARTIAL`(호출 상한 등으로 일부만), `FAILED` |

### 4.2 `ingest.dart` — OpenDART 연동

| 클래스 | 책임 |
|---|---|
| [DartApi](../../src/main/java/org/stockinsight/ingest/dart/DartApi.java) | 수집기가 쓰는 OpenDART 기능 4개: `fetchCorpCodes()`, `fetchCompany(corpCode)`, `fetchDisclosures(date, type, page)`, `fetchKeyAccounts(corpCodes≤100, bsnsYear, reportCode)`. 모든 실패는 `DartApiException`. 테스트는 이 인터페이스를 가짜로 바꾼다 |
| [DartClient](../../src/main/java/org/stockinsight/ingest/dart/DartClient.java) | `DartApi` 구현. `RestClient`로 GET, 응답 바이트를 Jackson 3 `JsonMapper`(자체 인스턴스)로 파싱. 요청 간 최소 간격(`RequestPacer`), 통신 오류·5xx만 재시도. 상태 013은 빈 결과로 바꾼다. 예외 메시지에 URL(인증키 포함)을 넣지 않는다 |
| [DartConfig](../../src/main/java/org/stockinsight/ingest/dart/DartConfig.java) | `DartClient` 빈 등록. JDK `HttpClient`(연결 타임아웃) + `JdkClientHttpRequestFactory`(읽기 타임아웃) |
| [DartProperties](../../src/main/java/org/stockinsight/ingest/dart/DartProperties.java) | `app.dart.*` 바인딩. `toString()`은 인증키를 가린다 |
| [DartStatus](../../src/main/java/org/stockinsight/ingest/dart/DartStatus.java) | OpenDART 상태 코드 + 내부 상태. `stopsRun()`이 true면 실행 전체를 멈춘다: 010·011·012(키/IP), 020(한도 초과), 800(점검), 901, `MISSING_KEY`, `TRANSPORT_ERROR` |
| [DartApiException](../../src/main/java/org/stockinsight/ingest/dart/DartApiException.java) | `DartStatus`를 가진 런타임 예외 |
| `CorpCodeXmlParser` | 고유번호 zip 안의 XML과 오류 XML 파싱 (세부 구현은 이 문서에서 추적하지 않음) |
| `DartCorpCode` | 고유번호 파일 한 줄. `isListed()` = 종목코드 있음 |
| `DartCompanyOverview` | `company.json` 응답 (시장 구분 `corp_cls`, 업종 `induty_code`, 결산월 `acc_mt` 등) |
| `DartDisclosurePage`, `DartDisclosure` | `list.json` 한 페이지와 공시 한 건. `hasNextPage()` = `page_no < total_page` |
| `DartKeyAccountResponse`, `DartKeyAccount` | `fnlttMultiAcnt.json` 응답과 계정 한 줄. 금액은 문자열 그대로 |

### 4.3 `ingest.checkpoint`

| 클래스 | 책임 |
|---|---|
| [IngestCheckpoint](../../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpoint.java) | (소스, 대상 키)별 마지막 결과: 원천 버전, `SUCCESS`/`NO_DATA`/`ERROR`, 연속 오류 횟수, 마지막 시도·성공 시각, **`nextCheckAt`**(선택, D-43) |
| [IngestCheckpointRepository](../../src/main/java/org/stockinsight/ingest/checkpoint/IngestCheckpointRepository.java) | `findAllBySource(source)` → `Map<대상 키, 체크포인트>`. `record(...)` = `insert ... on conflict do update`(6개 인자 오버로드는 `nextCheckAt=null`로 위임). ERROR면 `attempt_count + 1`, 아니면 0. 성공 시각은 SUCCESS일 때만 갱신. `next_check_at`은 넘긴 값으로 그대로 덮어쓴다(null이면 지운다) |

작업별 체크포인트 사용

| 소스 | 대상 키 예 | 원천 버전 | 쓰는 곳 |
|---|---|---|---|
| `DART_COMPANY` | `00126380` (고유번호) | 고유번호 파일의 변경일 | `CompanySyncJob` |
| `DART_DISCLOSURE` | `2026-08-14:A` (날짜:유형) | 없음(null) | `DisclosureSyncJob` |
| `DART_FINANCIAL` | `00126380:2026:11012` | 처리한 계기 공시번호 중 최댓값(초기 적재는 null) | `FinancialSyncJob` |
| `SIGNAL_FINANCIAL` | `123` (기업 ID) | `fin-3:<재무 마지막 변경 시각>`. `nextCheckAt`을 씀(D-43): "최신 재무 미확인"이 아직 아니면 시간만으로 활성이 되는 날, 이미 활성이면 null | `FinancialSignalJob` |

`FinancialSignalJob`의 대상 판정은 원천 버전이 바뀐 기업뿐 아니라 **`nextCheckAt`이 지난 기업**도 포함한다(재무 변경 없이도 시간만으로 재판정, D-43). 이미 불러온 체크포인트 맵으로만 판단하므로 추가 조회는 없다.

### 4.4 `ingest.company` / `ingest.disclosure` / `ingest.financial` — 수집 Job

각 소스마다 세 클래스가 한 벌이다.

| 역할 | 기업 목록 | 공시 목록 | 재무 |
|---|---|---|---|
| 로직 (`@Component`) | [CompanySyncJob](../../src/main/java/org/stockinsight/ingest/company/CompanySyncJob.java) | [DisclosureSyncJob](../../src/main/java/org/stockinsight/ingest/disclosure/DisclosureSyncJob.java) | [FinancialSyncJob](../../src/main/java/org/stockinsight/ingest/financial/FinancialSyncJob.java) |
| 실행 시점 (`@Component`, package-private) | `CompanySyncScheduler` | `DisclosureSyncScheduler` (전체 + 장중 두 개의 `@Scheduled`) | `FinancialSyncScheduler` |
| 설정 (`@ConfigurationProperties` 레코드) | `CompanySyncProperties` | `DisclosureSyncProperties` | `FinancialSyncProperties` |
| 작업명 / 체크포인트 소스 | `company-sync` / `DART_COMPANY` | `disclosure-sync` / `DART_DISCLOSURE` | `financial-sync` / `DART_FINANCIAL` |

Scheduler는 Job의 `run()`(공시는 `runToday()`도)을 부르기만 한다. 로직은 모두 Job에 있다.

### 4.5 `company` — 기업·종목 마스터 (JPA)

| 클래스 | 책임 |
|---|---|
| [CompanyService](../../src/main/java/org/stockinsight/company/CompanyService.java) | 기업 데이터를 바꾸는 유일한 창구(클래스 수준 `@Transactional`). `upsertListed(ListedCompany)`: 사명이 바뀌면 이전 이름을 별칭으로 남기고, 범위를 판정해 ACTIVE/EXCLUDED로 저장하고, 보통주 `Security`를 upsert한다. `markDelistedExcept(codes, date)`: 목록에 없는 기업을 DELISTED로. 조회: `activeCompanyIdsByDartCorpCode()`, `activeFiscalMonthsByCompanyId()`, `countListed()` 등. 입력 DTO `ListedCompany` |
| [ListingScopePolicy](../../src/main/java/org/stockinsight/company/ListingScopePolicy.java) | 대상 범위 판정. KONEX → 제외, KOSPI·KOSDAQ 외 → `OTHER_MARKET`, 이름에 "스팩"·"기업인수목적" → `SPAC`, "리츠"·"부동산투자회사" → `REIT` |
| [Company](../../src/main/java/org/stockinsight/company/Company.java) | JPA 엔티티(`company`). 변경 메서드(`updateProfile`, `changeStatus`)는 package-private이라 `CompanyService`만 부른다. `updateProfile`이 초성(`KoreanInitials.of`)도 갱신 |
| [Security](../../src/main/java/org/stockinsight/company/Security.java) | JPA 엔티티(`security`), `@ManyToOne(LAZY) Company`. 현재는 보통주(`COMMON`), 통화 `KRW` 고정 생성 |
| [CompanyAlias](../../src/main/java/org/stockinsight/company/CompanyAlias.java) | JPA 엔티티(`company_alias`). 종류 `PREVIOUS_NAME`, `MANUAL`(추가 경로 없음) |
| [CompanyRepositories](../../src/main/java/org/stockinsight/company/CompanyRepositories.java) | package-private Spring Data JPA 인터페이스 3개 (`CompanyRepository`, `SecurityRepository`, `CompanyAliasRepository`) |
| `CompanyStatus` | `ACTIVE`, `EXCLUDED`, `DELISTED` |
| `ExclusionReason` | 제외 사유 (`KONEX`, `SPAC`, `REIT`, `OTHER_MARKET`) |
| `Market` | `KOSPI`, `KOSDAQ`, `KONEX`, `OTHER`. `fromDartCorpCls("Y"/"K"/"N"/그 외)` |
| `ShareType` | `COMMON`, `PREFERRED`. 현재 저장 경로는 `COMMON`만 만든다 |
| `KoreanInitials` | 검색용 초성 문자열 생성 (예: 삼성전자 → ㅅㅅㅈㅈ, `CompanySyncJobTest`에서 확인) |

### 4.6 `disclosure` — 공시 (JdbcClient)

| 클래스 | 책임 |
|---|---|
| [DisclosureService](../../src/main/java/org/stockinsight/disclosure/DisclosureService.java) | 공시 저장 규칙. `saveAll(List<NewDisclosure>)`: 공시번호 upsert 후 해당 기업들의 정정 공시를 원 공시에 다시 연결. `latestPeriodicTriggers()`: 재무 수집 계기. 입력 DTO `NewDisclosure`(공시번호 14자리 검증), 결과 `SaveResult` |
| [DisclosureRepository](../../src/main/java/org/stockinsight/disclosure/DisclosureRepository.java) | package-private. `upsert`(값이 같으면 갱신하지 않음, `xmax = 0`으로 신규/갱신 구분), `relinkAmendments`(SQL 한 번으로 원 공시 연결), `latestPeriodicByCompanyAndBaseName`(기업·기본 보고서명별 최대 공시번호) |
| [ReportName](../../src/main/java/org/stockinsight/disclosure/ReportName.java) | 보고서명 해석: 공백 정리, 기타정보(공백 두 칸 이상 뒤) 제거, `[기재정정]` 같은 앞 표시 분리 → `baseName`, `amendmentLabel` |
| `DisclosureType` | `PERIODIC`(A), `MAJOR_EVENT`(B), `EXCHANGE`(I). `dartCode()`로 조회 코드 |
| `Disclosure` | 조회용 레코드 (원 공시 공시번호 포함) |
| `PeriodicTrigger` | (기업 ID, 기본 보고서명, 최대 공시번호). `FinancialSyncJob`의 입력 |

### 4.7 `financial` — 재무 저장과 읽을 때 해석 (JdbcClient)

저장 쪽과 해석 쪽이 한 패키지에 있다. 저장은 원천 그대로(D-32), 해석은 읽을 때 한다.

| 클래스 | 쪽 | 책임 |
|---|---|---|
| [FinancialService](../../src/main/java/org/stockinsight/financial/FinancialService.java) | 저장 | `replace(companyId, bsnsYear, reportCode, rows, expectedPeriodEndMonth)`: 손익 행의 `thstrm_dt`로 기간 식별, 계기 기간 검증, 연결/별도별로 내용이 같으면 그대로 두고 다르면 삭제 후 삽입, 응답에서 빠진 `fs_div` 삭제. `lastChangedByCompanyId()`: 신호 재계산 대상 판단용. `currentReceiptNo(companyId, periodEnd, fsDiv)`: 계정 행을 읽지 않고 `financial_report`만 조회(D-42 무효화 판정용, 재무 요약 전체를 다시 만들지 않는다) |
| [PeriodicReportName](../../src/main/java/org/stockinsight/financial/PeriodicReportName.java) | 저장 | "사업/반기/분기보고서 (YYYY.MM)" → `QueryKey(bsnsYear, periodType, periodEndMonth)`. 분기보고서는 결산월 기준 +3개월이면 Q1, +9개월이면 Q3. 그 외 정기공시는 빈 값 |
| `FinancialAmounts` | 저장 | 금액 문자열 파싱 (쉼표, 음수, `"-"`·빈 값 → null) |
| `RawAccountLine` | 저장 | 수집기가 넘기는 계정 한 줄(문자열 그대로). `ingest`의 DTO에 `financial`이 의존하지 않도록 둔 경계 타입 |
| `StoredFinancialLine`, `StoredFinancialReport` | 공용 | 저장된 계정 행(금액 `BigDecimal`)과 보고서 한 벌(행 포함) |
| [FinancialRepository](../../src/main/java/org/stockinsight/financial/FinancialRepository.java) | 공용 | package-private. 보고서·행 insert/delete/find, `findAllByCompany`(보고서+행 조인 한 번), `lastChangedByCompanyId`, `findReceiptNoByPeriodEnd`(가벼운 단일 조회, 행 없음) |
| `PeriodType` | 공용 | `Q1`(11013), `H1`(11012), `Q3`(11014), `FY`(11011). `fromReportCode`, `reportCode()` |
| `FinancialPeriodException`, `PeriodicReportNameException` | 저장 | 기간 식별 실패·기간 불일치, 보고서명 해석 실패 |
| [FinancialSummaryService](../../src/main/java/org/stockinsight/financial/FinancialSummaryService.java) | 해석 | `summarize(companyId)` → `FinancialSummary`. 최신 기간의 연결/별도·통화로 기준 고정(D-37), 최근 12분기(1·2·3분기 실제 + 4분기 파생) + 최근 3개 사업연도, 재무상태표 항등식 점검, 데이터 이상 플래그. 저장하지 않는다 |
| [AccountMapper](../../src/main/java/org/stockinsight/financial/AccountMapper.java) | 해석 | package-private. 계정명 → 지표(매출액, 영업이익 두 이름, 당기순이익 작은 `ord`, 자산·부채·자본총계, 자본금). 유동자산 유무로 `GENERAL`/`FINANCIAL` 판별 |
| `FinancialSummary`, `QuarterEntry`, `AnnualEntry`, `MetricValue`, `PeriodKey`, `SummaryFlag`, `FinancialFormat` | 해석 | 요약 결과 구조. `PeriodKey`가 신호 근거 키 형식(`flowBasisKey`, `stateBasisKey`, `debtJumpBasisKey`)과 표시 키를 만든다 |
| [FlowRuns](../../src/main/java/org/stockinsight/financial/FlowRuns.java) | 해석 | 같은 부호가 이어진 기간 수(spec/signals-financial.md §3.7 "지속", D-61). `revenueYoySign`·`marginYoySign`(분기·연간, 4분기 파생처럼 비교하지 않는 기간은 부호 없음), `signedQuarterRun`(주어진 분기부터 과거로, 부호 없음·빈 기간·반대 부호에서 멈춤), `signedAnnualRun`(앞 사업연도 끝 다음 날에 시작해야 이어짐). 신호의 지속과 재무 쉬운 설명의 흐름 사실(`revenue_yoy_run`·`operating_loss_run`)이 이 한 정의를 함께 쓴다 |

### 4.8 `signal` — 재무 신호

| 클래스 | 책임 |
|---|---|
| [FinancialSignalJob](../../src/main/java/org/stockinsight/signal/FinancialSignalJob.java) | 재무가 바뀐 기업 + `nextCheckAt`이 지난 기업(D-43)만 골라 기업 하나당 트랜잭션 하나로 요약 → 계산 → 반영. 반영 직후 그 기업의 최신 기간 근거 키(`stateBasisKey()`, 재무가 없으면 null)를 `applyFinancial`에 넘긴다. 철회 급증 경고 |
| `FinancialSignalScheduler`, `FinancialSignalProperties` | 실행 시점, `app.signal.financial-signal.*` |
| [FinancialSignalCalculator](../../src/main/java/org/stockinsight/signal/FinancialSignalCalculator.java) | `calculate(FinancialSummary, asOf)` → `CalculationResult(drafts, staleRecheckAt)`. static 순수 함수. DB·Spring 없음. `assessStale(latest, asOf)`(package-private) → `StaleAssessment(stale, deadline, nextRecheckDate)`: 다음 기간 종료월 말일(`YearMonth...atEndOfMonth()`) + 기한일(60·120일) + 유예 7일로 판정한다(D-43). 이미 미확인이면 `nextRecheckDate=null`(그다음엔 규칙 버전·재무 변경만이 계기). 매출·영업이익률 변화 신호의 `persistence`는 그 기간까지 같은 부호의 전년 동기 변화가 이어진 수(`FlowRuns`, 분기끼리·연간끼리, 문턱값 아님)다. 전환·부채비율 급등·데이터 한계는 null이다(D-61: 영업이익의 연속은 영업적자 지속, 부채비율은 기준점이 같아 셈이 무의미). 영업적자 지속·자본잠식은 구간의 분기 수 |
| [FinancialRuleCatalog](../../src/main/java/org/stockinsight/signal/FinancialRuleCatalog.java) | 문턱값·심각도 구간 상수와 `RULE_VERSION = "fin-3"`(2026-09-27, 변화 신호 지속을 채우며 올림 — D-61. 지문에 규칙 버전이 들어가므로 AI 초안 지문도 바뀐다). `QUARTERLY_DEADLINE_DAYS=60`, `ANNUAL_DEADLINE_DAYS=120`, `STALE_GRACE_DAYS=7`은 서비스 내부 데이터 품질 판정 기준이며 실제 법정 제출기한이 아니다(D-43). 값을 바꾸면 버전을 올리고, 버전이 바뀌면 모든 기업이 다시 판정된다 |
| [CompanySignalService](../../src/main/java/org/stockinsight/signal/CompanySignalService.java) | 신호 저장 규칙. `applyFinancial(companyId, drafts, ruleVersion, latestPeriodStateBasisKey)`: 초안을 자연키로 upsert(ACTIVE/PAST). 이번 초안에 없는 기존 신호는 WITHDRAWN이 기본이지만, `FIN_DATA_STALE`이고 그 근거 키가 `latestPeriodStateBasisKey`보다 앞선 기간이면(문자열 비교, `fiscalYearStart:Qn` 형식이라 사전식 비교가 시간순과 같다) PAST로 둔다(해소는 철회가 아니다, D-43). 행은 지우지 않는다(D-36) |
| [CompanySignalRepository](../../src/main/java/org/stockinsight/signal/CompanySignalRepository.java) | package-private. upsert(상태가 바뀔 때만 `status_changed_at` 갱신), withdraw, `markPast`(D-43 해소 전용, 상태가 이미 PAST가 아닐 때만 `status_changed_at` 갱신), 조회. `calc_values`·`watch_metrics`는 자체 `JsonMapper`로 JSON 문자열을 만들어 `jsonb`로 캐스팅 |
| `SignalDraft` | 계산 결과(저장 전). 대리키 없음 |
| `CompanySignal` | 조회용 레코드 |
| `SignalType` | 변화·상태 6종(`FIN_REVENUE_CHANGE`, `FIN_OPERATING_MARGIN_CHANGE`, `FIN_OPERATING_TURN`, `FIN_DEBT_RATIO_JUMP`, `FIN_OPERATING_LOSS_STREAK`, `FIN_CAPITAL_IMPAIRMENT`) + 데이터 한계 7종(`FIN_DATA_*`) |
| `SignalNature`, `SignalDirection`, `SignalSeverity`, `SignalStatus` | 상태/변화, 긍정/부정/불확실, 낮음/중간/높음, 활성/이력/철회 |

### 4.9 `analysis` — AI 재무 쉬운 설명

첫 AI 기능이다(financial_explain 하나뿐). 입력은 코드가 만든 사실표·신호 참조뿐이고 원천 행·공시 원문·내부 ID·철회 신호는 넣지 않는다(D-38). 숫자·기간·신호는 AI 글에서 `{fin.*}`/`{per.*}`/`{sig.*}` 토큰으로만 나온다(D-39). 지문은 값이 아니라 식별자 집합의 해시다(D-40, D-08).

| 클래스 | 책임 |
|---|---|
| [FinancialExplainInputBuilder](../../src/main/java/org/stockinsight/analysis/FinancialExplainInputBuilder.java) | `build(companyId)` → `Optional<BuildResult>`(AI 입력 + 값 스냅샷 + 지문). 재무 보고서가 없으면 빈 값. `FinancialSummaryService.summarize()` + `CompanySignalService.findByCompany()`로 최신 손익·연간·재무상태·흐름 사실을 만들고, 활성 신호 전부 + 이력 신호 최대 4개를 심각도·최근성 순으로 골라 기간·주제로 묶는다(spec/financial-explain.md §4.4.2). 재무상태표가 불일치(`FIN_DATA_INCONSISTENT` 활성)하면 재무상태 지표 전체를 `unavailable`로 두고 `structure` 섹션을 뺀다. `history` 섹션은 이력(PAST) 신호가 있을 때만 연다 — 흐름 사실(`revenue_yoy_run`·`operating_loss_run`)은 최신 기간까지 거꾸로 센 "지금 이어지는" 값이라 그 자체로는 `history`를 열지 않는다(`sales_profit`에서만 쓴다, D-45). 신호마다 그 기간을 `periods`에도 반드시 라벨로 남긴다 — 그 기간에 등록된 사실이 없어도(최신 기간도 사업연도 대표 기간도 아닌 과거 분기의 이력 신호) `{per.기간}` 토큰을 쓸 수 있어야 하기 때문이다(work/3-4-verification-1.md §7.4.7). `revenue`가 `ACCOUNT_MISSING`이면 포맷과 무관하게 `doNotMention`에도 "매출"을 넣는다 — `FINANCIAL` 포맷만 자동으로 넣던 것을 `GENERAL`인데 계정이 없는 경우까지 넓혔다(D-51). 같은 데이터면 바이트 단위로 같은 JSON. **D-54(섹션 배정, `INPUT_BUILDER_VERSION = "fx-input-3"`)**: (1) 신호마다 `section`을 정한다(`sectionOf`: 활성 → 주제 섹션 `sales_profit`/`structure`, 이력 → `history`). `period`는 설명 기준 기간이다: 활성은 최신 기간(`latest.periodKey`), 이력은 발생 기간(`derivePeriodKey`). 활성 신호의 `factKeys`는 최신 기간(없으면 흐름 사실의 최신 분기) 사실을 가리킨다 — 발생 기간 기준이던 이전에는 상태 신호·같은 회계연도 부채비율 급등의 `factKeys`가 늘 비어 있었다. (2) `buildGroups`는 (섹션, 기준 기간, 주제)로 묶어 활성·이력이 한 묶음이 되지 않게 하고, 활성 변화 → 활성 상태 → 이력, 같은 무리 안에서는 최고 심각도 → 최근 기간 → 먼저 나온 순으로 정렬한다. (3) `addBalanceFacts`는 재무상태 고정 구성만 만든다: 일반형 `debt_ratio`·`debt_ratio_diff`(이름 "부채비율 변화(전기말 대비)"), 부채비율이 없거나 금융형이면 `total_equity`, 활성 자본잠식이 있을 때만 `total_equity`·`capital_stock`(최대 4개). `debt_ratio_prior_end`·`total_assets`·`total_liabilities`·`impairment_ratio`는 사실표와 값 스냅샷에서 빠졌다. (4) `assignSectionFacts`가 `sectionFacts`를 만든다: `overview` = CHANGED면 첫 묶음 신호의 변화량 사실(`isChangeFact`, 최대 3), NONE이면 `revenue_yoy` → `operating_margin_diff` → `debt_ratio_diff` 중 처음 있는 하나. `structure` = 재무상태 고정 구성에서 개요 사실을 뺀 나머지. (5) `resolveSections`는 배정된 사실이나 신호가 있을 때 섹션을 연다 — 재무상태표 불일치로 사실이 없어도 활성 재무 구조 신호가 있으면 `structure`를 연다(배지만, D-52). **D-54 보완·D-55(`fx-input-4`, 개요 전환 사실은 `fx-input-5`)**: (6) `FactCollector`가 흑자·적자 사실을 만든다 — `net_income_status`(순이익에만, 값 ≠ 0), `operating_income_turn`·`net_income_turn`(`putTurn`: 같은 보고서 최신·전년 부호가 다르고 둘 다 ≠ 0, 신호와 무관). `putState`는 값 스냅샷에만 표시 문구(닫힌 집합 "흑자"/"적자"·"적자에서 흑자로"/"흑자에서 적자로")를 두고, 스냅샷 원값을 부호의 근거 값으로 둔다(단위 `STATE_UNIT`) — AI 입력의 `Fact`에는 표시 문구가 없다(D-59, 아래). (7) `assignSectionFacts`가 네 섹션을 모두 배정하고 `Assignment(sectionFacts, relations)`를 돌려준다: 개요 = 첫 묶음 신호의 변화량 사실(`isChangeFact`)과 전환 사실(`isTurnFact`), `sales_profit` = `assignSalesProfit`의 우선순위(규모 → 활성 신호 근거 → 순이익+상태/전환(관계 단위) → 영업이익률 → 흐름(연속 적자 → 매출 연속) → 전년 영업이익 비교(부호 같으면 전년 값 + 둘 다 흑자면 R2, 부호 바뀌고 신호 없으면 전환 사실) → 최근 사업연도 사실 하나, 상한 6), `history` = 빈 목록. `FactCollector.assignable`은 사실 이름이 `doNotMention`이나 `unavailable`의 이름(`FinancialExplainValidator.UNAVAILABLE_METRIC_NAMES`, 규칙 8과 같은 대응)을 포함하면 배정하지 않는다. (8) 사실표·값 스냅샷·신호 `factKeys`·기간 라벨은 배정된 사실로 좁힌다. 이력 신호의 `factKeys`는 비운다. 지문의 공시번호도 배정된 사실의 것뿐이다. (9) `buildGroups`는 이력 묶음을 설명 기준 기간 종료일(`periodEnd(PeriodKey)`) 내림차순으로, 같은 기간끼리만 심각도 순으로 정렬한다. `SIGNAL_FACT_KEYS`에서 매출·영업이익률의 전년 원값을 빼고 전환 신호는 `operating_income_turn`을 가리킨다. **D-59(`fx-input-6`)**: (10) `FinancialExplainInput.Fact`에서 `display`를 없앴다(`key`·`name`·`sign`·`period` 4필드). `PeriodLabel`은 `label` 대신 `kind`(QUARTER·ANNUAL) — 값 스냅샷(`factSnapshots`·`periodLabels`)에는 표시 문구·라벨이 그대로 남는다(렌더링용). (11) 사실 이름에 실제 부호로 정해지는 방향을 넣는다(`directionalName` 헬퍼): "매출 증가율"/"매출 감소율", "영업이익률 상승폭"/"하락폭", "부채비율 상승폭"/"하락폭"(전 이름 "부채비율 변화(전기말 대비)"를 "…(지난 회계연도 말 대비)"로), "매출 증가/감소 연속 분기 수". 전환 사실 이름의 "전년 같은 분기"/"전년 같은 기간"은 `latest.isAnnual`로 정확히 고른다. (12) `build()`에서 `latestAnnualKey`(우선순위 7 "최근 사업연도 사실"의 후보)는 `summary.annual().get(0).irregular()`면 `null`이다 — 12개월이 아닌 회계연도(결산기 변경)를 그 우선순위에서 뺀다(2386이 실제 사례: 분할 신설 뒤 2개월짜리 사업보고서가 있었다). `PeriodLabels.ofAnnual`도 `periodEnd`로 12개월이 아닌 기간을 가려 실제 시작~끝 달로 쓴다("2025.11~12 회계연도"). `ofQuarter`는 비12월 결산에서 회계연도 분기 번호("(1분기)")를 빼고 달만 쓴다("2026년 4~6월"). 신호 참조의 `persistence`는 상태 신호만 넣는다(`aiPersistence`) — 변화 신호의 지속(D-61)은 흐름 사실 토큰으로만 주고 입력 계약 `fx-input-6`을 그대로 둔다. 흐름 사실의 셈은 `FlowRuns`를 쓴다 |
| [PeriodLabels](../../src/main/java/org/stockinsight/analysis/PeriodLabels.java) | package-private. 기간 라벨(12월 결산 "2026년 2분기" / 그 밖 "2026.04~06(1분기)")과 값 표시 형식(비율은 부호 있는 소수 1자리 %, 개수는 정수). 금액은 통화와 관계없이 한국어 수 단위(조·억·만, 1만 미만은 콤마 정수) + 통화명(KRW=원, CNY=위안, USD=달러, JPY=엔, GBP=파운드, 그 밖은 " "+코드)이고 환산하지 않는다(D-41). 억·조 단위는 반올림 결과가 다음 단위의 경계(10000)에 닿으면 그 단위로 다시 계산해 올린다(예: 9,999.95억 → 1.0조). `formatForReader(metric, value, unit)`(D-56)는 렌더러용이다 — `formatValue`와 같되 수준 비율(`_yoy`가 아닌 %)에는 '+'를 붙이지 않는다. AI 입력의 표시 값은 아직 `formatValue`(모든 %·%p에 부호)이고, 다음 입력 구성 변경 때 맞춘다 |
| [KoreanText](../../src/main/java/org/stockinsight/analysis/KoreanText.java) | package-private, 순수 계산(D-56·D-58). `splitSentences` — 검증기와 렌더러가 함께 쓰는 문장 경계(D-53 규칙을 검증기에서 옮김, 판정은 같다). `isLeadingBadge(sentence, 배지시작)`·`isNameBadge(sentence, 배지끝)` — 배지가 문장·절의 앞머리인지("… 신호" 이름 자리와 겹칠 수 있어 이름을 먼저 본다), 검증기 규칙 14와 렌더러의 배지 표기가 함께 쓴다. `adjustParticle(값, 뒤 글)` — 토큰 바로 뒤의 조사 쌍(으로/로, 이었/였, 이에요/예요, 을/를, 은/는, 과/와, 이/가)을 렌더링 값의 끝소리에 맞춘다. 끝소리(`finalConsonantOf`): 마지막 한글 음절(끝의 괄호·따옴표·공백은 건너뜀), %·%p는 받침 없음, 숫자는 읽기(1·7·8 ㄹ, 3 ㅁ, 6 ㄱ, 2·4·5·9 없음), 0이나 영문으로 끝나면 모름(바꾸지 않음). 'ㄹ' 받침 뒤는 "로". "이고"·"는데"처럼 조사 뒤가 한글이면 다른 말의 시작으로 보고 바꾸지 않는다 |
| [Fingerprint](../../src/main/java/org/stockinsight/analysis/Fingerprint.java) | package-private. `compute(공시번호 집합, 신호 목록, unavailable 목록, 입력 구성 버전)` → SHA-256 해시. 정렬해 이어 붙인 문자열을 해시하므로 값 자체가 아니라 "무엇을 썼는가"가 바뀌어야 지문이 바뀐다(D-08, D-40, D-42). 데이터 한계 신호 코드(`FIN_DATA_*`) 자체가 아니라 `unavailable`(지표·사유) 집합을 쓴다 — 날짜로 바뀌는 "최신 재무 미확인" 같은 코드가 바뀌어도 지문은 그대로다 |
| [FinancialExplainInput](../../src/main/java/org/stockinsight/analysis/FinancialExplainInput.java), [ValueSnapshot](../../src/main/java/org/stockinsight/analysis/ValueSnapshot.java), [FinancialExplainOutput](../../src/main/java/org/stockinsight/analysis/FinancialExplainOutput.java) | AI 입력 계약, 렌더링용 값 스냅샷(원값·출처 공시번호 포함, AI에는 안 감), AI 출력 계약(`overview`/`sales_profit`/`structure`/`history`, 없는 섹션은 null). D-54로 `SignalRef.section`, `Group.section`, `sectionFacts`(섹션 → 사실 키)가 추가됐다. D-55로 `relations`(`Relation(type, factKeys, value)`: `OPERATING_INCOME_DIRECTION`=R2 UP/DOWN/SAME, `STATE_DIFFERENCE`=R5 DIFFERENT)가 추가됐다 — 토큰이 아니고 값 스냅샷에도 없다. 관계가 없는 입력을 만드는 11인자 생성자(`relations = List.of()`)가 따로 있다(테스트·이전 모양). `fx-input-4`부터 `sectionFacts`는 네 섹션 모두다. 이 필드가 없는 과거 행을 `AnalysisRepository`가 읽으면 null이 된다(`fx-input-2` 이하 `section`·`sectionFacts`, `fx-input-3` 이하 `relations`) |
| [AnalysisAttempt](../../src/main/java/org/stockinsight/analysis/AnalysisAttempt.java), [NewAnalysis](../../src/main/java/org/stockinsight/analysis/NewAnalysis.java) | `AnalysisAttempt`: AI 호출 한 번의 기록(시도 번호, `Outcome` = `SUCCESS`·`REJECTED`·`INVALID_JSON`·`LLM_OUTPUT_REJECTED`·`CALL_FAILED`, 실패 규칙, 파싱한 출력, JSON 파싱 실패 원문, 부가 사유, 검증기 버전). `NewAnalysis`의 마지막 필드 `attempts`로 `analysis.attempts`에 저장된다. 읽기 모델(`Analysis`)에는 없다 — 게시·렌더링·무효화가 읽지 않는 분석 전용 기록이다(D-53) |
| [FinancialExplainValidator](../../src/main/java/org/stockinsight/analysis/FinancialExplainValidator.java) | `validate(output, input)` → `ValidationResult`(통과 여부 + 실패 규칙 번호 목록). spec/financial-explain.md §4.4.7 규칙 1~13(스키마·토큰·숫자·반복·강도어·증감 방향·신호 섹션과 시간 순서·`unavailable`·금지 표현·분량·섹션 배정·흑자적자·코드가 주지 않은 관계) 전부 코드 상수·정규식으로 판정. 규칙 7은 흐름 사실 토큰(`revenue_yoy_run`·`operating_loss_run`)이 `history`에 나오는 경우도 실패로 잡는다(D-45). 규칙 6은 한 문장 안에 부호가 다른 변화 토큰(`_yoy`·`_diff`) 둘이 섞여 있으면(방향이 엇갈리는 묶음을 나란히 쓴 정상 문장) 판정을 건너뛴다 — 문장 안 마지막 토큰의 부호만으로 전체를 판정하면 이런 정상 문장을 오탐하는 결함이 있었다(work/3-4-verification-1.md §7.4.7). 문장 분리(`KoreanText.splitSentences`, 2026-09-27 전에는 검증기의 `SENTENCE_SPLIT`)는 '요'·'다'·'.' 뒤 공백·끝을 경계로 보되 "보다"·"주요"·"필요"·"중요"로 끝나는 자리는 제외한다 — 이 말들을 경계로 세면 한 문장이 여러 문장이 되어 규칙 10·5는 오탐, 규칙 6은 미탐이 났다(D-53). 규칙 6의 증감 사전(`POSITIVE_WORDS`·`NEGATIVE_WORDS`)은 "높아지"·"낮아지"·"좋아지"·"나빠지" 각 어간의 '지+었' 축약형(져·졌·진·질·짐·집)도 포함한다 — 이 축약형이 해요체 과거형("높아졌어요")의 기본형인데 어간 문자열이 활용형 안에 그대로 남지 않아 방향 불일치를 놓치고 있었다(§7.4.19, 새 D-결정 없이 §4.4.7·§4.4.8이 이미 정한 사전 조정 절차로 처리). **D-54(`VERSION = "fxv-3"`)**: 규칙 6은 변화량 토큰이 있는 문장에 그 부호와 같은 증감 어휘가 반드시 있어야 한다(부호가 섞이면 두 방향 모두) — "영업이익률은 {…_diff} 수준이에요"를 막는다. 규칙 7은 신호의 `section`을 따른다(자기 섹션 + 활성이면 `overview`). `section`이 null인 과거 입력은 상태 기준(이력은 `history`에서만, 활성은 그 밖)으로 판정한다. 규칙 11(`checkSectionFacts`)은 `sectionFacts`에 키가 있는 섹션(`overview`·`structure`)에서 목록 밖 사실 토큰을 막는다. `sectionFacts`가 null이면 검사하지 않는다. 판정 방식 버전 `VERSION`을 시도 기록에 남긴다. **D-55(`fxv-4`, 내부 용어는 `fxv-5`)**: 단어 검사는 토큰을 뺀 문장(`words`)에서 한다. 규칙 6 — 증감 근거(`directionEvidence`) = 변화 토큰·`revenue_yoy_run` 부호, 신호 대응표(`signalArithmeticSign`: 매출·영업이익률은 방향대로, 부채비율 급등은 +), R2 관계(두 토큰이 같은 문장). 근거 방향이 하나로 모이면 반대 어휘·(SAME이면) 모든 증감 어휘가 실패. 증감 사전(`INCREASE_WORDS`·`DECREASE_WORDS`, '지다' 축약형 포함)과 평가어 사전(`GOOD_WORDS`·`BAD_WORDS`)을 나눴고 평가어는 방향이 같은 신호 토큰이 있어야 한다. 전환 신호의 흑자·적자 단어 검사는 없앴다. 규칙 12(`checkState`) — 토큰 밖 흑자·적자·손실·손해 금지(연속 영업적자 토큰이 있는 문장의 "영업적자"만 예외), 전환 서술어는 전환 토큰과, 이어짐 서술어는 전환 토큰 없이. 규칙 13(`checkRelations`) — 늘 금지(비슷·같은 수준·훨씬·폭), 근거 없는 증감 어휘, "보다" 뒤 크기 어휘는 증감 근거나 자본잠식 배지 필요("그보다 앞선"은 크기 어휘가 없어 해당 없음), 대조어는 전환 토큰이나 R5 순이익 사실 토큰 필요. 규칙 7 — 시간 어휘는 `history`에서만, `checkHistoryOrder`로 `history` 기간 토큰의 종료일(`periodEnd(String)`, 형식이 다르면 건너뜀)이 앞보다 늦으면 실패. 규칙 9 — "내부 용어 노출"(섹션·입력 필드 이름, "섹션"·"토큰"). 규칙 11은 `sectionFacts`에 키가 있는 모든 섹션에 적용된다. `UNAVAILABLE_METRIC_NAMES`(규칙 8 이름 대응)는 입력 구성기와 공유한다. **D-56(`fxv-6`)**: 규칙 14(`checkBadgePosition`) — 배지는 문장 맨 앞(앞에 배지만 있어도 됨), 절의 맨 앞(앞 절이 쉼표·"고·며·면서·지만·는데"로 끝나거나 `{per}에는`·`{per}에`·`{per} 기준` 뒤), "… 신호" 앞(나열된 배지를 거쳐도 됨)에만. 규칙 7 — 흐름 사실 토큰이 있는 문장에 `{per}` 토큰이 있으면 실패(사업보고서 기간과 파생 4분기가 같은 기간 키). 문장 분리는 `KoreanText.splitSentences`로 옮겼다. **D-57(`fxv-7`)**: `checkSectionFacts`가 `overview`일 때 완전성도 본다 — `sectionFacts.overview`에 배정된 사실을 하나라도 쓰지 않으면 규칙 11 실패(배정 밖 사실 금지는 그대로). `sales_profit`·`structure`는 완전성을 요구하지 않는다(872가 첫 묶음의 영업이익률 변화를 버린 계약 충돌을 개요에서만 닫는다, work/3-4-verification-2.md §7.4.31). **D-58(`fxv-8`)**: 규칙 14가 `checkBadgePosition`(자리)에 더해 `checkBadgeMetricBinding`(지표 결합)도 본다 — 앞머리 배지 뒤 같은 절의 첫 사실 토큰이 `SIGNAL_METRIC_FAMILY`(신호 유형 → 지표 묶음) 안에 있어야 하고, 그 섹션에 그 지표 묶음 사실이 배정돼 있으면 사실 없는 절도 실패다(이름 배지는 대상 아님). 규칙 13이 `checkMetricJoining`(`RO_JOIN` 정규식, `METRIC_ROOTS`로 변형을 한데 묶음)으로 서로 다른 지표의 "(으)로" 잇기를 잡고, `ALWAYS_FORBIDDEN_RELATIONS`에 조금·약간·살짝을 더했다. 규칙 6은 부호가 다른 변화 토큰이 한 문장에 섞이면 `checkDirectionPerClause`로 절 단위도 본다(문장 전체 기준으로는 어휘 두 개가 다 있으면 통과하던 틈, 820). 규칙 5의 `INTENSITY_WORDS`에 많이·상당히·꽤. 배지 자리 판정(`isLeadingBadge`·`isNameBadge`)은 `KoreanText`로 옮겨 렌더러와 공유한다. DB·Spring 없는 순수 클래스(`new`로 직접 생성, 빈 아님) |
| [FinancialExplainPrompt](../../src/main/java/org/stockinsight/analysis/FinancialExplainPrompt.java) | package-private. `classpath:prompts/`에서 스타일 가이드+역할·규칙 프롬프트, 스키마 JSON을 기동 시 한 번 읽어 상수로 들고 있는다(`PROMPT_VERSION = "fx-v13"`, `SCHEMA_VERSION = "fx-schema-1"`). `userMessage(inputJson, 실패규칙목록)`이 데이터 구분자(`<data>`)로 감싸고, 재시도 때는 실패 규칙마다 `retryGuidance(규칙)`의 안내 한 줄을 덧붙인다 — 검증기 번호(§4.4.7)를 프롬프트가 쓰는 표현으로 옮긴 것이고, 모르는 규칙이면 `IllegalArgumentException`(D-53). AI 출력 원문은 되돌리지 않는다. fx-v5의 시스템 프롬프트는 fx-v4와 같고 재시도 문구만 다르다. fx-v6(D-54)은 `v1.md`의 입력 설명(`section`·`sectionFacts`), 개요·`structure`·`history` 규칙을 바꾸고 D-46 예시 두 개("{…_diff} 수준이에요", "예: `net_income`")를 뺐으며, 재시도 안내에 규칙 11을 더했다. fx-v7(D-55)은 `v1.md`에 "관계 서술" 절(관계 표·늘 쓰지 않는 관계·평가어)을 넣고 예시를 11개에서 8개로 교체했으며, 재시도 안내 6·7·11을 바꾸고 12·13을 더했다. fx-v8은 연간 사실을 `{per}` 토큰으로 가리키게 하고 "나란히 놓기" 문구를 "차례로 적기"로 바꿨다(글자 그대로 옮겨지는 문제). fx-v9는 개요 전환 사실과 R2 한 문장 쓰기를, fx-v10은 "개요에 쓴 사실은 다른 섹션에서 다시 쓰지 않는다"를 더했다(§7.4.28). fx-v11(D-56)은 배지 위치 규칙, 흐름 사실 문장에 `{per}`를 붙이지 않기·최신이 연간이면 "분기로 세면", 비교 기준 "지난 회계연도 말"("전기말" 쓰지 않음), 두 값은 쉼표로 차례로, 전환 예시 "전년 같은 분기에 비해 {전환} 돌아섰어요"를 넣고 재시도 안내 7을 고치고 14를 더했다(예시 수 8개 그대로). fx-v12(D-57)는 개요 행의 "핵심 한 가지만"을 "`sectionFacts.overview`를 하나도 빠짐없이"로 바꾸고, NONE 고정 문구의 "…기준 이 기간 보고서 기준으로…" 중복을 "{per} 기준으로는…"으로 정리하고, "로"는 같은 지표의 값+비교에만 쓴다는 구분과 개요 2사실 대조 예시·ANNUAL 분기 기준 예시를 더했다(예시 10개, 재시도 안내 11을 고쳤다). **fx-v13(D-58·D-59)**: 입력 설명에서 `display`·`label` 언급을 빼고 `kind`로, R9 예시를 배지+사실 부착형("{sig.S1} 매출은 {…}로 전년 같은 분기보다 늘었지만, {sig.S2} 영업이익률은 {…}로 낮아졌어요")으로 교체(예시 수는 그대로), 배지는 자기 지표의 사실 앞에 둔다는 문구, 강도 표현·정도 어휘 목록(많이·상당히·꽤 / 조금·약간·살짝), `sales_profit`의 "최근 사업연도 사실은 마지막 문장"을 더하고, 재시도 안내 3(맨 앞 정렬·설명)·6(방향 없는 말 금지)·13("로" 금지·정도 어휘)·14(자기 지표)를 고쳤다. `schema.json`의 `history` 설명을 D-45·D-54 보완에 맞게 고쳤다(스키마 구조는 같아 `SCHEMA_VERSION`은 그대로) |
| [FinancialExplainProperties](../../src/main/java/org/stockinsight/analysis/FinancialExplainProperties.java) | `app.analysis.financial-explain.*` 바인딩: cron, `run-on-startup`, `golden-set-company-ids`, `daily-budget-usd`/`monthly-budget-usd`(USD, 모든 분석 종류 공유), `publish`(기본 false) |
| [FinancialExplainJob](../../src/main/java/org/stockinsight/analysis/FinancialExplainJob.java) | 작업명 `analysis-financial-explain`. 대상 = `company.ai_covered` ∪ 골든셋 목록(지금은 `ai_covered`가 전부 false라 골든셋만) 순차 처리. 대상마다 입력 구성 → `AnalysisService.decide()`로 건너뜀/재시도 판단 → LLM 호출 → 검증 → 실패 시 실패 규칙의 안내만 프롬프트에 덧붙여 1회 재생성 → 시도별 기록(`attempts`)과 함께 저장. `publish=true`일 때만 검증 통과 결과를 `AnalysisService.publish()`로 승격한다. 예산 초과 시 남은 대상을 건너뛰고 `PARTIAL` |
| [AnalysisService](../../src/main/java/org/stockinsight/analysis/AnalysisService.java) | 저장 규칙(§6.3 상태 전이)만 담당, 생성·검증은 하지 않는다. `decide()`: 같은 지문의 게시·숨김·초안이 있으면 `SKIP_UP_TO_DATE`, REJECTED/FAILED면 24시간 지나야 `PROCEED`(`SKIP_BACKOFF`), 같은 지문 누적 3회 실패 + 프롬프트 버전 그대로면 `SKIP_MAX_FAILURES`. `spentSince(시각)`: 예산 확인용 비용 합(분석 종류 무관) |
| [AnalysisRepository](../../src/main/java/org/stockinsight/analysis/AnalysisRepository.java) | package-private. `insert`(시도 기록 `attempts` 포함), `publish`(기존 게시본 내리고 새 행 올림, 두 update — 상태를 확인하지 않으므로 거절된 출력은 `result_json`에 넣지 않는다), `findCurrent`, `findLatestByFingerprint`, `countFailuresByFingerprint`, `sumCostSince`. `jsonb` 컬럼은 Jackson 3 `JsonMapper`로 문자열화. 조회(`SELECT`)는 `attempts`를 읽지 않는다 |
| [AnalysisRenderer](../../src/main/java/org/stockinsight/analysis/AnalysisRenderer.java) | 화면 조립 전 단계(화면 자체는 없음). `render()`: 토큰 → 값, 모르는 토큰은 예외. 사실 값은 스냅샷 원값·단위로 렌더링 때 서식을 만든다(`readerDisplay`가 `PeriodLabels.formatForSentence` 사용, D-41·D-56·D-58 — 단위가 %·%p·분기·그 사실의 통화일 때만. 원값이 없거나 상태·전환 사실처럼 단위를 모르면 저장된 표시 문자열). 문장 안의 변화량(%p, `_yoy`의 %)은 부호 없이 보인다(D-58) — 방향은 절의 증감 어휘가 말하고 검증기 규칙 6이 일치를 보장하므로 부호는 겹말이다. 수준값(그 밖 %·금액)은 그대로(음수는 값이라 남는다). 토큰 바로 뒤 조사는 `KoreanText.adjustParticle`로 맞춘다(이스케이프 전). `{sig}` 배지는 `KoreanText.isNameBadge`가 아니고 `isLeadingBadge`면(앞머리) `[이름]`으로 괄호 쳐 값 뒤에 붙어 읽히지 않게 한다(D-58, "매출 큰 폭 감소 매출은…"이 아니라 "[매출 큰 폭 감소] 매출은…") — 화면(Thymeleaf)이 아직 없어 칩 대신 문자열 표기다. `renderSentences()`(D-56): 원문을 검증기와 같은 경계로 나눈 뒤 문장마다 `render()` — 화면은 문장마다 줄을 바꾼다. 결과 **전체**를 HTML 이스케이프한다 — 2026-09-27 전에는 토큰 값만 이스케이프하고 토큰 사이의 AI 문장은 그대로 붙였다(AI 출력도 외부 입력, §7.4.28). 흑자·적자 상태·전환 사실도 다른 사실처럼 표시 값으로 바뀐다. `isInvalidated(current, companyId)`(D-42): 입력 전체를 다시 만들지 않는다. 스냅샷이 참조한 신호의 자연키로 `CompanySignalService.findByCompany`(기업당 한 번) 조회 후 상태·방향만 비교(철회되었거나 방향이 바뀌면 무효화)하고, 참조한 사실의 (기간 종료일, 기준) 조합마다 `FinancialService.currentReceiptNo`로 현재 공시번호만 확인(다르거나 없으면 무효화)한다. 새 보고서 도착·활성→이력 전환·심각도/규칙 버전 변경만으로는 무효화하지 않는다. `limitMessages(companyId)`: 스냅샷이 아니라 현재 활성 `FIN_DATA_*` 신호로 매번 새로 만든다(헤더는 렌더링 시점 현재 값, D-42) |
| [StaticContent](../../src/main/java/org/stockinsight/analysis/StaticContent.java) | package-private. 신호 유형·방향별 배지 이름, 제한 코드별 문구, AI 생성 표시·무효화 안내 문구. 전부 코드 상수(AI가 만들지 않음) |
| [GoldenSetDumpRunner](../../src/main/java/org/stockinsight/analysis/GoldenSetDumpRunner.java) | `@Profile("goldenset")`인 `ApplicationRunner`. AI를 호출하지 않는다. 골든셋 목록의 §4.4.2 입력 JSON을 로컬 DB에서 만들어 `src/test/resources/golden/financial_explain/{companyId}.json`에 쓴다. **구현하지 않은 것**: 저장된 입력 파일을 다시 읽어 재생성하는 경로(work/3-4-financial-explain.md §7.2의 7번) |
| `AnalysisKind`, `AnalysisStatus`, `TargetType` | `FINANCIAL_EXPLAIN`(하나뿐) / `DRAFT`·`PUBLISHED`·`REJECTED`·`FAILED`·`HIDDEN` / `COMPANY`(하나뿐) |

### 4.10 `analysis.llm` — Anthropic SDK 어댑터

| 클래스 | 책임 |
|---|---|
| [LlmClient](../../src/main/java/org/stockinsight/analysis/llm/LlmClient.java) | `generate(systemPrompt, userInput, jsonSchema)` → `LlmResult`. 실패는 모두 `LlmException`(또는 그 하위 `LlmOutputRejectedException`). 테스트는 이 인터페이스를 `FakeLlmClient`로 바꾼다 |
| [AnthropicLlmClient](../../src/main/java/org/stockinsight/analysis/llm/AnthropicLlmClient.java) | 실제 구현. 인증키 없으면 호출 전에 `LlmException`(네트워크 호출 없음). 원시 JSON 스키마 문자열을 고전 Jackson 2 `JsonNode`로 읽어 `JsonOutputFormat.Schema`의 property bag으로 옮긴다. 시스템 프롬프트는 프롬프트 캐싱(`CacheControlEphemeral`)을 건 `TextBlockParam` 하나로 보낸다. `StopReason.MAX_TOKENS`/`REFUSAL`이면 사용량은 챙기고 `LlmOutputRejectedException`으로 던진다(§7.3 검증 실패와 동급). 응답의 `Usage`로 비용을 계산한다(`app.llm.*-cost-per-million`). **인증키·프롬프트 전문은 예외 메시지·로그에 남기지 않는다**(예외 메시지는 SDK 예외 클래스 이름만) |
| [LlmResult](../../src/main/java/org/stockinsight/analysis/llm/LlmResult.java) | `outputJson`(출력 거부 시 null), `model`, 토큰 3종, `costUsd` |
| [LlmException](../../src/main/java/org/stockinsight/analysis/llm/LlmException.java), [LlmOutputRejectedException](../../src/main/java/org/stockinsight/analysis/llm/LlmOutputRejectedException.java) | 호출 자체 실패(재시도 안 함, FAILED로 기록) / 호출은 됐지만 응답을 못 씀(과금된 사용량을 `partialUsage()`로 들고 있음, 검증 실패처럼 1회 재시도) |
| [LlmProperties](../../src/main/java/org/stockinsight/analysis/llm/LlmProperties.java) | `app.llm.*` 바인딩(`api-key`, `model`, `max-tokens`, `timeout`, `max-retries`, 단가 3종). `toString()`은 키를 가린다 |
| [LlmConfig](../../src/main/java/org/stockinsight/analysis/llm/LlmConfig.java) | `AnthropicOkHttpClient.builder()`로 `AnthropicClient` 만들고 `AnthropicLlmClient` 빈 등록. 인증키가 없어도 빌드는 되고("missing" 자리표시자), 실제 실패는 `AnthropicLlmClient.generate()`의 첫 줄에서 난다 |
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
