package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * 골든셋 12개사의 고정 입력(src/test/resources/golden/financial_explain)이 D-54 보완·D-55 입력 계약을 지키는지 확인한다
 * (docs/work/3-4-verification-2.md §7.4.26 점검표 1~6·20~22, §7.4.28). 입력 구성기를 바꾸면 GoldenSetDumpRunner로 fixture를
 * 다시 만들고 이 테스트로 계약을 점검한다. DB·외부 호출 없이 파일만 읽는다.
 */
class FinancialExplainGoldenContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * §7.4.26 모의 배정의 sales_profit 개수(2715는 금지 용어가 아닌 연간 영업이익이 더해져 4). D-59(fx-input-6)로
     * 2386의 12개월이 아닌 회계연도가 우선순위 7에서 빠져 4 → 3이 됐다(§7.4.33).
     */
    private static final Map<String, Integer> SALES_PROFIT_COUNT = Map.ofEntries(
            Map.entry("608", 5), Map.entry("716", 6), Map.entry("737", 6), Map.entry("810", 4),
            Map.entry("820", 6), Map.entry("872", 6), Map.entry("908", 6), Map.entry("1016", 3),
            Map.entry("1640", 6), Map.entry("2386", 3), Map.entry("2715", 4), Map.entry("2906", 6));

    private static FinancialExplainInput load(String companyId) throws IOException {
        try (InputStream in = FinancialExplainGoldenContractTest.class
                .getResourceAsStream("/golden/financial_explain/" + companyId + ".json")) {
            assertThat(in).as("fixture " + companyId).isNotNull();
            return MAPPER.readValue(in, FinancialExplainInput.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"608", "716", "737", "810", "820", "872", "908", "1016", "1640", "2386", "2715", "2906"})
    void goldenInputKeepsTheRelationContract(String companyId) throws IOException {
        FinancialExplainInput input = load(companyId);
        Map<String, List<String>> sectionFacts = input.sectionFacts();

        // 1. 사실표 = 네 섹션 배정의 합집합, 한 사실은 한 섹션에만, history는 비어 있음, 섹션 상한 이하
        assertThat(sectionFacts).containsOnlyKeys("overview", "sales_profit", "structure", "history");
        assertThat(sectionFacts.get("history")).isEmpty();
        List<String> assigned = new ArrayList<>();
        sectionFacts.values().forEach(assigned::addAll);
        assertThat(assigned).doesNotHaveDuplicates();
        assertThat(input.facts()).extracting(FinancialExplainInput.Fact::key).containsExactlyInAnyOrderElementsOf(assigned);
        assertThat(sectionFacts.get("overview")).hasSizeLessThanOrEqualTo(3)
                .allSatisfy(k -> assertThat(FinancialExplainInputBuilder.isChangeFact(k) || FinancialExplainInputBuilder.isTurnFact(k)).isTrue());
        assertThat(sectionFacts.get("sales_profit")).hasSize(SALES_PROFIT_COUNT.get(companyId));
        assertThat(sectionFacts.get("structure")).hasSizeLessThanOrEqualTo(4);
        assertThat(assigned).noneMatch(k -> k.startsWith("fin.revenue_prior") || k.startsWith("fin.operating_margin_prior")
                || k.startsWith("fin.net_income_prior") || k.startsWith("fin.debt_ratio_prior_end"));

        // 2·21. 상태 사실은 순이익에만. 상태·전환 사실도 부호가 있다(D-59: 표시 값은 AI 입력에 없다 — 값 스냅샷에서만 확인한다).
        for (FinancialExplainInput.Fact f : input.facts()) {
            if (f.key().contains("_status.")) {
                assertThat(f.key()).startsWith("fin.net_income_status.");
                assertThat(f.sign()).isIn("+", "-");
            }
            if (f.key().contains("_turn.")) {
                assertThat(f.sign()).isIn("+", "-");
            }
        }
        // D-59: AI 입력에는 사람이 읽는 표시 문자열이 없다 — 기간은 kind(QUARTER·ANNUAL)만 갖는다.
        assertThat(input.periods()).allSatisfy(p -> assertThat(p.kind()).isIn("QUARTER", "ANNUAL"));

        // 3·4·22. 관계 항목은 두 사실 키와 코드 값뿐이고, 두 사실이 모두 배정됐을 때만 있다.
        for (FinancialExplainInput.Relation r : input.relations()) {
            assertThat(r.factKeys()).hasSize(2);
            assertThat(assigned).containsAll(r.factKeys());
            switch (r.type()) {
                case FinancialExplainInput.Relation.OPERATING_INCOME_DIRECTION -> {
                    assertThat(r.factKeys().get(0)).startsWith("fin.operating_income.");
                    assertThat(r.factKeys().get(1)).startsWith("fin.operating_income_prior.");
                    assertThat(r.value()).isIn("UP", "DOWN", "SAME");
                    // R2는 둘 다 흑자일 때만
                    assertThat(input.facts()).filteredOn(f -> r.factKeys().contains(f.key()))
                            .allSatisfy(f -> assertThat(f.sign()).isEqualTo("+"));
                }
                case FinancialExplainInput.Relation.STATE_DIFFERENCE -> {
                    assertThat(r.factKeys().get(0)).matches("fin\\.net_income_(status|turn)\\..+");
                    assertThat(r.factKeys().get(1)).startsWith("fin.operating_income.");
                    assertThat(r.value()).isEqualTo("DIFFERENT");
                }
                default -> throw new AssertionError("알 수 없는 관계: " + r.type());
            }
        }

        // 5. history 묶음은 설명 기준 기간 종료일 내림차순, 이력 신호의 근거 사실은 비어 있음
        LocalDate previous = null;
        for (FinancialExplainInput.Group g : input.groups()) {
            if (!g.section().equals("history")) {
                continue;
            }
            LocalDate end = FinancialExplainValidator.periodEnd(g.period());
            if (previous != null) {
                assertThat(end).as(companyId + " history 순서").isBeforeOrEqualTo(previous);
            }
            previous = end;
        }
        assertThat(input.signals()).filteredOn(s -> s.status().equals("PAST")).allSatisfy(s -> assertThat(s.factKeys()).isEmpty());
        assertThat(input.signals()).allSatisfy(s -> assertThat(assigned).containsAll(s.factKeys()));

        // 6. 금지 용어·계산하지 못한 지표와 이름이 모순되는 사실 없음(2715)
        Set<String> forbiddenNames = new LinkedHashSet<>(input.doNotMention());
        input.unavailable().stream().map(u -> FinancialExplainValidator.UNAVAILABLE_METRIC_NAMES.get(u.metric()))
                .filter(java.util.Objects::nonNull).forEach(forbiddenNames::add);
        assertThat(input.facts()).allSatisfy(f -> assertThat(forbiddenNames).noneMatch(f.name()::contains));

        // 토큰으로 쓸 기간은 모두 라벨이 있다(최신 기간, 사실의 기간, 신호의 설명 기준 기간)
        Set<String> periodKeys = new LinkedHashSet<>();
        input.periods().forEach(p -> periodKeys.add(p.key()));
        assertThat(periodKeys).contains(input.latest().period());
        input.facts().forEach(f -> assertThat(periodKeys).contains(f.period()));
        input.signals().forEach(s -> assertThat(periodKeys).contains(s.period()));
    }
}
