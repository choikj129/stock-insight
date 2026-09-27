-- D-53: 시도별 AI 출력·검증 결과를 남긴다(원인 분석·검증기 변경 뒤 재판정용). 게시·렌더링은 이 열을 읽지 않는다.
alter table analysis add column attempts jsonb;

comment on column analysis.attempts is '시도별 기록 배열: attempt, outcome, failedRules, output(파싱한 출력), rawOutput(JSON 파싱 실패 시 원문), detail, validatorVersion. D-53 이전 행은 null';
comment on column analysis.failure_reasons is '마지막 시도의 실패 규칙 목록(시도별 전체는 attempts)';
