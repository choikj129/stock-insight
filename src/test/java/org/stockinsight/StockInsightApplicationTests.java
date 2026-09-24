package org.stockinsight;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockInsightApplicationTests {

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    Flyway flyway;

    @Test
    void connectsToPostgres() {
        String version = jdbcClient.sql("SHOW server_version").query(String.class).single();

        assertThat(version).startsWith("17");
    }

    @Test
    void appliesAllMigrations() {
        assertThat(flyway.info().pending()).isEmpty();
    }

}
