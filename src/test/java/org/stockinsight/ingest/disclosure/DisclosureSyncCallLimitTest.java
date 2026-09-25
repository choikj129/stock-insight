package org.stockinsight.ingest.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.stockinsight.ingest.disclosure.DisclosureSyncJobTest.D2;

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
import org.stockinsight.disclosure.DisclosureService;
import org.stockinsight.ingest.checkpoint.IngestCheckpointRepository;
import org.stockinsight.ingest.disclosure.DisclosureSyncJobTest.FakeDartApi;
import org.stockinsight.ingest.disclosure.DisclosureSyncJobTest.MutableClock;

/**
 * 호출 한도(한 실행 6건)에서 초기 적재를 여러 실행에 나눠 받는지 검증한다. 데이터는 {@link DisclosureSyncJobTest}와 같다.
 */
@SpringBootTest(properties = {
        "app.ingest.disclosure-sync.max-calls-per-run=6",
        "app.ingest.disclosure-sync.initial-load-period=2d"
})
@Import({TestcontainersConfiguration.class, DisclosureSyncJobTest.FakeDartConfig.class})
class DisclosureSyncCallLimitTest {

    @Autowired
    DisclosureSyncJob job;

    @Autowired
    DisclosureService disclosureService;

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
        DisclosureSyncJobTest.seed(jdbc, clock, dart, companyService);
    }

    @Test
    void resumesAcrossRunsAndLinksOriginalArrivingLater() {
        // 1회: 당일 3 + 어제 3 = 6호출. 그제는 남는다.
        assertThat(job.run().status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(disclosureService.findByReceiptNo("20260923000300").orElseThrow().originalReceiptNo()).isNull();

        // 2회: 당일 3 + 그제 A, B + 그제 I 1페이지에서 한도. 끝까지 못 읽은 그제 I는 기록하지 않는다.
        DisclosureSyncJob.Result second = job.run();
        assertThat(second.status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(second.summary()).contains("남은 대상 1");
        assertThat(checkpoints.findAllBySource(DisclosureSyncJob.CHECKPOINT_SOURCE)).doesNotContainKey(D2 + ":I");
        // 원 공시가 정정 공시보다 늦게 들어와도 연결된다.
        assertThat(disclosureService.findByReceiptNo("20260923000300").orElseThrow().originalReceiptNo())
                .isEqualTo("20260922000200");

        // 3회: 당일 3 + 그제 I 3페이지.
        assertThat(job.run().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from disclosure").query(Long.class).single()).isEqualTo(8);
        assertThat(runStatuses()).containsExactly("PARTIAL", "PARTIAL", "SUCCEEDED");
    }

    private List<String> runStatuses() {
        return jdbc.sql("select status from pipeline_run order by id").query(String.class).list();
    }
}
