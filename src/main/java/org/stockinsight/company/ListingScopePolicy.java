package org.stockinsight.company;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * 대상 종목 범위: KOSPI·KOSDAQ 상장 보통주. 스팩, 리츠, KONEX는 제외한다 (docs/architecture.md §4.3).
 * ETF·ETN은 OpenDART 기업 목록에 상장사로 나오지 않으므로 여기서 다루지 않는다.
 */
@Component
public class ListingScopePolicy {

    private static final List<String> SPAC_KEYWORDS = List.of("스팩", "기업인수목적");
    private static final List<String> REIT_KEYWORDS = List.of("리츠", "부동산투자회사");

    public Decision classify(Market market, String... names) {
        if (market == Market.KONEX) {
            return Decision.excluded(ExclusionReason.KONEX);
        }
        if (market != Market.KOSPI && market != Market.KOSDAQ) {
            return Decision.excluded(ExclusionReason.OTHER_MARKET);
        }
        if (containsAny(names, SPAC_KEYWORDS)) {
            return Decision.excluded(ExclusionReason.SPAC);
        }
        if (containsAny(names, REIT_KEYWORDS)) {
            return Decision.excluded(ExclusionReason.REIT);
        }
        return Decision.INCLUDED;
    }

    private static boolean containsAny(String[] names, List<String> keywords) {
        return Stream.of(names)
                .filter(Objects::nonNull)
                .anyMatch(name -> keywords.stream().anyMatch(name::contains));
    }

    public record Decision(boolean included, ExclusionReason reason) {

        static final Decision INCLUDED = new Decision(true, null);

        static Decision excluded(ExclusionReason reason) {
            return new Decision(false, reason);
        }
    }
}
