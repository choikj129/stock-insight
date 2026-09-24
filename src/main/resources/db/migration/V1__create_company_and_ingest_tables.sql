-- 기업·종목 마스터와 수집 상태 테이블 (docs/architecture.md §4.2)

create table company (
    id               bigint generated always as identity primary key,
    dart_corp_code   varchar(8) unique,
    name             varchar(200) not null,
    legal_name       varchar(200),
    name_initials    varchar(200),
    industry_code    varchar(10),
    fiscal_month     integer,
    status           varchar(20)  not null,
    exclusion_reason varchar(30),
    ai_covered       boolean      not null default false,
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);

comment on column company.status is 'ACTIVE: 대상 종목 범위, EXCLUDED: 상장사지만 범위 밖, DELISTED: 상장폐지';

create table security (
    id          bigint generated always as identity primary key,
    company_id  bigint      not null references company (id),
    ticker      varchar(12) not null,
    isin        varchar(12),
    market      varchar(10) not null,
    share_type  varchar(20) not null,
    currency    varchar(3)  not null,
    listed_on   date,
    delisted_on date,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    unique (company_id, share_type)
);

create index security_ticker_idx on security (ticker);

create table company_alias (
    id         bigint generated always as identity primary key,
    company_id bigint       not null references company (id),
    alias      varchar(200) not null,
    kind       varchar(20)  not null,
    created_at timestamptz  not null,
    unique (company_id, alias)
);

create table ingest_checkpoint (
    source          varchar(50)  not null,
    target_key      varchar(100) not null,
    source_version  varchar(50),
    result          varchar(20)  not null,
    message         varchar(500),
    attempt_count   integer      not null default 0,
    last_attempt_at timestamptz  not null,
    last_success_at timestamptz,
    primary key (source, target_key)
);

comment on column ingest_checkpoint.result is 'SUCCESS, NO_DATA, ERROR';

create table pipeline_run (
    id              bigint generated always as identity primary key,
    job_name        varchar(100) not null,
    status          varchar(20)  not null,
    started_at      timestamptz  not null,
    finished_at     timestamptz,
    processed_count integer      not null default 0,
    failed_count    integer      not null default 0,
    message         varchar(1000)
);

create index pipeline_run_job_idx on pipeline_run (job_name, started_at desc);
