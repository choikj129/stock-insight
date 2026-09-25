-- 공시 목록 (docs/architecture.md §4.2, OpenDART 공시검색)

create table disclosure (
    id               bigint generated always as identity primary key,
    receipt_no       varchar(14)  not null unique,
    company_id       bigint       not null references company (id),
    disclosure_type  varchar(20)  not null,
    report_name      varchar(300) not null,
    base_report_name varchar(300) not null,
    amendment_label  varchar(20),
    received_on      date         not null,
    filer_name       varchar(200),
    remark           varchar(20)  not null default '',
    original_id      bigint references disclosure (id),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);

comment on column disclosure.receipt_no is '공시번호(접수번호 14자리). 공시의 자연키이자 근거 키';
comment on column disclosure.disclosure_type is 'PERIODIC: 정기공시(A), MAJOR_EVENT: 주요사항보고(B), EXCHANGE: 거래소공시(I)';
comment on column disclosure.base_report_name is '정정 표시([기재정정] 등)와 기타정보를 뗀 보고서명. 원 공시 연결 기준';
comment on column disclosure.amendment_label is '정정 표시(기재정정, 첨부정정, 첨부추가 등). 최초 제출이면 null';
comment on column disclosure.received_on is '접수일. 공시의 기준일';
comment on column disclosure.remark is 'OpenDART 비고(rm) 원문. 마지막으로 읽은 시점의 값';
comment on column disclosure.original_id is '정정 공시의 원 공시. 코드가 기업 + base_report_name으로 연결한다';

create index disclosure_company_received_idx on disclosure (company_id, received_on desc);
create index disclosure_amendment_idx on disclosure (company_id, base_report_name, receipt_no);
