package org.stockinsight.analysis;

import java.util.Map;

/**
 * 정적 콘텐츠: 신호 유형별 배지 이름, 데이터 한계 문구, 면책·AI 생성 표시(ai-analysis.md §7.2, §10).
 * 코드 상수로 관리한다. AI는 이 문구를 만들지 않는다.
 */
final class StaticContent {

    private StaticContent() {
    }

    static final String AI_GENERATED_NOTICE = "이 설명은 AI가 공시된 재무 수치로 자동 작성했어요. 투자 판단의 근거로 쓰지 마세요.";
    static final String INVALIDATED_NOTICE = "재무 자료가 바뀌어 설명을 다시 만드는 중이에요.";

    private static final Map<String, String> BADGE_POSITIVE = Map.of(
            "FIN_REVENUE_CHANGE", "매출 큰 폭 증가",
            "FIN_OPERATING_MARGIN_CHANGE", "영업이익률 큰 폭 개선",
            "FIN_OPERATING_TURN", "흑자 전환");
    private static final Map<String, String> BADGE_NEGATIVE = Map.of(
            "FIN_REVENUE_CHANGE", "매출 큰 폭 감소",
            "FIN_OPERATING_MARGIN_CHANGE", "영업이익률 큰 폭 악화",
            "FIN_OPERATING_TURN", "적자 전환",
            "FIN_DEBT_RATIO_JUMP", "부채비율 급등",
            "FIN_OPERATING_LOSS_STREAK", "영업적자 지속",
            "FIN_CAPITAL_IMPAIRMENT", "자본잠식");

    /** 신호 유형·방향의 정적 배지 이름(ai-analysis.md §4.4.4). */
    static String badgeName(String signalType, String direction) {
        Map<String, String> table = "POSITIVE".equals(direction) ? BADGE_POSITIVE : BADGE_NEGATIVE;
        return table.getOrDefault(signalType, table.getOrDefault(signalType, signalType));
    }

    private static final Map<String, String> LIMIT_CODE_MESSAGE = Map.of(
            "FIN_DATA_HISTORY_SHORT", "이 기업은 재무 이력이 짧아 추이를 판단하기 어려워요.",
            "FIN_DATA_FINANCIAL_FORMAT", "은행·보험·증권 같은 금융회사는 부채비율·영업이익률로 재무 구조를 보지 않아요.",
            "FIN_DATA_REVENUE_MISSING", "이 기업은 최근 보고서에 매출액 계정이 없어요.",
            "FIN_DATA_BASIS_CHANGED", "최근 연결·별도 재무제표 기준이 바뀌어 추이 비교에 참고가 필요해요.",
            "FIN_DATA_IRREGULAR_PERIOD", "결산기가 바뀌어 12개월이 아닌 기간이 있어요.",
            "FIN_DATA_INCONSISTENT", "이 기간 재무상태표 합계가 서로 맞지 않아 참고용으로만 보여줘요.",
            "FIN_DATA_STALE", "최신 보고서 제출 기한이 지났는데 아직 반영되지 않았어요(수집 기준 미확인).");

    static String limitCodeMessage(String code) {
        return LIMIT_CODE_MESSAGE.getOrDefault(code, code);
    }
}
