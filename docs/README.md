# 문서 지도

| 항목 | 내용 |
|---|---|
| 상태 | 확정 (문서 체계의 입구) |
| 최종 수정 | 2026-09-27 (문서 재구성: 큰 파일 4개를 주제·종류별 20개 파일로 정리) |

stock-insight의 모든 설계·진행·코드 문서는 이 폴더에 있다. 작업을 시작할 때는 이 파일과 [status.md](status.md)를 읽고, §2 표에서 그 작업에 필요한 파일만 연다.

---

## 1. 파일 목록

문서는 **현재 기준**과 **기록** 두 종류다. 현재 기준은 지금 참인 것만 담고, 바뀌면 그 절을 고쳐 쓴다. 기록은 경위를 담고 끝에 이어 쓴다. 파일 목록은 고정이다. 새 파일은 새 작업 단계나 새 분석 종류가 생길 때만 만든다(§3).

| 종류 | 파일 | 내용 |
|---|---|---|
| 현재 기준 | [status.md](status.md) | 단계별 진행 현황, 판단 대기, 구현 중 점검할 제품 경계, 작업 기록 목록. **작업 시작 때마다 읽는다** |
| 현재 기준 | [product.md](product.md) | 제품의 목적·대상·원칙·기능 판단 기준(§12). "왜" |
| 현재 기준 | [architecture.md](architecture.md) | 시스템 설계: 인프라·기술 선택·데이터 모델·수집 일정·화면 구조·보안·운영·범위 |
| 현재 기준 | [spec/signals-financial.md](spec/signals-financial.md) | 코드가 판정하는 규칙: 기업 신호, 재무 지표·재무 신호, 지표 보기, 향후 이벤트·잠정실적·거래 상태 (§3) |
| 현재 기준 | [spec/financial-explain.md](spec/financial-explain.md) | AI 재무 쉬운 설명의 입력·설명 구조·검증·게시 조건 (§4.4) |
| 현재 기준 | [spec/analyses.md](spec/analyses.md) | AI 분석 공통(역할 분담·동기화·검증·금지 표현·프롬프트·비용)과 나머지 분석 종류(기업 이해·일일 변동·향후 전망·경쟁사 비교, 설계만) |
| 현재 기준 | [code-guide/README.md](code-guide/README.md) | 코드 가이드: 한눈에 보기, 저장소·패키지 구조, 설계 의도, 읽는 순서, 테스트·CI, 미확인·한계, 변경 시 체크리스트 |
| 현재 기준 | [code-guide/classes.md](code-guide/classes.md) | 코드 가이드: 클래스별 책임 |
| 현재 기준 | [code-guide/runtime.md](code-guide/runtime.md) | 코드 가이드: 기동·Bean, 실행 경로·흐름 추적, 설정값, 데이터 흐름, DB, 트랜잭션·예외·재시도·동시성 |
| 기록 | [decisions/README.md](decisions/README.md) | 설계 결정 목록(한 줄씩). 원문은 번호 구간 파일 `D-01-D-40.md`, `D-41-D-53.md`, `D-54-D-59.md`, `D-60-D-99.md` |
| 기록 | [work/3-collection-signals.md](work/3-collection-signals.md) | 3단계 수집·재무 지표·신호 작업 기록 (완료) |
| 기록 | [work/3-4-financial-explain.md](work/3-4-financial-explain.md) | 3-4 AI 재무 쉬운 설명 작업 기록, 후속 D-41~D-43, 검증 회차 목록·경과 요약 |
| 기록 | [work/3-4-verification-1.md](work/3-4-verification-1.md), [-2](work/3-4-verification-2.md) | 3-4 검증 회차 원문 (§7.4~§7.4.21 / §7.4.22~). 평소에는 읽지 않는다 |
| 기록 | [work/4-financial-metrics-expansion.md](work/4-financial-metrics-expansion.md) | 4단계 재무 지표 확장 (설계 확정, 구현 전) |
| 기록 | [review/](review/) | 사용자 판단을 위한 검토 문서(선택지·권고). 주제마다 한 파일 |

---

## 2. 작업별로 읽을 파일

