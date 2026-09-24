package org.stockinsight.company;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ListingScopePolicyTest {

    private final ListingScopePolicy policy = new ListingScopePolicy();

    @Test
    void includesKospiAndKosdaqCommonCompanies() {
        assertThat(policy.classify(Market.KOSPI, "삼성전자", "삼성전자(주)").included()).isTrue();
        assertThat(policy.classify(Market.KOSDAQ, "에코프로", "(주)에코프로").included()).isTrue();
    }

    @Test
    void excludesKonexAndOtherMarkets() {
        assertThat(policy.classify(Market.KONEX, "코넥스기업", null).reason()).isEqualTo(ExclusionReason.KONEX);
        assertThat(policy.classify(Market.OTHER, "기타법인", null).reason()).isEqualTo(ExclusionReason.OTHER_MARKET);
    }

    @Test
    void excludesSpacByName() {
        assertThat(policy.classify(Market.KOSDAQ, "하나33호스팩", "하나33호기업인수목적(주)").reason())
                .isEqualTo(ExclusionReason.SPAC);
        assertThat(policy.classify(Market.KOSDAQ, "에스케이증권제9호", "에스케이증권제9호기업인수목적(주)").reason())
                .isEqualTo(ExclusionReason.SPAC);
    }

    @Test
    void excludesReitsByName() {
        assertThat(policy.classify(Market.KOSPI, "롯데리츠", "롯데위탁관리부동산투자회사(주)").reason())
                .isEqualTo(ExclusionReason.REIT);
    }

    @Test
    void mapsDartCorpClsToMarket() {
        assertThat(Market.fromDartCorpCls("Y")).isEqualTo(Market.KOSPI);
        assertThat(Market.fromDartCorpCls("K")).isEqualTo(Market.KOSDAQ);
        assertThat(Market.fromDartCorpCls("N")).isEqualTo(Market.KONEX);
        assertThat(Market.fromDartCorpCls("E")).isEqualTo(Market.OTHER);
        assertThat(Market.fromDartCorpCls(null)).isEqualTo(Market.OTHER);
    }

    @Test
    void buildsKoreanInitials() {
        assertThat(KoreanInitials.of("삼성전자")).isEqualTo("ㅅㅅㅈㅈ");
        assertThat(KoreanInitials.of("SK 하이닉스")).isEqualTo("SKㅎㅇㄴㅅ");
        assertThat(KoreanInitials.of(null)).isNull();
    }
}
