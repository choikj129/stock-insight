package org.stockinsight.analysis.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** 테스트용 {@link LlmClient}. 준비해 둔 응답(또는 예외)을 호출 순서대로 돌려주고 호출 내용을 기록한다. */
public class FakeLlmClient implements LlmClient {

    private final Deque<Supplier<LlmResult>> queue = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public FakeLlmClient thenReturn(LlmResult result) {
        queue.add(() -> result);
        return this;
    }

    public FakeLlmClient thenThrow(RuntimeException e) {
        queue.add(() -> {
            throw e;
        });
        return this;
    }

    @Override
    public LlmResult generate(String systemPrompt, String userInput, String jsonSchema) {
        calls.add(new Call(systemPrompt, userInput, jsonSchema));
        Supplier<LlmResult> next = queue.pollFirst();
        if (next == null) {
            throw new IllegalStateException("FakeLlmClient: 준비된 응답이 없습니다 (호출 " + calls.size() + "번째)");
        }
        return next.get();
    }

    public void reset() {
        queue.clear();
        calls.clear();
    }

    public int callCount() {
        return calls.size();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public record Call(String systemPrompt, String userInput, String jsonSchema) {
    }
}