| 하려는 일 | 먼저 읽기 | 필요하면 |
|---|---|---|
| 새 세션에서 이어서 작업 | [status.md](status.md) | 해당 작업 기록 |
| 기능·화면을 넣을지 판단 | [product.md](product.md) §10~§12 | [결정 목록](decisions/README.md) |
| 코드 구조 파악, 분석 시작 | [code-guide/README.md](code-guide/README.md) | [classes.md](code-guide/classes.md), [runtime.md](code-guide/runtime.md) |
| 수집(OpenDART) 변경 | [architecture.md](architecture.md) §4, [code-guide/runtime.md](code-guide/runtime.md) §7.1~§7.3 | [work/3-collection-signals.md](work/3-collection-signals.md) |
| 재무 지표·신호, 공시 신호·이벤트·거래 상태 | [spec/signals-financial.md](spec/signals-financial.md) | [work/3-collection-signals.md](work/3-collection-signals.md) §6, [work/4-financial-metrics-expansion.md](work/4-financial-metrics-expansion.md) |
| 재무 쉬운 설명(AI) 변경 | [spec/financial-explain.md](spec/financial-explain.md), [spec/analyses.md](spec/analyses.md) §6~§9 | [work/3-4-financial-explain.md](work/3-4-financial-explain.md) (회차 목록에서 필요한 회차만) |
| 다른 AI 분석(기업 이해·향후 전망 등) | [spec/analyses.md](spec/analyses.md) | |
| 설정·DB·트랜잭션 확인 | [code-guide/runtime.md](code-guide/runtime.md) §8~§11 | |
| 테스트·CI·gitleaks | [code-guide/README.md](code-guide/README.md) §14 | |

