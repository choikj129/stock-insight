package org.stockinsight.signal;

/** 신호의 방향. 실적·재무 건전성에 대한 영향 기준이며 주가 영향이 아니다 (ai-analysis.md §3.1). */
public enum SignalDirection {
    POSITIVE, NEGATIVE, UNCERTAIN
}
