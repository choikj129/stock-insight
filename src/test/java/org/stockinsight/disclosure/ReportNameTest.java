package org.stockinsight.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 보고서명은 모두 실제 OpenDART 공시검색 응답에서 가져왔다 (2026-08-14, 2026-09-23). */
class ReportNameTest {

    @Test
    void originalHasNoLabel() {
        ReportName name = ReportName.parse("반기보고서 (2026.06)");

        assertThat(name.isAmendment()).isFalse();
        assertThat(name.name()).isEqualTo("반기보고서 (2026.06)");
        assertThat(name.baseName()).isEqualTo("반기보고서 (2026.06)");
    }

    @Test
    void amendmentLabelIsSeparatedFromBaseName() {
        ReportName name = ReportName.parse("[기재정정]주요사항보고서(유상증자결정)");

        assertThat(name.isAmendment()).isTrue();
        assertThat(name.amendmentLabel()).isEqualTo("기재정정");
        assertThat(name.baseName()).isEqualTo("주요사항보고서(유상증자결정)");
        assertThat(name.name()).isEqualTo("[기재정정]주요사항보고서(유상증자결정)");
    }

    @Test
    void attachmentAmendmentsLinkToSameBaseNameAsOriginal() {
        assertThat(ReportName.parse("[첨부정정]사업보고서 (2025.12)").baseName())
                .isEqualTo(ReportName.parse("사업보고서 (2025.12)").baseName());
        assertThat(ReportName.parse("[첨부추가]반기보고서 (2026.06)").amendmentLabel()).isEqualTo("첨부추가");
    }

    @Test
    void trailingAndRepeatedSpacesAreNormalized() {
        ReportName name = ReportName.parse("[기재정정]현금ㆍ현물배당결정              ");
        assertThat(name.name()).isEqualTo("[기재정정]현금ㆍ현물배당결정");
        assertThat(name.baseName()).isEqualTo("현금ㆍ현물배당결정");

        assertThat(ReportName.parse("주권매매거래정지              (상장폐지 사유발생)").name())
                .isEqualTo("주권매매거래정지 (상장폐지 사유발생)");
    }

    @Test
    void extraInfoAfterGapIsKeptInNameButNotInBaseName() {
        // 실제 사례: 원 공시에는 기타정보가 있고 정정 제출에는 없다.
        ReportName original = ReportName.parse("주주총회소집결의              (임시주주총회)");
        ReportName amendment = ReportName.parse("[기재정정]주주총회소집결의");

        assertThat(original.name()).isEqualTo("주주총회소집결의 (임시주주총회)");
        assertThat(original.baseName()).isEqualTo("주주총회소집결의");
        assertThat(amendment.baseName()).isEqualTo(original.baseName());

        assertThat(ReportName.parse("[기재정정]증권발행결과(자율공시)              (제3자배정유상증자)").baseName())
                .isEqualTo(ReportName.parse("증권발행결과(자율공시)").baseName());
    }

    @Test
    void periodWithSingleSpaceStaysInBaseName() {
        // 정기보고서의 기간은 기타정보가 아니라 보고서명의 일부다. 기간이 다르면 다른 보고서다.
        assertThat(ReportName.parse("반기보고서 (2026.06)").baseName())
                .isNotEqualTo(ReportName.parse("반기보고서 (2025.06)").baseName());
    }

    @Test
    void bracketsInsideNameAreNotLabels() {
        ReportName name = ReportName.parse("기타시장안내 (상장적격성 실질심사 사유 추가 관련 안내)");

        assertThat(name.isAmendment()).isFalse();
    }

    @Test
    void labelOnlyNameIsKeptAsOriginal() {
        assertThat(ReportName.parse("[기재정정]").isAmendment()).isFalse();
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> ReportName.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