**큰 기록 파일 읽는 법.** 결정·검증 기록은 파일이 크다. 통째로 읽지 말고, 목록([결정 목록](decisions/README.md), [검증 회차 목록](work/3-4-financial-explain.md#검증-회차-목록))에서 번호를 찾은 뒤, 제목(`## D-54.`, `### 7.4.33`)을 검색해 그 부분만 읽는다.

---

## 3. 쓰는 규칙

| 쓰려는 것 | 쓰는 곳 |
|---|---|
| 새 설계 결정 | [decisions/D-60-D-99.md](decisions/D-60-D-99.md) 끝에 `## D-xx. 결정 한 줄` 제목으로 추가하고, [목록](decisions/README.md)에 한 줄 더한다. D-99를 넘으면 `D-100-D-199.md`를 만든다 |
| 결정이 바뀜 | 새 결정을 추가하고, 옛 결정과 목록의 상태를 "대체됨(D-yy)"으로 고친다. 옛 결정 내용은 지우지 않는다 |
| 현재 규칙이 바뀜 | 해당 `spec/`·`architecture.md`·`product.md`의 절을 고쳐 **지금 규칙만** 남긴다. 근거는 D-번호로 적는다. 경위·실험 결과는 명세에 쌓지 않는다 |
| 진행 현황 | [status.md](status.md) 해당 행을 **현재 상태로 덮어쓴다**(한두 문장 + 링크). 경과를 칸에 누적하지 않는다 |
| 진행 중 작업의 설계·결과 | 그 작업 기록 파일의 해당 절. 3-4 검증 회차는 [work/3-4-verification-2.md](work/3-4-verification-2.md) 끝에 `### 7.4.N` 제목으로 이어 쓰고 회차 목록에 한 줄 더한다 |
| 새 작업 단계 시작 | `work/<단계>-<주제>.md` 한 파일을 만들고 [status.md](status.md#작업-문서) 작업 문서 목록과 §1 표에 한 줄씩 더한다. 반복 검증이 길어지면 `work/<단계>-verification-N.md`로 나눈다 |
| 새 분석 종류 | 짧으면 [spec/analyses.md](spec/analyses.md)에 절을 더하고, 재무 쉬운 설명만큼 커지면 `spec/<분석>.md`를 만든다 |
| 코드 구조·흐름 변경 | [code-guide/](code-guide/README.md) 세 파일의 해당 절(§16 체크리스트) |
| 사용자 판단용 검토 | `review/<주제>.md` 한 파일 |

**크기.** 현재 기준 파일이 약 60KB를 넘으면 절 단위로 나눌지 검토한다. 기록 파일은 크기 제한 없이 이어 쓴다. 대신 모든 항목에 번호가 있는 제목을 달고, 목록을 최신으로 유지한다.

**참조.**
- 문서 본문에서는 `docs/` 기준 경로와 절 번호로 쓴다. 예: `spec/signals-financial.md §3.7`.
- 결정은 번호로만 부른다. 예: D-54. 링크는 구간 파일로만 건다(`[D-54](decisions/D-54-D-59.md)`). 결정 제목 anchor는 [결정 목록](decisions/README.md)에만 둔다. 제목이 바뀌어도 링크가 끊기지 않게 하기 위함이다.
- 코드 주석에서는 `docs/`로 시작하는 경로와 절 번호를 쓰고, 결정은 번호만 쓴다. 예: `docs/spec/signals-financial.md §3.7`, `D-54`.
- 절 번호는 옛 파일의 번호를 그대로 쓴다. 한 주제 안에서 번호가 겹치지 않는다(`§3.7`은 항상 `spec/signals-financial.md`에 있다).

---

## 4. 옛 참조와 새 위치

2026-09-27에 큰 파일 4개(`ai-analysis.md`, `implementation-plan.md`, `code-guide.md`, `decisions.md`)를 나눴다. 본문과 절 번호는 그대로 옮겼고, 문서와 코드 주석의 참조는 새 경로로 바꿨다. 옛 이름이 보이면 이 표로 찾는다.

| 옛 참조 | 새 위치 |
|---|---|
| `ai-analysis.md` §3, §3.1~§3.12 | [spec/signals-financial.md](spec/signals-financial.md) |
| `ai-analysis.md` §4.4, §4.4.x | [spec/financial-explain.md](spec/financial-explain.md) |
| `ai-analysis.md` §1·§2, §4(§4.4 제외), §5~§10 | [spec/analyses.md](spec/analyses.md) |
| `implementation-plan.md` §1·§3·§5 | [status.md](status.md) |
| `implementation-plan.md` §2(3-0~3-3), §4, §6 | [work/3-collection-signals.md](work/3-collection-signals.md) |
| `implementation-plan.md` §7(§7.4 제외), §8 | [work/3-4-financial-explain.md](work/3-4-financial-explain.md) |
| `implementation-plan.md` §7.4~§7.4.21 | [work/3-4-verification-1.md](work/3-4-verification-1.md) |
| `implementation-plan.md` §7.4.22~ | [work/3-4-verification-2.md](work/3-4-verification-2.md) |
| `implementation-plan.md` §9 | [work/4-financial-metrics-expansion.md](work/4-financial-metrics-expansion.md) |
| `code-guide.md` §1~§3, §12~§16 | [code-guide/README.md](code-guide/README.md) |
| `code-guide.md` §4 | [code-guide/classes.md](code-guide/classes.md) |
| `code-guide.md` §5~§11 | [code-guide/runtime.md](code-guide/runtime.md) |
| `decisions.md` 목록 | [decisions/README.md](decisions/README.md) |
| `decisions.md` D-xx | [decisions/README.md](decisions/README.md) 목록의 링크 (구간 파일) |

재구성 때 원문에서 바뀐 것은 다음뿐이다.
- 원본 파일의 제목 줄, `code-guide.md`의 옛 목차, 빈 `## 2. 완료한 작업` 제목을 뺐다. 파일마다 내용을 적은 머리말을 붙였다.
- 합치면서 결정 제목은 `## D-xx.`, 검증 회차 제목은 `### 7.4.N`으로 제목 단계를 맞췄다. 제목 글자는 그대로다.
- `code-guide.md`에서 빠져 있던 `## 8. 설정값과 사용 위치` 제목을 되살렸다.
- 옛 `implementation-plan.md` §1 6단계 칸에 쌓인 경과를 [작업 기록의 경과 요약](work/3-4-financial-explain.md#경과-요약)으로 옮기고, 현황에는 현재 상태만 남겼다.
