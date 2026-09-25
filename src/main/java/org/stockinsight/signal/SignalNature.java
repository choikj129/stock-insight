package org.stockinsight.signal;

/** 신호의 성격 (ai-analysis.md §3.1). */
public enum SignalNature {
    /** 지금 어떤 상태인가. */
    STATE,
    /** 최근 무엇이 바뀌었나. */
    CHANGE
}
