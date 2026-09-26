package org.stockinsight.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 재무 쉬운 설명 출력을 검증한다(ai-analysis.md §4.4.7, §7). 실패하면 규칙 번호 목록을 돌려준다.
 * DB·외부 호출 없는 순수 계산이라 단위 테스트 대상이다.
 */
public final class FinancialExplainValidator {

    private static final Pattern TOKEN = Pattern.compile("\\{((?:fin|per|sig)\\.[^{}]+)\\}");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[요다.])(?=\\s|$)");
    private static final Pattern ANY_DIGIT = Pattern.compile("[0-9]");

    private static final Set<String> QUANTITY_WORDS = Set.of("두 배", "세 배", "몇 배", "절반", "네 배");

    private static final Set<String> INTENSITY_WORDS = Set.of("크게", "급격히", "뚜렷하게", "전환", "지속", "급등", "잠식");

    private static final Set<String> POSITIVE_WORDS = Set.of("늘", "증가", "높아지", "개선", "확대", "좋아지");
    private static final Set<String> NEGATIVE_WORDS = Set.of("줄", "감소", "낮아지", "악화", "축소", "나빠지");
    private static final Set<String> SURPLUS_WORDS = Set.of("흑자");
    private static final Set<String> DEFICIT_WORDS = Set.of("적자");

    /** ai-analysis.md §7.2 + §4.4.7 규칙 9의 추가 목록. */
    private static final Map<String, Set<String>> FORBIDDEN = Map.ofEntries(
            Map.entry("투자 행위 권유", Set.of("매수", "매도", "추천", "비중 확대", "담아")),
            Map.entry("가격 예측", Set.of("목표가", "적정 주가", "상승 여력")),
            Map.entry("가치 판정", Set.of("저평가", "고평가", "유망")),
            Map.entry("미래 단정", Set.of("할 것이다", "할 것으로 예상", "확실히", "반드시")),
            Map.entry("종합 판정", Set.of("전반적으로", "좋은 기업", "나쁜 기업")),
            Map.entry("원인 접속어", Set.of("때문에", "덕분에", "탓에", "로 인해", "영향으로")),
            Map.entry("입력에 없는 비교", Set.of("전분기", "직전 분기", "지난 분기보다", "경쟁사", "업계 대비", "업계보다")),
            Map.entry("입력에 없는 주제", Set.of("현금흐름", "주가", "시가총액", "배당", "PER", "PBR", "ROE")),
            Map.entry("회사 전체 평가어", Set.of("안정적", "건전", "우량", "부실", "양호", "탄탄", "위험한 기업")));

    /** 섹션별 분량 한도(§4.4.3): 문장 수, 사실 토큰 수. */
    private static final Map<String, int[]> SECTION_LIMITS = Map.of(
            "overview", new int[] {2, 3},
            "sales_profit", new int[] {4, 6},
            "structure", new int[] {3, 4},
            "history", new int[] {3, Integer.MAX_VALUE});

    public ValidationResult validate(FinancialExplainOutput output, FinancialExplainInput input) {
        Set<String> failedRules = new LinkedHashSet<>();

        Map<String, String> sectionText = sectionsOf(output);
        Set<String> allowedSections = Set.copyOf(input.sections());

        // 규칙 1: sections에 있는 섹션만 채움, 없는 섹션은 null
        for (Map.Entry<String, String> e : sectionText.entrySet()) {
            boolean present = e.getValue() != null && !e.getValue().isBlank();
            boolean allowed = allowedSections.contains(e.getKey());
            if (present && !allowed) {
                failedRules.add("1");
            }
            if (!present && allowed) {
                failedRules.add("1");
            }
        }

        Map<String, org.stockinsight.analysis.FinancialExplainInput.Fact> factByKey = new LinkedHashMap<>();
        input.facts().forEach(f -> factByKey.put(f.key(), f));
        Map<String, org.stockinsight.analysis.FinancialExplainInput.SignalRef> signalByRef = new LinkedHashMap<>();
        input.signals().forEach(s -> signalByRef.put(s.ref(), s));
        Set<String> periodKeys = input.periods().stream().map(org.stockinsight.analysis.FinancialExplainInput.PeriodLabel::key)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, Integer> factTokenCount = new LinkedHashMap<>();
        Map<String, Integer> signalRefCount = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : sectionText.entrySet()) {
            String section = e.getKey();
            String text = e.getValue();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<String> sentences = splitSentences(text);

            for (String sentence : sentences) {
                List<TokenRef> tokens = tokensIn(sentence);
                for (TokenRef t : tokens) {
                    if (t.kind().equals("fin")) {
                        if (!factByKey.containsKey(t.raw())) {
                            failedRules.add("2");
                        } else {
                            factTokenCount.merge(t.raw(), 1, Integer::sum);
                        }
                    } else if (t.kind().equals("per")) {
                        if (!periodKeys.contains(t.value())) {
                            failedRules.add("2");
                        }
                    } else if (t.kind().equals("sig")) {
                        if (!signalByRef.containsKey(t.value())) {
                            failedRules.add("2");
                        } else {
                            signalRefCount.merge(t.value(), 1, Integer::sum);
                        }
                    }
                }
                checkNumbersAndQuantity(sentence, failedRules);
                checkIntensityWords(sentence, tokens, input.changeStatus(), failedRules);
                checkDirection(sentence, tokens, factByKey, signalByRef, failedRules);
                checkHistoryTense(sentence, tokens, section, signalByRef, failedRules);
                checkForbidden(sentence, failedRules);
            }
            checkDoNotMentionAndUnavailable(text, input, failedRules);
            checkLength(section, text, sentences, tokensIn(text), failedRules);
        }

        factTokenCount.values().forEach(count -> {
            if (count > 1) {
                failedRules.add("4");
            }
        });
        signalRefCount.values().forEach(count -> {
            if (count > 2) {
                failedRules.add("4");
            }
        });

        return new ValidationResult(failedRules.isEmpty(), List.copyOf(failedRules));
    }

    private static Map<String, String> sectionsOf(FinancialExplainOutput output) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("overview", output.overview());
        m.put("sales_profit", output.salesProfit());
        m.put("structure", output.structure());
        m.put("history", output.history());
        return m;
    }

    private static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        for (String s : SENTENCE_SPLIT.split(text.strip())) {
            if (!s.isBlank()) {
                result.add(s.strip());
            }
        }
        return result;
    }

    private record TokenRef(String raw, String kind, String value) {
    }

    private static List<TokenRef> tokensIn(String text) {
        List<TokenRef> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String inner = m.group(1);
            int dot = inner.indexOf('.');
            String kind = inner.substring(0, dot);
            String value = inner.substring(dot + 1);
            tokens.add(new TokenRef(inner, kind, value));
        }
        return tokens;
    }

    /** 규칙 3: 토큰 밖 숫자 없음, 수량 표현 없음. */
    private static void checkNumbersAndQuantity(String sentence, Set<String> failedRules) {
        String withoutTokens = TOKEN.matcher(sentence).replaceAll("");
        if (ANY_DIGIT.matcher(withoutTokens).find()) {
            failedRules.add("3");
        }
        for (String q : QUANTITY_WORDS) {
            if (sentence.contains(q)) {
                failedRules.add("3");
            }
        }
    }

    /** 규칙 5: 강도 표현·판정어는 신호 참조가 있는 문장에서만, NONE이면 어디에도 없음. */
    private static void checkIntensityWords(String sentence, List<TokenRef> tokens, String changeStatus, Set<String> failedRules) {
        boolean hasIntensity = INTENSITY_WORDS.stream().anyMatch(sentence::contains);
        if (!hasIntensity) {
            return;
        }
        boolean hasSignal = tokens.stream().anyMatch(t -> t.kind().equals("sig"));
        if ("NONE".equals(changeStatus) || !hasSignal) {
            failedRules.add("5");
        }
    }

    /** 규칙 6: 증감·흑자적자 어휘가 부호·신호 방향과 같음. */
    private static void checkDirection(String sentence, List<TokenRef> tokens,
            Map<String, org.stockinsight.analysis.FinancialExplainInput.Fact> factByKey,
            Map<String, org.stockinsight.analysis.FinancialExplainInput.SignalRef> signalByRef, Set<String> failedRules) {
        String directionalSign = null;
        for (TokenRef t : tokens) {
            // 사실 키는 "fin.<지표>.<기간 키>" 형식이고 기간 키 자체에도 점이 있어(예: 2026-01.Q2) 뒤에서부터 자르면 안 된다.
            // "_yoy."·"_diff."가 원문에 있는지로 변화량 지표인지 판단한다.
            if (t.kind().equals("fin") && (t.raw().contains("_yoy.") || t.raw().contains("_diff."))) {
                var fact = factByKey.get(t.raw());
                if (fact != null) {
                    directionalSign = fact.sign();
                }
            }
        }
        boolean hasPositiveWord = POSITIVE_WORDS.stream().anyMatch(sentence::contains);
        boolean hasNegativeWord = NEGATIVE_WORDS.stream().anyMatch(sentence::contains);
        if (directionalSign != null) {
            if ("+".equals(directionalSign) && hasNegativeWord) {
                failedRules.add("6");
            }
            if ("-".equals(directionalSign) && hasPositiveWord) {
                failedRules.add("6");
            }
        }
        for (TokenRef t : tokens) {
            if (!t.kind().equals("sig")) {
                continue;
            }
            var signal = signalByRef.get(t.value());
            if (signal == null || !"FIN_OPERATING_TURN".equals(signal.type())) {
                continue;
            }
            boolean hasSurplus = SURPLUS_WORDS.stream().anyMatch(sentence::contains);
            boolean hasDeficit = DEFICIT_WORDS.stream().anyMatch(sentence::contains);
            if ("POSITIVE".equals(signal.direction()) && hasDeficit) {
                failedRules.add("6");
            }
            if ("NEGATIVE".equals(signal.direction()) && hasSurplus) {
                failedRules.add("6");
            }
        }
    }

    /** 흐름 사실(최신 기간까지 이어지는 상태라 history 대상이 아니다, D-45). */
    private static final Set<String> ONGOING_FLOW_METRICS = Set.of("revenue_yoy_run", "operating_loss_run");

    /**
     * 규칙 7: 이력(PAST) 신호는 history에서만, 활성 신호는 history 밖에서만. 흐름 사실(fin.revenue_yoy_run·
     * fin.operating_loss_run)은 지금도 이어지는 상태라 history에 쓰지 않는다(D-45).
     */
    private static void checkHistoryTense(String sentence, List<TokenRef> tokens, String section,
            Map<String, org.stockinsight.analysis.FinancialExplainInput.SignalRef> signalByRef, Set<String> failedRules) {
        for (TokenRef t : tokens) {
            if (t.kind().equals("fin") && "history".equals(section)
                    && ONGOING_FLOW_METRICS.stream().anyMatch(m -> t.raw().startsWith("fin." + m + "."))) {
                failedRules.add("7");
                continue;
            }
            if (!t.kind().equals("sig")) {
                continue;
            }
            var signal = signalByRef.get(t.value());
            if (signal == null) {
                continue;
            }
            boolean isPast = "PAST".equals(signal.status());
            if (isPast && !"history".equals(section)) {
                failedRules.add("7");
            }
            if (!isPast && "history".equals(section)) {
                failedRules.add("7");
            }
        }
    }

    /** 규칙 8: doNotMention·unavailable 지표 이름 없음. */
    private static void checkDoNotMentionAndUnavailable(String text, FinancialExplainInput input, Set<String> failedRules) {
        for (String term : input.doNotMention()) {
            if (text.contains(term)) {
                failedRules.add("8");
            }
        }
        for (var u : input.unavailable()) {
            // "revenue"(매출 원값 자체가 없음)와 "revenue_yoy"(증가율만 못 줌, 원값은 있음)는 다른 사유다.
            // revenue_yoy만 unavailable이어도 원값(revenue) 사실은 사실표에 있고 sales_profit이 반드시
            // "매출(규모)"을 언급해야 하므로(§4.4.3), revenue_yoy를 "매출"에 묶어 막으면 안 된다.
            String name = switch (u.metric()) {
                case "revenue" -> "매출";
                case "operating_margin" -> "영업이익률";
                case "debt_ratio" -> "부채비율";
                case "impairment_ratio" -> "잠식률";
                default -> null;
            };
            if (name != null && text.contains(name)) {
                failedRules.add("8");
            }
        }
    }

    /** 규칙 9: 금지 표현. */
    private static void checkForbidden(String sentence, Set<String> failedRules) {
        for (Set<String> words : FORBIDDEN.values()) {
            for (String w : words) {
                if (sentence.contains(w)) {
                    failedRules.add("9");
                }
            }
        }
    }

    /** 규칙 10: 섹션 분량(문장 수, 사실 토큰 수). */
    private static void checkLength(String section, String text, List<String> sentences, List<TokenRef> tokens,
            Set<String> failedRules) {
        int[] limit = SECTION_LIMITS.get(section);
        if (limit == null) {
            return;
        }
        long factTokenCount = tokens.stream().filter(t -> t.kind().equals("fin")).count();
        if (sentences.size() > limit[0] || factTokenCount > limit[1]) {
            failedRules.add("10");
        }
    }

    public record ValidationResult(boolean valid, List<String> failedRules) {
    }
}
