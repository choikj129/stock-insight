package org.stockinsight.analysis.llm;

/**
 * 분석이 쓰는 LLM 기능. 시스템 프롬프트·사용자 입력·출력 JSON 스키마를 주면 JSON 문자열과 사용량을 돌려준다.
 * 실패(호출 오류, 인증키 없음, 출력 한도·거절)는 모두 {@link LlmException}이다. 테스트는 이 인터페이스를 가짜로 바꾼다.
 */
public interface LlmClient {

    LlmResult generate(String systemPrompt, String userInput, String jsonSchema);
}
