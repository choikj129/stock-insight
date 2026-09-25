-- AI 분석 결과 (docs/implementation-plan.md §7, D-38, D-39, D-40, architecture.md §4.2)

create table analysis (
    id                   bigint generated always as identity primary key,
    target_type          varchar(20)  not null,
    target_key           varchar(50)  not null,
    analysis_kind        varchar(30)  not null,
    fingerprint          varchar(64)  not null,
    status               varchar(10)  not null,
    result_json          jsonb,
    input_json           jsonb        not null,
    value_snapshot       jsonb,
    schema_version        varchar(20)  not null,
    prompt_version       varchar(20)  not null,
    model                varchar(50),
    input_builder_version varchar(20) not null,
    rule_version         varchar(20)  not null,
    input_tokens         integer,
    output_tokens        integer,
    cache_read_tokens    integer,
    cost_usd             numeric(10, 4),
    failure_reasons      jsonb,
    attempt_count        integer      not null default 0,
    created_at           timestamptz  not null,
    published_at         timestamptz,
    is_current           boolean      not null default false
);

comment on column analysis.target_type is 'COMPANY (경쟁사 쌍 등은 나중에 추가)';
comment on column analysis.target_key is '대상 식별자. COMPANY는 company.id 문자열';
comment on column analysis.analysis_kind is 'financial_explain (다른 분석 종류는 나중에 추가)';
comment on column analysis.fingerprint is '입력에 쓴 원천 식별자 집합의 해시(D-40). 값 해시가 아니다(D-08)';
comment on column analysis.status is 'DRAFT, PUBLISHED, REJECTED, FAILED, HIDDEN (ai-analysis.md §6.3)';
comment on column analysis.result_json is 'AI 출력 그대로(토큰 포함). 실패·거절이면 null일 수 있다';
comment on column analysis.input_json is 'AI에 준 입력 전체(공개 재무 수치만, 재현·감사용)';
comment on column analysis.value_snapshot is '자리표시자 → 값(표시 값·원값·단위·기간·출처)의 렌더링용 스냅샷';
comment on column analysis.schema_version is '출력 JSON 스키마 버전';
comment on column analysis.model is 'AI 응답을 받은 모델. 응답을 받지 못한 실패(FAILED)면 null일 수 있다';
comment on column analysis.input_builder_version is '입력 구성 코드 버전(초기 fx-input-1)';
comment on column analysis.rule_version is '입력에 쓴 신호의 규칙 버전(여러 개면 대표값, 지문 계산은 신호별로 한다)';
comment on column analysis.failure_reasons is '검증 실패 규칙 번호 목록 등';
comment on column analysis.is_current is '대상·분석 종류당 게시본은 최대 하나(부분 유일 인덱스)';

create unique index analysis_current_idx on analysis (target_type, target_key, analysis_kind) where is_current;
create index analysis_lookup_idx on analysis (target_type, target_key, analysis_kind, fingerprint);
