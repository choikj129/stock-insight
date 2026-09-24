package org.stockinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockInsightApplication.class, args);
    }

}
