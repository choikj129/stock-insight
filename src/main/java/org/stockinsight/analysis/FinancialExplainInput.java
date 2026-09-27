package org.stockinsight.analysis;

import java.util.List;
import java.util.Map;

/**
 * 재무 쉬운 설명의 AI 입력 전체(docs/spec/financial-explain.md §4.4.2, D-38). 코드가 만든 사실표와 신호 참조뿐이고,
 * 원천 행·분기 시계열·공시번호·내부 ID는 없다. 그대로 직렬화해 AI에 주고 {@code analysis.input_json}에도 저장한다.
 *
 * <p>D-54: 신호·묶음의 {@code section}과 {@link #sectionFacts}로 "어느 섹션에서 무엇을 쓸지"를 코드가 정한다.
 * D-55: 사실 사이의 관계 가운데 사실표만으로 드러나지 않는 것(영업이익 전년 대비 방향, 순이익·영업이익 상태 차이)은
 * {@link #relations}로 준다. AI가 쓸 수 있는 관계는 입력에 있는 것뿐이다.
 * 이전에 저장된 입력(`fx-input-2` 이하는 {@code section}·{@code sectionFacts}, `fx-input-3` 이하는 {@code relations})에는
 * 이 필드가 없다(null) — 검증기는 null이면 그 판정을 건너뛰거나 이전 판정 방식을 쓴다.
 *
 * @param sectionFacts 섹션 → 그 섹션이 쓸 수 있는 사실 키. `fx-input-4`부터 네 섹션 모두 있고 사실표는 이 목록의 합집합이다
 *                     ({@code history}는 빈 목록). 목록에 없는 섹션은 배정 제한이 없다(이전 입력)
 * @param relations    코드가 판정한 관계 항목(D-55 R2·R5). 토큰이 아니고 값 스냅샷에도 넣지 않는다
 *
 * <p>D-59(fx-input-6): 사람이 읽는 표시 문자열({@link Fact#name() 대신 있던} {@code display}, {@link PeriodLabel}의
 * {@code label})을 뺐다. 첫 시도에서 이 문자열을 그대로 옮겨 쓰는(토큰 대신 숫자·기간을 글자로 복사하는) 문제가
 * 반복되어(docs/work/3-4-verification-2.md §7.4.30·§7.4.33), AI가 볼 필요 없는 표시 문구를 입력에서 없앤다 — 렌더러가
 * 이미 조사·부호·서식을 만들므로(D-56) AI는 표시 값을 몰라도 된다. {@link PeriodLabel}은 대신 {@code kind}
 * (QUARTER·ANNUAL)를 준다.
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
        Map<String, List<String>> sectionFacts,
        List<Relation> relations,
        List<Unavailable> unavailable,
        List<String> doNotMention) {

    /** 관계 항목이 없는 입력(관계가 걸리지 않는 경우의 테스트 입력, `fx-input-3` 이하 입력과 같은 모양). */
    public FinancialExplainInput(Company company, Latest latest, String changeStatus, List<String> sections,
            List<PeriodLabel> periods, List<Fact> facts, List<SignalRef> signals, List<Group> groups,
            Map<String, List<String>> sectionFacts, List<Unavailable> unavailable, List<String> doNotMention) {
        this(company, latest, changeStatus, sections, periods, facts, signals, groups, sectionFacts, List.of(),
                unavailable, doNotMention);
    }

    public record Company(String name, String format, String currency, String basis, Integer fiscalYearEndMonth) {
    }

    public record Latest(String period, String kind) {
    }

    /** @param kind {@code QUARTER}·{@code ANNUAL}(D-59). 라벨 글자는 없다 — {@code {per.…}} 토큰의 표시는 렌더러가 만든다 */
    public record PeriodLabel(String key, String kind) {
    }

    /** D-59: 표시 값은 없다. AI는 {@code {fin.…}} 토큰으로만 값을 쓰고, 렌더링 서식은 값 스냅샷의 원값으로 만든다(D-41·D-56). */
    public record Fact(String key, String name, String sign, String period) {
    }

    /**
     * @param period  설명 기준 기간(D-54). 활성 신호는 최신 기간, 이력 신호는 성립한 기간. 조건이 처음 성립한 기간(발생
     *                기준일)은 넣지 않는다 — 배지가 연결되는 타임라인(코드)이 보여 준다
     * @param section 이 신호를 쓰는 섹션(D-54). 활성 → 주제 섹션({@code sales_profit}/{@code structure}), 이력 → {@code history}.
     *                활성 신호는 {@code overview}에도 한 번 쓸 수 있다
     */
    public record SignalRef(String ref, String type, String status, String nature, String direction,
            String severity, String period, String section, Integer persistence, List<String> factKeys) {
    }

    /** 같은 섹션·같은 설명 기준 기간·같은 주제의 신호 묶음(D-54). 활성·이력이 한 묶음이 되지 않는다. */
    public record Group(String section, String period, String topic, List<String> refs, boolean mixedDirection) {
    }

    /**
     * 코드가 판정한 관계(D-55). 두 사실 키와 코드가 정한 값만 담는다(새 수치·자유 문자열 없음).
     *
     * @param type     {@link #OPERATING_INCOME_DIRECTION}(R2) 또는 {@link #STATE_DIFFERENCE}(R5)
     * @param factKeys 관계가 걸린 두 사실 키. 둘 다 사실표에 있을 때만 관계를 넣는다
     * @param value    R2: {@code UP}·{@code DOWN}·{@code SAME}. R5: {@code DIFFERENT}
     */
    public record Relation(String type, List<String> factKeys, String value) {
        /** R2: 최신·전년 영업이익이 모두 흑자일 때의 산술 방향. factKeys = [최신 영업이익, 전년 같은 기간 영업이익]. */
        public static final String OPERATING_INCOME_DIRECTION = "OPERATING_INCOME_DIRECTION";
        /** R5: 순이익의 흑자·적자가 영업이익과 다름. factKeys = [순이익 상태·전환 사실, 최신 영업이익]. */
        public static final String STATE_DIFFERENCE = "STATE_DIFFERENCE";
    }

    public record Unavailable(String metric, String reason) {
    }
}
