package org.stockinsight.financial;

import java.math.BigDecimal;

/** 계정 하나의 당기·전기(또는 전기말) 값. 계정이 없으면 둘 다 null이다. */
public record MetricValue(BigDecimal current, BigDecimal prior) {

    public static final MetricValue EMPTY = new MetricValue(null, null);

    public boolean hasCurrent() {
        return current != null;
    }

    public boolean hasPrior() {
        return prior != null;
    }
}
