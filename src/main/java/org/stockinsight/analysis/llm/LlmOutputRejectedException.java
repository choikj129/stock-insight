package org.stockinsight.analysis.llm;

/**
 * 호출은 성공했지만 응답을 쓸 수 없는 경우(출력 한도에서 잘림, 모델 거절). ai-analysis.md §7.3에 따라
 * 검증 실패와 같게 다룬다(1회 재시도 대상).
 */
public class LlmOutputRejectedException extends LlmException {

    private final LlmResult partialUsage;

    public LlmOutputRejectedException(String message, LlmResult partialUsage) {
        super(message);
        this.partialUsage = partialUsage;
    }

    /** 응답을 쓸 수 없어도 과금은 되었으므로, 토큰·비용 기록용으로 남긴다({@link LlmResult#outputJson()}은 null). */
    public LlmResult partialUsage() {
        return partialUsage;
    }
}
