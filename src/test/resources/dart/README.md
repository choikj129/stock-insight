# OpenDART 응답 샘플

| 파일 | 출처 |
|---|---|
| `corpcode-error-010.xml` | **실제 응답** (2026-09-24, 등록되지 않은 인증키로 `corpCode.xml` 호출). 오류 시 zip이 아니라 HTTP 200 + XML이 온다 |
| `company-error-010.json` | **실제 응답** (2026-09-24, 등록되지 않은 인증키로 `company.json` 호출) |
| `corpcode-sample.xml` | 공식 가이드의 요소 구조(`result/list/corp_code, corp_name, stock_code, modify_date`)로 만든 샘플. 비상장사는 `stock_code`가 공백 한 칸인 원본 특성을 반영했다 |
| `company-00126380.json` | 공식 가이드의 필드 구조로 만든 기업개황 성공 응답 샘플 |
| `company-error-013.json`, `company-error-020.json` | 공식 가이드의 상태 코드로 만든 오류 응답 샘플 |

인증키를 발급받으면 성공 응답 샘플을 실제 응답으로 교체한다. 고유번호 파일은 수만 건이므로 일부 기업만 남긴다.

```bash
curl -o corpCode.zip "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=$DART_API_KEY"
curl -o company-00126380.json "https://opendart.fss.or.kr/api/company.json?crtfc_key=$DART_API_KEY&corp_code=00126380"
```
