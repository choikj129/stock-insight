package org.stockinsight.analysis;

/** 분석 결과 상태 (ai-analysis.md §6.3). */
public enum AnalysisStatus {
    /** 관리자 미리보기용, 게시되지 않는다. */
    DRAFT,
    /** 화면에 보이는 현재 버전. 대상·분석 종류당 1개. */
    PUBLISHED,
    /** 검증 실패. 이전 게시본을 유지한다. */
    REJECTED,
    /** 호출 오류. */
    FAILED,
    /** 관리자가 내린 게시본. */
    HIDDEN
}
