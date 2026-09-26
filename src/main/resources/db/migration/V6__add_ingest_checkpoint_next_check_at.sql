-- D-43: "최신 재무 미확인" 시간 기반 재판정. 소스 버전이 그대로여도 이 날짜가 지나면 다시 확인 대상이 된다.
alter table ingest_checkpoint add column next_check_at date;
