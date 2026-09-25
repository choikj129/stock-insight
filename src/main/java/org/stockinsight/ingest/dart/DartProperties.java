package org.stockinsight.ingest.dart;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenDART 설정. 인증키는 DART_API_KEY로만 주입한다. 저장소 밖 비밀값 파일이나 환경 변수에서 온다 (docs/decisions.md D-30).
 *
 * @param minInterval 요청 사이 최소 간격. 짧은 시간 과다 호출은 이용 제한 사유다
 * @param maxAttempts 통신 오류·5xx 응답 시 최대 시도 횟수
 */
@ConfigurationProperties("app.dart")
public record DartProperties(
        String apiKey,
        String baseUrl,
        Duration minInterval,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration retryBackoff) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "DartProperties[apiKey=" + (hasApiKey() ? "****" : "<none>") + ", baseUrl=" + baseUrl
                + ", minInterval=" + minInterval + ", maxAttempts=" + maxAttempts + "]";
    }
}
