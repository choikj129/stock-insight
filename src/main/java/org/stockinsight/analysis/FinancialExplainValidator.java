package org.stockinsight.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 재무 쉬운 설명 출력을 검증한다(docs/spec/financial-explain.md §4.4.7, §7). 실패하면 규칙 번호 목록을 돌려준다.
 * DB·외부 호출 없는 순수 계산이라 단위 테스트 대상이다. 판정은 입력(사실 부호·신호 방향·관계 항목)만 보고 한다 —
 * 관계의 방향을 표시 문자열에서 다시 계산하지 않는다(D-55).
 */
public final class FinancialExplainValidator {

    /**
     * 시도 기록(analysis.attempts)에 남기는 판정 방식 버전. fxv-1은 D-53 문장 분리 수정 전, fxv-2는 D-54 전이다.
     * fxv-3(D-54): 규칙 6이 변화량 토큰에 같은 부호의 증감 어휘를 요구하고, 규칙 7이 신호의 {@code section}을 따르며,
     * 규칙 11(섹션 배정)이 추가됐다.
     * fxv-4(D-55): 관계 서술 계약. 규칙 6의 증감 근거에 신호 대응표·매출 연속 흐름·방향 관계(R2)를 더하고 평가어를 신호 방향과
     * 대조하며 전환 신호의 단어 검사를 뺐다. 규칙 12(흑자·적자는 토큰으로만), 규칙 13(코드가 주지 않은 관계), 규칙 7의
     * {@code history} 기간 순서·시간 어휘를 더했다. 규칙 11은 배정 목록이 있는 모든 섹션에 적용된다.
     * fxv-5(D-55 구현 재검증 §7.4.28): 규칙 9에 내부 용어 노출(섹션·입력 필드 이름)을 더하고, 규칙 9를 토큰을 뺀 문장에서 본다.
     * fxv-6(D-56): 규칙 14(배지 위치)를 더하고, 규칙 7에 "흐름 사실 문장에는 기간 토큰을 쓰지 않는다"를 더했다.
     * fxv-7(D-57): 규칙 11에 개요 완전성을 더했다 — {@code overview}는 배정된 사실을 모두 써야 한다(배정 밖 금지는 그대로).
     * {@code sales_profit}·{@code structure}는 완전성을 요구하지 않는다.
     * fxv-8(D-58, fx-v12 사람 검토 반영·docs/work/3-4-verification-2.md §7.4.33): 규칙 14가 앞머리 배지 뒤 같은 절의 첫 사실
     * 토큰이 그 신호의 지표 묶음인지도 본다(배지·지표 결합). 규칙 13이 서로 다른 지표를 "(으)로"로 잇는 것과 정도를
     * 줄이는 어휘(조금·약간·살짝)를 더 막는다. 규칙 6은 부호가 다른 변화량 토큰이 한 문장에 섞이면 절 단위로도 본다.
     * 규칙 5에 정도 어휘(많이·상당히·꽤)를 더한다.
     */
    public static final String VERSION = "fxv-8";

    /** unavailable 지표 → 언급 금지 이름(규칙 8). 입력 구성기도 같은 대응으로 모순되는 사실을 배정하지 않는다. */
    static final Map<String, String> UNAVAILABLE_METRIC_NAMES = Map.of(
            // "revenue"(매출 원값 자체가 없음)와 "revenue_yoy"(증가율만 못 줌, 원값은 있음)는 다른 사유다. revenue_yoy만
            // unavailable이어도 원값 사실은 있고 sales_profit이 "매출(규모)"을 말해야 하므로 revenue_yoy는 "매출"에 묶지 않는다.
            "revenue", "매출",
            "operating_margin", "영업이익률",
            "debt_ratio", "부채비율",
            "impairment_ratio", "잠식률");

    private static final Pattern TOKEN = Pattern.compile("\\{((?:fin|per|sig)\\.[^{}]+)\\}");
    /** 규칙 14: 배지 토큰. */
    private static final Pattern BADGE = Pattern.compile("\\{sig\\.([^{}]+)\\}");
    /** 규칙 14 확장(D-58): 절 경계(쉼표·연결 어미) 또는 다음 배지 앞까지를 "같은 절"로 본다. */
    private static final List<String> CLAUSE_MARKERS = List.of(",", "고 ", "며 ", "면서 ", "지만 ", "는데 ");
    /** 규칙 13 확장(D-58): 사실 토큰 바로 뒤 "(으)로" 다음, 조사가 붙은 말 뒤에 다른 지표의 사실 토큰이 오면 안 된다. */
    private static final Pattern RO_JOIN = Pattern.compile(
            "\\{fin\\.([a-z_]+)\\.[^{}]+\\}(?:으로|로)\\s*[^{},]*?(?:은|는|이|가)\\s*\\{fin\\.([a-z_]+)\\.");
    private static final Pattern ANY_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern PERIOD_KEY = Pattern.compile("(\\d{4})-(\\d{2})\\.Q([1-4])");

