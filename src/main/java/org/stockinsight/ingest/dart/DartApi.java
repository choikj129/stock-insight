package org.stockinsight.ingest.dart;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 수집기가 쓰는 OpenDART 기능. 인증키가 없는 환경의 테스트는 이 인터페이스를 가짜로 바꿔 쓴다.
 * 모든 메서드는 실패 시 {@link DartApiException}을 던진다.
 */
public interface DartApi {

    /** 고유번호 파일(corpCode.xml)의 전체 기업 목록. */
    List<DartCorpCode> fetchCorpCodes();

    /** 기업개황(company.json). 데이터가 없으면(013) 빈 값. */
    Optional<DartCompanyOverview> fetchCompany(String corpCode);

    /**
     * 공시검색(list.json). 기업을 지정하지 않고 하루치 한 공시 유형을 한 페이지씩 받는다.
     * 정정 전 원 공시도 함께 받는다 (최종보고서만 검색하지 않음). 데이터가 없으면(013) 빈 페이지.
     *
     * @param disclosureType OpenDART 공시유형 코드 (A: 정기공시, B: 주요사항보고, I: 거래소공시 등)
     */
    DartDisclosurePage fetchDisclosures(LocalDate receivedOn, String disclosureType, int pageNo);

    /**
     * 다중회사 주요계정(fnlttMultiAcnt.json). corpCodes는 최대 100개. 데이터가 없으면(013) 빈 목록.
     *
     * @param reportCode 11013(1분기), 11012(반기), 11014(3분기), 11011(사업보고서)
     */
    List<DartKeyAccount> fetchKeyAccounts(List<String> corpCodes, int bsnsYear, String reportCode);
}
