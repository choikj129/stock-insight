-- 재무 데이터: OpenDART 다중회사 주요계정(fnlttMultiAcnt) (docs/implementation-plan.md §4.2, D-32, D-33)

create table financial_report (
    id                 bigint generated always as identity primary key,
    company_id         bigint      not null references company (id),
    bsns_year          integer     not null,
    report_code        varchar(5)  not null,
    fs_div             varchar(3)  not null,
    period_type        varchar(2)  not null,
    fiscal_year_start  date        not null,
    period_end         date        not null,
    currency           varchar(3)  not null,
    receipt_no         varchar(14) not null,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    unique (company_id, bsns_year, report_code, fs_div)
);

comment on column financial_report.bsns_year is 'OpenDART 조회 키. 보고서 기간 종료 연도';
comment on column financial_report.report_code is '11013: 1분기, 11012: 반기, 11014: 3분기, 11011: 사업보고서';
comment on column financial_report.fs_div is 'CFS: 연결, OFS: 별도';
comment on column financial_report.period_type is 'Q1, H1, Q3, FY. report_code에서 정한다';
comment on column financial_report.fiscal_year_start is '손익 thstrm_dt 범위의 시작일. 회계연도 식별자(D-33)';
comment on column financial_report.period_end is '손익 thstrm_dt 범위의 종료일';
comment on column financial_report.receipt_no is '응답 rcept_no(최신 정정본). 근거 공시번호';

create index financial_report_period_idx on financial_report (company_id, fiscal_year_start, period_type);

create table financial_line (
    id                          bigint generated always as identity primary key,
    report_id                   bigint      not null references financial_report (id) on delete cascade,
    ord                         integer     not null,
    statement                   varchar(2)  not null,
    account_name                varchar(100) not null,
    current_amount              numeric(24, 0),
    current_cumulative_amount   numeric(24, 0),
    prior_amount                numeric(24, 0),
    prior_cumulative_amount     numeric(24, 0),
    prior2_amount               numeric(24, 0),
    unique (report_id, ord)
);

comment on column financial_line.statement is 'sj_div. BS: 재무상태표, IS: 손익';
comment on column financial_line.account_name is 'account_nm 원문';
comment on column financial_line.current_amount is 'thstrm_amount';
comment on column financial_line.current_cumulative_amount is 'thstrm_add_amount. 사업보고서·재무상태표는 null';
comment on column financial_line.prior_amount is 'frmtrm_amount';
comment on column financial_line.prior_cumulative_amount is 'frmtrm_add_amount';
comment on column financial_line.prior2_amount is 'bfefrmtrm_amount. 사업보고서만';
