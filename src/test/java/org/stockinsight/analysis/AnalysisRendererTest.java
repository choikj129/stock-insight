package org.stockinsight.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.stockinsight.TestcontainersConfiguration;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.financial.FinancialService;
import org.stockinsight.financial.RawAccountLine;
import org.stockinsight.signal.FinancialSignalJob;

/**
 * 게시본 무효화를 검증한다(D-40). 참조한 신호가 철회되면 재생성 전이라도 즉시 무효화되어야 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnalysisRendererTest {

    @Autowired
    AnalysisRenderer renderer;

    @Autowired
    FinancialExplainInputBuilder inputBuilder;

    @Autowired
    AnalysisService analysisService;

    @Autowired
    FinancialService financialService;

    @Autowired
    FinancialSignalJob signalJob;

    @Autowired
    CompanyService companyService;

    @Autowired
    JdbcClient jdbc;

    long companyId;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate analysis, company_signal, financial_report, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        companyId = companyService.upsertListed(
                new ListedCompany("00126380", "삼성전자", "삼성전자(주)", "005930", Market.KOSPI, "264", 12)).getId();
    }

    @Test
    void publishedAnalysisIsInvalidatedAssoonAsItsReferencedSignalIsWithdrawn() {
        seedQuarter("4,000,000,000", "2,000,000,000", "R1"); // +100%: FIN_REVENUE_CHANGE 활성
        signalJob.run();
        FinancialExplainInputBuilder.BuildResult built = inputBuilder.build(companyId).orElseThrow();

        NewAnalysis draft = new NewAnalysis(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN,
                built.fingerprint(), AnalysisStatus.DRAFT, null, built.input(), built.snapshot(), "fx-schema-1", "fx-v1",
                "claude-sonnet-5", FinancialExplainInputBuilder.INPUT_BUILDER_VERSION, "fin-1", 100, 50, 0,
                java.math.BigDecimal.ONE, null, 1);
        long id = analysisService.save(draft, Instant.now());
        analysisService.publish(id, TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN, Instant.now());
        Analysis published = analysisService.findCurrent(TargetType.COMPANY, String.valueOf(companyId), AnalysisKind.FINANCIAL_EXPLAIN)
                .orElseThrow();

        assertThat(renderer.isInvalidated(published, companyId)).isFalse();

        // 정정: 증가율이 문턱값 아래로 내려가 신호가 철회된다.
        seedQuarter("2,200,000,000", "2,000,000,000", "R2");
        signalJob.run();

        assertThat(renderer.isInvalidated(published, companyId)).isTrue();
    }

    private void seedQuarter(String current, String prior, String receiptNo) {
        List<RawAccountLine> rows = List.of(
                line("BS", "유동자산", "1", "2026.03.31 현재", "3,000,000,000", "2,500,000,000", receiptNo),
                line("BS", "자산총계", "5", "2026.03.31 현재", "9,000,000,000", "8,000,000,000", receiptNo),
                line("BS", "부채총계", "9", "2026.03.31 현재", "4,000,000,000", "3,500,000,000", receiptNo),
                line("BS", "자본총계", "13", "2026.03.31 현재", "5,000,000,000", "4,500,000,000", receiptNo),
                line("IS", "매출액", "23", "2026.01.01 ~ 2026.03.31", current, prior, receiptNo),
                line("IS", "영업이익", "27", "2026.01.01 ~ 2026.03.31", "500,000,000", "400,000,000", receiptNo));
        financialService.replace(companyId, 2026, "11013", rows, null);
    }

    private static RawAccountLine line(String statement, String accountName, String ord, String period,
            String current, String prior, String receiptNo) {
        return new RawAccountLine("CFS", statement, accountName, ord, period, current, null, prior, null, null,
                "KRW", receiptNo);
    }
}
