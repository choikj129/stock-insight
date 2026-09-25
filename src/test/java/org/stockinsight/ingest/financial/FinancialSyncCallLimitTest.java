package org.stockinsight.ingest.financial;

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
import org.stockinsight.common.pipeline.PipelineRunRecorder.RunStatus;
import org.stockinsight.company.CompanyService;
import org.stockinsight.company.CompanyService.ListedCompany;
import org.stockinsight.company.Market;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.financial.FinancialSyncJobTest.FakeDartApi;
import org.stockinsight.ingest.financial.FinancialSyncJobTest.FakeDartConfig;
import org.stockinsight.ingest.financial.FinancialSyncJobTest.MutableClock;

/**
 * 100개를 넘는 기업이 있을 때 묶음이 최대 100개씩 나뉘고, 호출 한도에서 다음 실행이 이어받는지 검증한다.
 */
@SpringBootTest(properties = {
        "app.ingest.financial-sync.max-calls-per-run=1",
        "app.ingest.financial-sync.initial-load-years=0"
})
@Import({TestcontainersConfiguration.class, FakeDartConfig.class})
class FinancialSyncCallLimitTest {

    private static final int COMPANY_COUNT = 150;

    @Autowired
    FinancialSyncJob job;

    @Autowired
    CompanyService companyService;

    @Autowired
    IngestCheckpointRepository checkpoints;

    @Autowired
    FakeDartApi dart;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate financial_report, disclosure, company_alias, security, company, ingest_checkpoint, pipeline_run restart identity cascade")
                .update();
        dart.reset();
        clock.set(Instant.parse("2026-09-24T01:00:00Z"));
        for (int i = 0; i < COMPANY_COUNT; i++) {
            String corpCode = "1%07d".formatted(i);
            companyService.upsertListed(new ListedCompany(corpCode, "회사" + i, "회사" + i + "(주)",
                    "%06d".formatted(i), Market.KOSPI, "264", 12));
        }
    }

    @Test
    void batchesAreCappedAtOneHundredCompanies() {
        FinancialSyncJob.Result result = job.run();

        assertThat(result.status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(dart.calls).hasSize(1);
        List<String> corpCodes = List.of(dart.calls.get(0).split(":")[2].split(","));
        assertThat(corpCodes).hasSize(100);
    }

    @Test
    void laterRunsResumeUntilEveryCompanyAndReportCodeIsCovered() {
        int guard = 0;
        RunStatus status;
        do {
            status = job.run().status();
            guard++;
        } while (status == RunStatus.PARTIAL && guard < 20);

        assertThat(status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(checkpoints.findAllBySource(FinancialSyncJob.CHECKPOINT_SOURCE)).hasSize(COMPANY_COUNT * 4);
    }
}
