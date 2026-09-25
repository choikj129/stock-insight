package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 저장된 분석 결과 한 벌(architecture.md §4.2). */
public record Analysis(
        long id,
        TargetType targetType,
        String targetKey,
        AnalysisKind analysisKind,
        String fingerprint,
        AnalysisStatus status,
        FinancialExplainOutput resultJson,
        FinancialExplainInput inputJson,
        ValueSnapshot valueSnapshot,
        String schemaVersion,
        String promptVersion,
        String model,
        String inputBuilderVersion,
        String ruleVersion,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheReadTokens,
        BigDecimal costUsd,
        List<String> failureReasons,
        int attemptCount,
        Instant createdAt,
        Instant publishedAt,
        boolean isCurrent) {
}
