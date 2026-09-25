package org.stockinsight.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 골든셋 입력 JSON을 로컬 DB에서 만들어 저장소에 고정한다(ai-analysis.md §4.4.8, §8). AI를 호출하지 않는다(무료·안전).
 * {@code --spring.profiles.active=local,goldenset} 로 기동할 때만 동작한다. 대상은
 * {@code app.analysis.financial-explain.golden-set-company-ids}에 설정된 기업 ID뿐이다(ai_covered로 임의로
 * 넓히지 않는다). 산출물은 {@code src/test/resources/golden/financial_explain/{companyId}.json}에 쓴다.
 */
@Component
@Profile("goldenset")
class GoldenSetDumpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldenSetDumpRunner.class);
    private static final Path OUTPUT_DIR = Path.of("src/test/resources/golden/financial_explain");

    private final FinancialExplainInputBuilder inputBuilder;
    private final FinancialExplainProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    GoldenSetDumpRunner(FinancialExplainInputBuilder inputBuilder, FinancialExplainProperties properties) {
        this.inputBuilder = inputBuilder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<Long> ids = properties.goldenSetCompanyIds();
        if (ids.isEmpty()) {
            log.warn("골든셋 덤프: app.analysis.financial-explain.golden-set-company-ids가 비어 있습니다. 아무것도 만들지 않습니다.");
            return;
        }
        Files.createDirectories(OUTPUT_DIR);
        int written = 0;
        int skipped = 0;
        for (Long companyId : ids) {
            var built = inputBuilder.build(companyId);
            if (built.isEmpty()) {
                log.warn("골든셋 덤프: 기업 {}는 재무 보고서가 없어 건너뜁니다", companyId);
                skipped++;
                continue;
            }
            Path file = OUTPUT_DIR.resolve(companyId + ".json");
            Files.writeString(file, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(built.get().input()));
            written++;
        }
        log.info("골든셋 덤프 완료: 기록 {}, 건너뜀 {}, 위치 {}", written, skipped, OUTPUT_DIR.toAbsolutePath());
    }
}
