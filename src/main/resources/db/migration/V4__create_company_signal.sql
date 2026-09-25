-- 기업 신호 (docs/implementation-plan.md §6, D-35, D-36, D-37)

create table company_signal (
    id                 bigint generated always as identity primary key,
    company_id         bigint       not null references company (id),
    signal_type        varchar(50)  not null,
    basis_key          varchar(50)  not null,
    nature             varchar(10)  not null,
    direction          varchar(10)  not null,
    severity           varchar(10)  not null,
    occurred_on        date         not null,
    persistence        integer,
    calc_values        jsonb        not null,
    watch_metrics      jsonb        not null,
    source_receipt_no  varchar(14),
    status             varchar(10)  not null,
    rule_version       varchar(20)  not null,
    first_detected_at  timestamptz  not null,
    last_evaluated_at  timestamptz  not null,
    status_changed_at  timestamptz  not null,
    unique (company_id, signal_type, basis_key)
);

comment on column company_signal.basis_key is '신호를 만든 근거의 식별자(재무 기간 등). 자연키 = company_id + signal_type + basis_key';
comment on column company_signal.nature is 'STATE(상태) 또는 CHANGE(변화)';
comment on column company_signal.direction is 'POSITIVE, NEGATIVE, UNCERTAIN. 실적·재무 건전성 영향 기준';
comment on column company_signal.severity is 'LOW, MEDIUM, HIGH';
comment on column company_signal.occurred_on is '발생 기준일. 재무 신호는 해당 기간 종료일';
comment on column company_signal.persistence is '같은 방향의 변화가 연속으로 나타난 기간 수. 상태 신호가 아니면 null 가능';
comment on column company_signal.calc_values is '판정에 쓴 계산값(자리표시자 키·값, 출처 공시번호 포함)';
comment on column company_signal.watch_metrics is '이 신호가 있을 때 앞으로 지켜볼 지표 키 목록';
comment on column company_signal.source_receipt_no is '근거 보고서의 공시번호(최신 정정본). 데이터 한계 신호는 null일 수 있다';
comment on column company_signal.status is 'ACTIVE(활성), PAST(이력), WITHDRAWN(철회). 행은 지우지 않는다';
comment on column company_signal.rule_version is '판정에 쓴 규칙 카탈로그 버전';

create index company_signal_status_idx on company_signal (company_id, status);
create index company_signal_occurred_idx on company_signal (company_id, occurred_on desc);
