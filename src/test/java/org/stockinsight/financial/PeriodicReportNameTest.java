package org.stockinsight.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.stockinsight.financial.PeriodicReportName.QueryKey;

class PeriodicReportNameTest {

    @Test
    void annualReportResolvesToFyRegardlessOfFiscalMonth() {
        QueryKey key = PeriodicReportName.resolve("사업보고서 (2025.12)", 12).orElseThrow();

        assertThat(key.bsnsYear()).isEqualTo(2025);
        assertThat(key.periodType()).isEqualTo(PeriodType.FY);
        assertThat(key.periodEndMonth()).isEqualTo(YearMonth.of(2025, 12));
    }

    @Test
    void halfYearReportResolvesToH1() {
        QueryKey key = PeriodicReportName.resolve("반기보고서 (2026.06)", 12).orElseThrow();

        assertThat(key.bsnsYear()).isEqualTo(2026);
        assertThat(key.periodType()).isEqualTo(PeriodType.H1);
    }

    @Test
    void quarterlyReportForDecemberFiscalYearEnd() {
        assertThat(PeriodicReportName.resolve("분기보고서 (2026.03)", 12).orElseThrow().periodType())
                .isEqualTo(PeriodType.Q1);
        assertThat(PeriodicReportName.resolve("분기보고서 (2026.09)", 12).orElseThrow().periodType())
                .isEqualTo(PeriodType.Q3);
    }

    @Test
    void quarterlyReportForJuneFiscalYearEnd() {
        // 실제 사례: 6월 결산 양지사의 3분기보고서는 bsns_year=2026, 기간 2025.07~2026.03 (docs/implementation-plan.md §4.1)
        assertThat(PeriodicReportName.resolve("분기보고서 (2026.09)", 6).orElseThrow().periodType())
                .isEqualTo(PeriodType.Q1);
        QueryKey q3 = PeriodicReportName.resolve("분기보고서 (2026.03)", 6).orElseThrow();
        assertThat(q3.periodType()).isEqualTo(PeriodType.Q3);
        assertThat(q3.bsnsYear()).isEqualTo(2026);
    }

    @Test
    void quarterlyReportWithoutFiscalMonthIsAnError() {
        assertThatThrownBy(() -> PeriodicReportName.resolve("분기보고서 (2026.03)", null))
                .isInstanceOf(PeriodicReportNameException.class);
    }

    @Test
    void quarterlyReportNotMatchingEitherQuarterIsAnError() {
        assertThatThrownBy(() -> PeriodicReportName.resolve("분기보고서 (2026.05)", 12))
                .isInstanceOf(PeriodicReportNameException.class);
    }

    @Test
    void otherPeriodicReportsAreNotResolved() {
        assertThat(PeriodicReportName.resolve("등록법인결산서류(자본시장법 제5조 제1항 제3호)", 12)).isEqualTo(Optional.empty());
    }

    @Test
    void amendmentLabelsAreAlreadyStrippedByReportName() {
        // ReportName.parse가 정정 표시를 뗀 base_report_name을 준다. 여기서는 이미 뗀 이름만 받는다.
        assertThat(PeriodicReportName.resolve("사업보고서 (2025.12)", 12)).isPresent();
    }
}
