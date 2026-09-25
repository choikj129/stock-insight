package org.stockinsight.analysis;

import java.math.BigDecimal;
import java.util.List;

/** 저장할 분석 결과 한 벌(대리키·생성 시각 제외). */
public record NewAnalysis(
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
        int attemptCount) {
}
