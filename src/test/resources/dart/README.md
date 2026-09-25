# OpenDART 응답 샘플

| 파일 | 출처 |
|---|---|
| `corpcode-error-010.xml` | **실제 응답** (2026-09-24, 등록되지 않은 인증키로 `corpCode.xml` 호출). 오류 시 zip이 아니라 HTTP 200 + XML이 온다 |
| `company-error-010.json` | **실제 응답** (2026-09-24, 등록되지 않은 인증키로 `company.json` 호출) |
| `company-00126380.json` | **실제 응답** (2026-09-25, 삼성전자 기업개황). 이전 샘플과 필드 구성이 같았다 |
| `list-20260814-B.json` | **실제 응답** (2026-09-25 조회, 접수일 2026-08-14 주요사항보고 1페이지). `list`는 38건 중 5건만 남겼다(상장사 원 공시, `[기재정정]`, `[첨부정정]`, 비상장 기타법인 `E`, 비고 `공`). 페이지 정보는 원본 그대로다 |
| `list-20260814-I-page1.json` | **실제 응답** (2026-09-25 조회, 접수일 2026-08-14 거래소공시 1/3페이지). `list`는 100건 중 3건만 남겼다(끝 공백이 있는 보고서명, 비고 `코정`) |
| `list-error-013.json` | **실제 응답** (2026-09-25, 토요일 2026-09-19 조회). 공시가 없는 날과 마지막 페이지를 넘긴 요청 모두 이 응답이다 |
| `list-error-100.json` | **실제 응답** (2026-09-25, 기업 미지정 4개월 조회). 기업을 지정하지 않으면 기간이 3개월로 제한된다 |
| `corpcode-sample.xml` | 공식 가이드의 요소 구조(`result/list/corp_code, corp_name, stock_code, modify_date`)로 만든 샘플. 비상장사는 `stock_code`가 공백 한 칸인 원본 특성을 반영했다 |
| `company-error-013.json`, `company-error-020.json` | 공식 가이드의 상태 코드로 만든 오류 응답 샘플 |
| `fnlttMultiAcnt-2025-11012.json` | **실제 응답** (2026-09-25 조회, 2025년 반기보고서). 삼성전자(연결·별도), 모아텍(3월 결산), 양지사(별도만, 6월 결산, 금액 `"-"`, 당기순이익 중복 ord 30·62), KB금융(금융형 계정: 예수부채·이자수익·영업이익(손실), 당기순이익 중복 ord 29·61)만 남겼다 |
| `fnlttMultiAcnt-2025-11011.json` | **실제 응답** (2026-09-25 조회, 2025년 사업보고서). 삼성전자(연결, `bfefrmtrm` 필드, 당기순이익 중복), 한일철강(연결, `rcept_no`가 실제 `[기재정정]` 공시번호 20260806000290)만 남겼다 |
| `fnlttMultiAcnt-error-013.json` | **실제 응답** (2026-09-25, 2030년 사업보고서 조회). 아직 존재하지 않는 기간을 조회하면 013이다 |

인증키는 샘플에 들어가지 않는다(응답 본문에 인증키가 없다). 새 샘플을 받을 때도 요청 URL이나 인증키를 파일·커밋 메시지에 남기지 않는다. 고유번호 파일은 수만 건이므로 일부 기업만 남긴다.

```bash
curl -o corpCode.zip "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=$DART_API_KEY"
curl -o company-00126380.json "https://opendart.fss.or.kr/api/company.json?crtfc_key=$DART_API_KEY&corp_code=00126380"
curl -o list.json "https://opendart.fss.or.kr/api/list.json?crtfc_key=$DART_API_KEY&bgn_de=20260814&end_de=20260814&pblntf_ty=B&last_reprt_at=N&page_count=100"
curl -o fnlttMultiAcnt.json "https://opendart.fss.or.kr/api/fnlttMultiAcnt.json?crtfc_key=$DART_API_KEY&corp_code=00126380,00241209,00139685,00688996&bsns_year=2025&reprt_code=11012"
```