    private static final Set<String> QUANTITY_WORDS = Set.of("두 배", "세 배", "몇 배", "절반", "네 배");

    // "많이"·"상당히"·"꽤"는 D-58에서 더했다 — 872의 "{sig.S1} 매출이 전년 같은 분기보다 많이 늘었어요"처럼 크기를
    // 부풀리는 말이라 강도 표현과 같은 취급이다(신호 토큰이 있는 문장에서만, NONE이면 어디에도 없음).
    private static final Set<String> INTENSITY_WORDS = Set.of(
            "크게", "급격히", "뚜렷하게", "전환", "지속", "급등", "잠식", "많이", "상당히", "꽤");

    /** 신호 유형 → 지표 묶음(규칙 14 확장, D-58). 배지 뒤 같은 절의 첫 사실 토큰이 이 안에 있어야 한다. */
    private static final Map<String, Set<String>> SIGNAL_METRIC_FAMILY = Map.of(
            "FIN_REVENUE_CHANGE", Set.of("revenue", "revenue_yoy", "revenue_yoy_run"),
            "FIN_OPERATING_MARGIN_CHANGE", Set.of("operating_margin", "operating_margin_diff"),
            "FIN_OPERATING_TURN", Set.of("operating_income", "operating_income_turn", "operating_income_prior"),
            "FIN_OPERATING_LOSS_STREAK", Set.of("operating_loss_run", "operating_income", "operating_income_prior"),
            "FIN_DEBT_RATIO_JUMP", Set.of("debt_ratio", "debt_ratio_diff"),
            "FIN_CAPITAL_IMPAIRMENT", Set.of("total_equity", "capital_stock"));
    /** "(으)로" 잇기 검사(규칙 13 확장)의 지표 뿌리 — 변형(전년 동기·변화량·흐름 등)을 한데 묶는다. */
    private static final Map<String, String> METRIC_ROOTS = Map.ofEntries(
            Map.entry("revenue", "revenue"), Map.entry("revenue_yoy", "revenue"), Map.entry("revenue_yoy_run", "revenue"),
            Map.entry("revenue_prior", "revenue"),
            Map.entry("operating_income", "operating_income"), Map.entry("operating_income_prior", "operating_income"),
            Map.entry("operating_income_turn", "operating_income"), Map.entry("operating_loss_run", "operating_income"),
            Map.entry("operating_margin", "operating_margin"), Map.entry("operating_margin_prior", "operating_margin"),
            Map.entry("operating_margin_diff", "operating_margin"),
            Map.entry("net_income", "net_income"), Map.entry("net_income_prior", "net_income"),
            Map.entry("net_income_turn", "net_income"), Map.entry("net_income_status", "net_income"),
            Map.entry("debt_ratio", "debt_ratio"), Map.entry("debt_ratio_diff", "debt_ratio"));

    // 증감 어휘(산술 방향, D-55 R1). '지다' 축약형(§7.4.19 G1): 어간 '지'에 -어/-었/-ㄴ/-ㄹ/-ㅁ/-ㅂ니다가 붙으면
    // 져·졌·진·질·짐·집으로 줄어 "높아지" 같은 어간 부분 문자열이 사라진다. 늘·줄은 ㄹ 어간이라 활용형에 어간이 남고,
    // 한자어+하다/되다는 이 축약이 없다. "높아요"·"낮아요"(수준 서술)는 축약형에 없어 증감으로 세지 않는다.
    private static final Set<String> INCREASE_WORDS = Set.of(
            "늘", "증가", "확대", "높아지", "높아져", "높아졌", "높아진", "높아질", "높아짐", "높아집");
    private static final Set<String> DECREASE_WORDS = Set.of(
            "줄", "감소", "축소", "낮아지", "낮아져", "낮아졌", "낮아진", "낮아질", "낮아짐", "낮아집");
    // 평가어(좋고 나쁨, D-55): 증감 사전에서 빼고 방향이 맞는 신호가 있는 문장에서만 쓴다. 좋고 나쁨은 신호에만 있다(§3.9, D-47).
    private static final Set<String> GOOD_WORDS = Set.of(
            "개선", "좋아지", "좋아져", "좋아졌", "좋아진", "좋아질", "좋아짐", "좋아집");
    private static final Set<String> BAD_WORDS = Set.of(
            "악화", "나빠지", "나빠져", "나빠졌", "나빠진", "나빠질", "나빠짐", "나빠집");

