package org.stockinsight.signal;

/** 신호 상태 (D-36). 행은 지우지 않고 상태만 바뀐다. */
public enum SignalStatus {
    /** 지금도 성립한다. */
    ACTIVE,
    /** 그 기간에는 성립했고 지금은 최신이 아니다. */
    PAST,
    /** 다시 계산했더니 같은 자연키로 성립하지 않는다(정정·규칙 변경). */
    WITHDRAWN
}
