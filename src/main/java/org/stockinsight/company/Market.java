package org.stockinsight.company;

public enum Market {
    KOSPI, KOSDAQ, KONEX, OTHER;

    /** OpenDART 기업개황의 corp_cls(Y/K/N/E)를 시장으로 바꾼다. */
    public static Market fromDartCorpCls(String corpCls) {
        if (corpCls == null) {
            return OTHER;
        }
        return switch (corpCls.trim()) {
            case "Y" -> KOSPI;
            case "K" -> KOSDAQ;
            case "N" -> KONEX;
            default -> OTHER;
        };
    }
}