    /** 규칙 12: 토큰 밖에 쓰지 않는 흑자·적자 상태 명사(D-55, "손실"·"손해"는 최종 점검에서 추가). */
    private static final Set<String> STATE_WORDS = Set.of("흑자", "적자", "손실", "손해");
    /** 규칙 12의 유일한 예외: 연속 영업적자 흐름 사실이 있는 문장의 "영업적자"(D-51). */
    private static final String LOSS_RUN_EXCEPTION = "영업적자";
    private static final Set<String> TURN_PREDICATES = Set.of("바뀌", "바뀐", "바뀔", "바뀜", "바꼈", "돌아서", "돌아섰", "돌아선");
    private static final Set<String> CONTINUE_PREDICATES = Set.of("이어지", "이어져", "이어졌", "이어진", "이어질");

    // "조금"·"약간"·"살짝"은 D-58에서 더했다 — 코드는 변화의 크기를 "작다"고 판정하지 않는다("대폭"·"소폭"은 이미
    // "폭"으로 걸린다).
    /** 규칙 13: 코드가 줄 수 없는 관계라 근거와 무관하게 늘 실패하는 어휘(D-55). */
    private static final Set<String> ALWAYS_FORBIDDEN_RELATIONS = Set.of(
            "비슷", "같은 수준", "훨씬", "폭", "조금", "약간", "살짝");
    /** 규칙 13: "보다" 뒤에 오면 크기 비교가 되는 어휘. "그보다 앞선"(시간)은 여기에 걸리지 않는다. */
    private static final Set<String> SIZE_WORDS = Set.of(
            "많", "적어", "적었", "적다", "적은", "적게", "크", "큰", "커", "컸", "작", "높", "낮");
    /** 규칙 13: 대조 어휘. 전환 사실(R4) 또는 상태 차이 관계(R5)가 근거다. */
    private static final Set<String> CONTRAST_WORDS = Set.of("달리", "다르게", "달라");
    /** 규칙 7 확장: 시간 관계 어휘는 history에서만(D-55 R8). */
    private static final Set<String> TIME_WORDS = Set.of("앞선", "앞서", "이전", "이후");

    /** docs/spec/analyses.md §7.2 + §4.4.7 규칙 9의 추가 목록. */
    private static final Map<String, Set<String>> FORBIDDEN = Map.ofEntries(
            Map.entry("투자 행위 권유", Set.of("매수", "매도", "추천", "비중 확대", "담아")),
            Map.entry("가격 예측", Set.of("목표가", "적정 주가", "상승 여력")),
            Map.entry("가치 판정", Set.of("저평가", "고평가", "유망")),
            Map.entry("미래 단정", Set.of("할 것이다", "할 것으로 예상", "확실히", "반드시")),
            Map.entry("종합 판정", Set.of("전반적으로", "좋은 기업", "나쁜 기업")),
            Map.entry("원인 접속어", Set.of("때문에", "덕분에", "탓에", "로 인해", "영향으로")),
            Map.entry("입력에 없는 비교", Set.of("전분기", "직전 분기", "지난 분기보다", "경쟁사", "업계 대비", "업계보다")),
            Map.entry("입력에 없는 주제", Set.of("현금흐름", "주가", "시가총액", "배당", "PER", "PBR", "ROE")),
            Map.entry("회사 전체 평가어", Set.of("안정적", "건전", "우량", "부실", "양호", "탄탄", "위험한 기업")),
            // fx-v7 통과본 2715가 "…sales_profit 섹션에서 확인할 수 있어요"라고 입력 계약의 이름을 사용자 문장에 흘렸다(§7.4.28).
            // 토큰을 뺀 문장에서만 본다(토큰 키 안의 영문은 대상이 아니다).
            Map.entry("내부 용어 노출", Set.of("sales_profit", "overview", "structure", "history", "sectionFacts",
                    "relations", "doNotMention", "unavailable", "섹션", "토큰")));

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

