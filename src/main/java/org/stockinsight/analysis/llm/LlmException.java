package org.stockinsight.analysis.llm;

/**
 * LLM 호출 실패(통신 오류, 인증키 없음, 출력 한도·거절, 스키마 오류). 메시지에 프롬프트 전문이나 인증키를 넣지 않는다.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
