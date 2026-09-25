package org.stockinsight.analysis;

import java.util.List;

/**
 * 재무 쉬운 설명의 AI 입력 전체(ai-analysis.md §4.4.2, D-38). 코드가 만든 사실표와 신호 참조뿐이고,
 * 원천 행·분기 시계열·공시번호·내부 ID는 없다. 그대로 직렬화해 AI에 주고 {@code analysis.input_json}에도 저장한다.
 */
public record FinancialExplainInput(
        Company company,
        Latest latest,
        String changeStatus,
        List<String> sections,
        List<PeriodLabel> periods,
        List<Fact> facts,
        List<SignalRef> signals,
        List<Group> groups,
        List<Unavailable> unavailable,
        List<String> doNotMention) {

    public record Company(String name, String format, String currency, String basis, Integer fiscalYearEndMonth) {
    }

    public record Latest(String period, String kind) {
    }

    public record PeriodLabel(String key, String label) {
    }

    public record Fact(String key, String name, String display, String sign, String period) {
    }

    public record SignalRef(String ref, String type, String status, String nature, String direction,
            String severity, String period, Integer persistence, List<String> factKeys) {
    }

    public record Group(String period, String topic, List<String> refs, boolean mixedDirection) {
    }

    public record Unavailable(String metric, String reason) {
    }
}