        Context ctx = new Context(input);
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
                        if (!ctx.factByKey.containsKey(t.raw())) {
                            failedRules.add("2");
                        } else {
                            factTokenCount.merge(t.raw(), 1, Integer::sum);
                        }
                    } else if (t.kind().equals("per")) {
                        if (!ctx.periodKeys.contains(t.value())) {
                            failedRules.add("2");
                        }
                    } else if (t.kind().equals("sig")) {
                        if (!ctx.signalByRef.containsKey(t.value())) {
                            failedRules.add("2");
                        } else {
                            signalRefCount.merge(t.value(), 1, Integer::sum);
                        }
                    }
                }
                String words = TOKEN.matcher(sentence).replaceAll(" ");
                checkNumbersAndQuantity(sentence, failedRules);
                checkIntensityWords(sentence, tokens, input.changeStatus(), failedRules);
                checkDirection(sentence, words, tokens, ctx, failedRules);
                checkState(words, tokens, failedRules);
                checkRelations(words, tokens, ctx, failedRules);
                checkHistoryTense(words, tokens, section, ctx, failedRules);
                checkBadgePosition(sentence, failedRules);
                checkBadgeMetricBinding(sentence, section, ctx, input.sectionFacts(), failedRules);
                checkMetricJoining(sentence, failedRules);
                checkForbidden(words, failedRules);
            }
            checkHistoryOrder(section, tokensIn(text), failedRules);
            checkSectionFacts(section, tokensIn(text), input.sectionFacts(), failedRules);
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

    /** 입력에서 뽑은 조회용 색인. */
    private static final class Context {
        final Map<String, FinancialExplainInput.Fact> factByKey = new LinkedHashMap<>();
        final Map<String, FinancialExplainInput.SignalRef> signalByRef = new LinkedHashMap<>();
        final Set<String> periodKeys = new LinkedHashSet<>();
        final List<FinancialExplainInput.Relation> directionRelations = new ArrayList<>();
        /** 상태 차이 관계(R5)가 걸린 순이익 상태·전환 사실 키. */
        final Set<String> stateDifferenceFacts = new LinkedHashSet<>();

        Context(FinancialExplainInput input) {
            input.facts().forEach(f -> factByKey.put(f.key(), f));
            input.signals().forEach(s -> signalByRef.put(s.ref(), s));
            input.periods().forEach(p -> periodKeys.add(p.key()));
            if (input.relations() != null) {
                for (FinancialExplainInput.Relation r : input.relations()) {
                    if (FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION.equals(r.type()) && r.factKeys().size() == 2) {
                        directionRelations.add(r);
                    } else if (FinancialExplainInput.Relation.STATE_DIFFERENCE.equals(r.type()) && !r.factKeys().isEmpty()) {
                        stateDifferenceFacts.add(r.factKeys().get(0));
                    }
                }
            }
        }
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
        return KoreanText.splitSentences(text);
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

    private static boolean containsAny(String text, Set<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    /** 사실 키의 지표 이름("fin.<지표>.<기간 키>"). 기간 키에도 점이 있어 두 번째 점까지만 자른다. */
    private static boolean isMetric(TokenRef t, String metric) {
        return t.kind().equals("fin") && t.raw().startsWith("fin." + metric + ".");
    }

    /** 변화량 사실(전년 동기 대비 증가율·차이). 흐름 사실({@code _yoy_run})은 아니다. */
    private static boolean isChangeToken(TokenRef t) {
        return t.kind().equals("fin") && (t.raw().contains("_yoy.") || t.raw().contains("_diff."));
    }

    private static boolean isTurnToken(TokenRef t) {
        return t.kind().equals("fin") && t.raw().contains("_turn.");
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

    /**
     * 문장의 증감 근거(D-55 R1·R2)의 방향 집합: "+"·"-"·"0". 변화량 토큰·매출 연속 흐름의 부호, 신호 유형별 산술 방향
     * 대응표, 방향 관계(R2, 두 사실 토큰이 같은 문장에 있을 때). 흑자·적자 전환·영업적자 지속·자본잠식 신호는 증감 근거가 아니다.
     */
    private static Set<String> directionEvidence(List<TokenRef> tokens, Context ctx) {
        Set<String> signs = new LinkedHashSet<>();
        Set<String> rawTokens = new LinkedHashSet<>();
        for (TokenRef t : tokens) {
            rawTokens.add(t.raw());
            if (isChangeToken(t) || isMetric(t, "revenue_yoy_run")) {
                var fact = ctx.factByKey.get(t.raw());
                if (fact != null) {
                    signs.add(fact.sign());
                }
            } else if (t.kind().equals("sig")) {
                var signal = ctx.signalByRef.get(t.value());
                String sign = signal == null ? null : signalArithmeticSign(signal);
                if (sign != null) {
                    signs.add(sign);
                }
            }
        }
        for (FinancialExplainInput.Relation r : ctx.directionRelations) {
            if (rawTokens.contains(r.factKeys().get(0)) && rawTokens.contains(r.factKeys().get(1))) {
                signs.add(switch (r.value()) {
                    case "UP" -> "+";
                    case "DOWN" -> "-";
                    default -> "0";
                });
            }
        }
        return signs;
    }

    /** 신호 유형별 산술 방향 대응표(D-55). 좋고 나쁨과 산술 방향이 다른 부채비율 급등(NEGATIVE)은 "높아짐"이다. */
    private static String signalArithmeticSign(FinancialExplainInput.SignalRef signal) {
        return switch (signal.type()) {
            case "FIN_REVENUE_CHANGE", "FIN_OPERATING_MARGIN_CHANGE" -> switch (signal.direction()) {
                case "POSITIVE" -> "+";
                case "NEGATIVE" -> "-";
                default -> null;
            };
            case "FIN_DEBT_RATIO_JUMP" -> "+";
            default -> null;
        };
    }

    /**
     * 규칙 6(증감, D-55 R1·R2·R7·R9): 증감 어휘는 같은 문장의 증감 근거와 방향이 같아야 한다. 변화량 토큰에는 그 부호의 증감
     * 어휘가 있어야 한다(D-54, 부호가 섞이면 두 방향 모두). 평가어는 방향이 맞는 신호가 같은 문장에 있을 때만 쓴다.
     * 변화량 토큰의 부호가 한 문장에서 갈리면(D-58, 개요 2사실 등) 문장 전체가 아니라 절 단위로도 본다 — 그렇지 않으면
     * "매출은 …줄었지만 영업이익률은 …높아졌어요"에서 두 어휘가 서로 바뀌어도(늘다·낮아지다 자리를 바꿔도) 문장 전체
     * 기준으로는 두 어휘가 다 있어 통과해 버린다(820, docs/work/3-4-verification-2.md §7.4.33).
     */
    private static void checkDirection(String sentence, String words, List<TokenRef> tokens, Context ctx, Set<String> failedRules) {
        boolean increase = containsAny(words, INCREASE_WORDS);
        boolean decrease = containsAny(words, DECREASE_WORDS);

        Set<String> changeSigns = new LinkedHashSet<>();
        for (TokenRef t : tokens) {
            if (isChangeToken(t)) {
                var fact = ctx.factByKey.get(t.raw());
                if (fact != null) {
                    changeSigns.add(fact.sign());
                }
            }
        }
        if ((changeSigns.contains("+") && !increase) || (changeSigns.contains("-") && !decrease)) {
            failedRules.add("6");
        }
        if (changeSigns.size() > 1) {
            checkDirectionPerClause(sentence, ctx, failedRules);
        }

        Set<String> evidence = directionEvidence(tokens, ctx);
        if (evidence.size() == 1) {
            String sign = evidence.iterator().next();
            if (("+".equals(sign) && decrease) || ("-".equals(sign) && increase) || ("0".equals(sign) && (increase || decrease))) {
                failedRules.add("6");
            }
        }

        boolean good = containsAny(words, GOOD_WORDS);
        boolean bad = containsAny(words, BAD_WORDS);
        if (good && !hasSignalWithDirection(tokens, ctx, "POSITIVE")) {
            failedRules.add("6");
        }
        if (bad && !hasSignalWithDirection(tokens, ctx, "NEGATIVE")) {
            failedRules.add("6");
        }
    }

    /** 규칙 6 확장(D-58): 절마다 자기 변화량 토큰의 부호와 증감 어휘가 맞는지 본다. 변화량 토큰이 없는 절은 건너뛴다. */
    private static void checkDirectionPerClause(String sentence, Context ctx, Set<String> failedRules) {
        for (String clause : splitClauses(sentence)) {
            String clauseWords = TOKEN.matcher(clause).replaceAll(" ");
            Set<String> signs = new LinkedHashSet<>();
            for (TokenRef t : tokensIn(clause)) {
                if (isChangeToken(t)) {
                    var fact = ctx.factByKey.get(t.raw());
                    if (fact != null) {
                        signs.add(fact.sign());
                    }
                }
            }
            if (signs.isEmpty()) {
                continue;
            }
            boolean increase = containsAny(clauseWords, INCREASE_WORDS);
            boolean decrease = containsAny(clauseWords, DECREASE_WORDS);
            if ((signs.contains("+") && !increase) || (signs.contains("-") && !decrease)) {
                failedRules.add("6");
            }
        }
    }

    /** 절 경계(쉼표, 연결 어미 뒤 공백)로 나눈다. 경계 자체는 버린다 — 절 안의 토큰·어휘만 있으면 된다. */
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(",|고\\s|며\\s|면서\\s|지만\\s|는데\\s");

    private static List<String> splitClauses(String sentence) {
        return List.of(CLAUSE_SPLIT.split(sentence));
    }

    private static boolean hasSignalWithDirection(List<TokenRef> tokens, Context ctx, String direction) {
        return tokens.stream()
                .filter(t -> t.kind().equals("sig"))
                .map(t -> ctx.signalByRef.get(t.value()))
                .anyMatch(s -> s != null && direction.equals(s.direction()));
    }

    /**
     * 규칙 12(흑자·적자, D-55 R3·R4·R7): 토큰 밖에 흑자·적자·손실·손해를 쓰지 않는다(예외: 연속 영업적자 흐름 사실이 있는
     * 문장의 "영업적자"). 전환 서술어는 전환 사실 토큰이 있는 문장에서만, 이어짐 서술어는 전환 사실 토큰과 함께 쓰지 않는다.
     */
    private static void checkState(String words, List<TokenRef> tokens, Set<String> failedRules) {
        boolean hasLossRun = tokens.stream().anyMatch(t -> isMetric(t, "operating_loss_run"));
        String checked = hasLossRun ? words.replace(LOSS_RUN_EXCEPTION, " ") : words;
        if (containsAny(checked, STATE_WORDS)) {
            failedRules.add("12");
        }
        boolean hasTurn = tokens.stream().anyMatch(FinancialExplainValidator::isTurnToken);
        if (containsAny(words, TURN_PREDICATES) && !hasTurn) {
            failedRules.add("12");
        }
        if (containsAny(words, CONTINUE_PREDICATES) && hasTurn) {
            failedRules.add("12");
        }
    }

    /**
     * 규칙 13(코드가 주지 않은 관계, D-55): 관계 어휘는 자기 종류의 근거가 같은 문장에 있을 때만 쓴다.
     * <ul>
     * <li>증감 어휘: 증감 근거(규칙 6의 근거)가 하나도 없으면 실패.</li>
     * <li>"보다" + 크기 어휘: 증감 근거(R1·R2) 또는 활성 자본잠식 신호(R6).</li>
     * <li>대조("달리" 등): 전환 사실(R4) 또는 상태 차이 관계(R5)의 순이익 사실.</li>
     * <li>비슷·같은 수준·훨씬·폭: 근거와 무관하게 실패.</li>
     * </ul>
     */
    private static void checkRelations(String words, List<TokenRef> tokens, Context ctx, Set<String> failedRules) {
        if (containsAny(words, ALWAYS_FORBIDDEN_RELATIONS)) {
            failedRules.add("13");
        }
        boolean hasDirectionEvidence = !directionEvidence(tokens, ctx).isEmpty();
        if ((containsAny(words, INCREASE_WORDS) || containsAny(words, DECREASE_WORDS)) && !hasDirectionEvidence) {
            failedRules.add("13");
        }
        int comparative = words.indexOf("보다");
        if (comparative >= 0) {
            String after = words.substring(comparative + "보다".length());
            boolean sizeComparison = containsAny(after, SIZE_WORDS) || containsAny(after, INCREASE_WORDS)
                    || containsAny(after, DECREASE_WORDS);
            boolean impairment = tokens.stream()
                    .filter(t -> t.kind().equals("sig"))
                    .map(t -> ctx.signalByRef.get(t.value()))
                    .anyMatch(s -> s != null && "FIN_CAPITAL_IMPAIRMENT".equals(s.type()));
            if (sizeComparison && !hasDirectionEvidence && !impairment) {
                failedRules.add("13");
            }
        }
        if (containsAny(words, CONTRAST_WORDS)) {
            boolean contrastEvidence = tokens.stream().anyMatch(
                    t -> isTurnToken(t) || (t.kind().equals("fin") && ctx.stateDifferenceFacts.contains(t.raw())));
            if (!contrastEvidence) {
                failedRules.add("13");
            }
        }
    }

    /**
     * 규칙 14(D-56): 배지는 문장 맨 앞(앞에 다른 배지만 있어도 됨), 절의 맨 앞(바로 앞이 쉼표·연결 어미·기간 부사어), "… 신호" 앞에만
     * 둔다. 값과 서술어 사이("매출은 {…}로 {sig.S1} 줄었어요")에 넣지 않는다. 절의 맨 앞을 허용하는 것은 D-55 R9가 두 지표를 한
     * 문장에 쓸 때 지표마다 자기 배지를 두게 하기 때문이다("{sig.S1} 매출이 늘었지만 {sig.S2} 영업이익률은 …").
     */
    private static void checkBadgePosition(String sentence, Set<String> failedRules) {
        Matcher m = BADGE.matcher(sentence);
        while (m.find()) {
            if (KoreanText.isNameBadge(sentence, m.end()) || KoreanText.isLeadingBadge(sentence, m.start())) {
                continue;
            }
            failedRules.add("14");
            return;
        }
    }

    /**
     * 규칙 14 확장(D-58): 앞머리 배지 뒤 같은 절의 첫 사실 토큰은 그 신호의 지표 묶음이어야 한다. 그 섹션에 그 지표
     * 묶음의 사실이 하나라도 배정돼 있으면, 사실 토큰이 아예 없는 절(배지만 되풀이하는 절)도 실패다 — 배정돼 있지
     * 않으면(이력 신호, 사실 없이 배지만 쓰는 structure) 이 검사를 하지 않는다. 이름 배지("… 신호" 앞)는 대상이 아니다.
     */
    private static void checkBadgeMetricBinding(String sentence, String section, Context ctx,
            Map<String, List<String>> sectionFacts, Set<String> failedRules) {
        if (sectionFacts == null) {
            return;
        }
        List<String> assigned = sectionFacts.get(section);
        if (assigned == null) {
            return;
        }
        Set<String> assignedMetrics = assigned.stream().map(FinancialExplainValidator::metricOf)
                .collect(java.util.stream.Collectors.toSet());
        Matcher m = BADGE.matcher(sentence);
        while (m.find()) {
            if (KoreanText.isNameBadge(sentence, m.end()) || !KoreanText.isLeadingBadge(sentence, m.start())) {
                continue;
            }
            var signal = ctx.signalByRef.get(m.group(1));
            if (signal == null) {
                continue;
            }
            Set<String> family = SIGNAL_METRIC_FAMILY.get(signal.type());
            if (family == null || java.util.Collections.disjoint(family, assignedMetrics)) {
                continue;
            }
            String clause = sentence.substring(m.end(), findClauseEnd(sentence, m.end()));
            Matcher fm = Pattern.compile("\\{fin\\.([a-z_]+)\\.").matcher(clause);
            if (fm.find()) {
                if (!family.contains(fm.group(1))) {
                    failedRules.add("14");
                }
            } else {
                failedRules.add("14");
            }
        }
    }

    private static int findClauseEnd(String sentence, int from) {
        int end = sentence.length();
        for (String marker : CLAUSE_MARKERS) {
            int idx = sentence.indexOf(marker, from);
            if (idx >= 0) {
                end = Math.min(end, idx);
            }
        }
        int nextBadge = sentence.indexOf("{sig.", from);
        if (nextBadge >= 0) {
            end = Math.min(end, nextBadge);
        }
        return end;
    }

    /** "fin.<지표>.<기간 키>"에서 지표 이름만("<지표>.<기간 키>" 전체가 아니라 첫 점 앞까지). */
    private static String metricOf(String finKey) {
        String body = finKey.startsWith("fin.") ? finKey.substring(4) : finKey;
        int dot = body.indexOf('.');
        return dot < 0 ? body : body.substring(0, dot);
    }

    /**
     * 규칙 13 확장(D-58): 서로 다른 지표를 "(으)로"로 잇지 않는다("영업이익은 {…}로 영업이익률은 {…}였어요"). 같은
     * 지표의 값과 그 비교("영업이익은 {…}로 전년 같은 분기 {…}보다 늘었어요")는 조사가 두 번째 토큰 바로 앞에 오지
     * 않아 걸리지 않는다. 쉼표 뒤는 새 절이라 걸리지 않는다(자본잠식 비교 등).
     */
    private static void checkMetricJoining(String sentence, Set<String> failedRules) {
        Matcher m = RO_JOIN.matcher(sentence);
        while (m.find()) {
            String rootA = METRIC_ROOTS.getOrDefault(m.group(1), m.group(1));
            String rootB = METRIC_ROOTS.getOrDefault(m.group(2), m.group(2));
            if (!rootA.equals(rootB)) {
                failedRules.add("13");
            }
        }
    }

    /** 흐름 사실(최신 기간까지 이어지는 상태라 history 대상이 아니다, D-45). */
    private static final Set<String> ONGOING_FLOW_METRICS = Set.of("revenue_yoy_run", "operating_loss_run");

    /**
     * 규칙 7: 신호는 코드가 정한 {@code section}에서만(활성 신호는 overview에서도 한 번), 흐름 사실은 history에 쓰지 않는다
     * (D-45·D-54). 시간 관계 어휘(앞선·이전·이후)는 history에서만 쓴다(D-55 R8).
     */
    private static void checkHistoryTense(String words, List<TokenRef> tokens, String section, Context ctx,
            Set<String> failedRules) {
        if (!"history".equals(section) && containsAny(words, TIME_WORDS)) {
            failedRules.add("7");
        }
        // D-56: 흐름 사실은 최신 기간 말까지 분기로 센 "지금의" 사실이다. 사업보고서 기간과 그 파생 4분기가 같은 기간 키라
        // 기간 토큰을 붙이면 회계연도 라벨이 분기 수에 붙는다("{회계연도} 영업적자가 {6분기}째").
        boolean hasFlowFact = tokens.stream().anyMatch(
                t -> t.kind().equals("fin") && ONGOING_FLOW_METRICS.stream().anyMatch(m -> t.raw().startsWith("fin." + m + ".")));
        if (hasFlowFact && tokens.stream().anyMatch(t -> t.kind().equals("per"))) {
            failedRules.add("7");
        }
        for (TokenRef t : tokens) {
            if (t.kind().equals("fin") && "history".equals(section)
                    && ONGOING_FLOW_METRICS.stream().anyMatch(m -> t.raw().startsWith("fin." + m + "."))) {
                failedRules.add("7");
                continue;
            }
            if (!t.kind().equals("sig")) {
                continue;
            }
            var signal = ctx.signalByRef.get(t.value());
            if (signal == null) {
                continue;
            }
            boolean isPast = "PAST".equals(signal.status());
            if (signal.section() != null) {
                // D-54: 신호는 코드가 정한 section에서만, 활성 신호는 overview에서도 한 번 쓸 수 있다. 이력 신호의
                // section은 history라 "이력은 history에서만"이 여기에 포함된다.
                boolean allowed = signal.section().equals(section) || (!isPast && "overview".equals(section));
                if (!allowed) {
                    failedRules.add("7");
                }
                continue;
            }
            // section이 없는 이전 입력(fx-input-2 이하)은 상태로만 판정한다.
            if (isPast && !"history".equals(section)) {
                failedRules.add("7");
            }
            if (!isPast && "history".equals(section)) {
                failedRules.add("7");
            }
        }
    }

    /**
     * 규칙 7 확장(D-55 R8): history의 기간 토큰은 입력 묶음 순서(최근 → 과거)를 거스르지 않는다 — 뒤에 나온 기간의 종료일이
     * 앞 기간보다 늦으면 실패(같은 기간은 허용). 기간 토큰이 없는 개수 문장(D-52)은 대상이 아니다.
     */
    private static void checkHistoryOrder(String section, List<TokenRef> tokens, Set<String> failedRules) {
        if (!"history".equals(section)) {
            return;
        }
        LocalDate previous = null;
        for (TokenRef t : tokens) {
            if (!t.kind().equals("per")) {
                continue;
            }
            LocalDate end = periodEnd(t.value());
            if (end == null) {
                continue;
            }
            if (previous != null && end.isAfter(previous)) {
                failedRules.add("7");
                return;
            }
            previous = end;
        }
    }

    /** 기간 키("회계연도 시작 연월.Q분기")의 종료일. 형식이 다르면 null(순서 검사를 건너뛴다). */
    static LocalDate periodEnd(String periodKey) {
        Matcher m = PERIOD_KEY.matcher(periodKey);
        if (!m.matches()) {
            return null;
        }
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(m.group(1)), month, 1)
                .plusMonths(3L * Integer.parseInt(m.group(3))).minusDays(1);
    }

    /**
     * 규칙 11(D-54, D-57로 확장): 코드가 사실을 배정한 섹션({@code sectionFacts}의 키)은 배정된 사실 토큰만 쓴다. `fx-input-4`부터
     * 네 섹션 모두 배정이 있다(history는 빈 목록이라 사실 토큰을 쓰면 실패). {@code sectionFacts}가 없거나 그 섹션 키가 없는 이전
     * 입력은 검사하지 않는다. {@code overview}는 완전성도 본다 — 배정된 사실은 모두 써야 한다(D-57). 코드가 첫 신호 묶음의 변화
     * 사실을 여러 개 배정할 수 있는데(신호가 둘이면 둘), 하나만 쓰고 나머지를 버리면 그 사실은 어느 섹션에서도 설명되지 않는다.
     * {@code sales_profit}·{@code structure}는 배정 안에서 무엇을 어떻게 묶어 쓸지 AI가 정할 자유가 있어(D-54 보완) 완전성을
     * 요구하지 않는다.
     */
    private static void checkSectionFacts(String section, List<TokenRef> tokens, Map<String, List<String>> sectionFacts,
            Set<String> failedRules) {
        if (sectionFacts == null || !sectionFacts.containsKey(section)) {
            return;
        }
        List<String> allowed = sectionFacts.get(section);
        if (allowed == null) {
            return;
        }
        Set<String> used = tokens.stream().filter(t -> t.kind().equals("fin")).map(TokenRef::raw)
                .collect(java.util.stream.Collectors.toSet());
        boolean outside = used.stream().anyMatch(k -> !allowed.contains(k));
        boolean incomplete = "overview".equals(section) && !used.containsAll(allowed);
        if (outside || incomplete) {
            failedRules.add("11");
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
            String name = UNAVAILABLE_METRIC_NAMES.get(u.metric());
            if (name != null && text.contains(name)) {
                failedRules.add("8");
            }
        }
    }

    /** 규칙 9: 금지 표현. 토큰을 뺀 문장(AI가 직접 쓴 말)만 본다. */
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
