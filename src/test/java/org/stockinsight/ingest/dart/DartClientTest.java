package org.stockinsight.ingest.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DartClientTest {

    private static final String BASE_URL = "https://opendart.fss.or.kr/api";
    private static final String API_KEY = "test-key-0000000000000000000000000000000";

    private MockRestServiceServer server;
    private DartClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DartClient(builder, properties(API_KEY));
    }

    @Test
    void readsCompanyOverview() {
        server.expect(requestTo(startsWith(BASE_URL + "/company.json")))
                .andExpect(queryParam("corp_code", "00126380"))
                .andExpect(queryParam("crtfc_key", API_KEY))
                .andRespond(withSuccess(fixture("company-00126380.json"), MediaType.APPLICATION_JSON));

        Optional<DartCompanyOverview> overview = client.fetchCompany("00126380");

        assertThat(overview).hasValueSatisfying(company -> {
            assertThat(company.stockName()).isEqualTo("삼성전자");
            assertThat(company.stockCode()).isEqualTo("005930");
            assertThat(company.corpCls()).isEqualTo("Y");
            assertThat(company.industryCode()).isEqualTo("264");
            assertThat(company.fiscalMonth()).isEqualTo("12");
        });
        server.verify();
    }

    @Test
    void returnsEmptyWhenNoData() {
        server.expect(requestTo(startsWith(BASE_URL + "/company.json")))
                .andRespond(withSuccess(fixture("company-error-013.json"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchCompany("99999999")).isEmpty();
    }

    @Test
    void unregisteredKeyStopsRun() {
        server.expect(requestTo(startsWith(BASE_URL + "/company.json")))
                .andRespond(withSuccess(fixture("company-error-010.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchCompany("00126380"))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(DartStatus.UNREGISTERED_KEY);
                    assertThat(e.stopsRun()).isTrue();
                    assertThat(e.getMessage()).doesNotContain(API_KEY);
                });
    }

    @Test
    void requestLimitExceededStopsRun() {
        server.expect(requestTo(startsWith(BASE_URL + "/company.json")))
                .andRespond(withSuccess(fixture("company-error-020.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchCompany("00126380"))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(DartStatus.REQUEST_LIMIT_EXCEEDED);
                    assertThat(e.stopsRun()).isTrue();
                });
    }

    @Test
    void readsCorpCodesFromZip() throws IOException {
        server.expect(requestTo(startsWith(BASE_URL + "/corpCode.xml")))
                .andRespond(withSuccess(zip("CORPCODE.xml", fixture("corpcode-sample.xml")), MediaType.APPLICATION_OCTET_STREAM));

        List<DartCorpCode> corpCodes = client.fetchCorpCodes();

        assertThat(corpCodes).extracting(DartCorpCode::corpCode)
                .containsExactly("00126380", "00434003", "01571107");
        assertThat(corpCodes).filteredOn(DartCorpCode::isListed).extracting(DartCorpCode::stockCode)
                .containsExactly("005930", "0010V0");
    }

    @Test
    void corpCodeErrorComesAsXmlInsteadOfZip() {
        // 실제 응답: 오류일 때 HTTP 200과 XML 상태 응답이 온다.
        server.expect(requestTo(startsWith(BASE_URL + "/corpCode.xml")))
                .andRespond(withSuccess(fixture("corpcode-error-010.xml"), MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> client.fetchCorpCodes())
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.status()).isEqualTo(DartStatus.UNREGISTERED_KEY));
    }

    @Test
    void readsOneDayOfDisclosuresIncludingAmendedOriginals() {
        server.expect(requestTo(startsWith(BASE_URL + "/list.json")))
                .andExpect(queryParam("bgn_de", "20260814"))
                .andExpect(queryParam("end_de", "20260814"))
                .andExpect(queryParam("pblntf_ty", "B"))
                .andExpect(queryParam("last_reprt_at", "N"))
                .andExpect(queryParam("page_no", "1"))
                .andExpect(queryParam("page_count", "100"))
                .andExpect(queryParam("crtfc_key", API_KEY))
                .andRespond(withSuccess(fixture("list-20260814-B.json"), MediaType.APPLICATION_JSON));

        DartDisclosurePage page = client.fetchDisclosures(LocalDate.of(2026, 8, 14), "B", 1);

        assertThat(page.hasNextPage()).isFalse();
        assertThat(page.list()).first().satisfies(item -> {
            assertThat(item.corpCode()).isEqualTo("00101220");
            assertThat(item.corpCls()).isEqualTo("Y");
            assertThat(item.receiptNo()).isEqualTo("20260814003888");
            assertThat(item.receivedDate()).isEqualTo("20260814");
            assertThat(item.reportName()).isEqualTo("주요사항보고서(자기주식처분결정)");
            assertThat(item.remark()).isEmpty();
        });
        assertThat(page.list()).extracting(DartDisclosure::reportName)
                .contains("[기재정정]주요사항보고서(유상증자결정)");
        // 비상장 기타법인(E)도 섞여 온다. 저장 대상은 수집기가 ACTIVE 기업으로 고른다.
        assertThat(page.list()).filteredOn(item -> item.corpCls().equals("E"))
                .allSatisfy(item -> assertThat(item.stockCode()).isEmpty());
        server.verify();
    }

    @Test
    void tellsWhenMorePagesRemain() {
        server.expect(requestTo(startsWith(BASE_URL + "/list.json")))
                .andRespond(withSuccess(fixture("list-20260814-I-page1.json"), MediaType.APPLICATION_JSON));

        DartDisclosurePage page = client.fetchDisclosures(LocalDate.of(2026, 8, 14), "I", 1);

        assertThat(page.totalPage()).isEqualTo(3);
        assertThat(page.hasNextPage()).isTrue();
        // 원본 보고서명에는 끝 공백과 연속 공백이 있다. 정리는 저장할 때 한다.
        assertThat(page.list()).extracting(DartDisclosure::reportName).anyMatch(name -> name.endsWith("  "));
    }

    @Test
    void dayWithoutDisclosuresIsEmptyPage() {
        // 실제 응답: 주말과 마지막 페이지를 넘긴 요청 모두 013이다.
        server.expect(requestTo(startsWith(BASE_URL + "/list.json")))
                .andRespond(withSuccess(fixture("list-error-013.json"), MediaType.APPLICATION_JSON));

        DartDisclosurePage page = client.fetchDisclosures(LocalDate.of(2026, 9, 19), "A", 1);

        assertThat(page.list()).isEmpty();
        assertThat(page.totalCount()).isZero();
        assertThat(page.hasNextPage()).isFalse();
    }

    @Test
    void invalidSearchConditionIsItemErrorWithoutLeakingKey() {
        // 실제 응답: 기업을 지정하지 않고 3개월 넘게 조회하면 100이다.
        server.expect(requestTo(startsWith(BASE_URL + "/list.json")))
                .andRespond(withSuccess(fixture("list-error-100.json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchDisclosures(LocalDate.of(2026, 8, 14), "A", 1))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(DartStatus.INVALID_FIELD);
                    assertThat(e.stopsRun()).isFalse();
                    assertThat(e.getMessage()).contains("list.json 20260814 A p1").doesNotContain(API_KEY);
                });
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        server.expect(times(2), requestTo(startsWith(BASE_URL + "/company.json"))).andRespond(withServerError());
        server.expect(once(), requestTo(startsWith(BASE_URL + "/company.json")))
                .andRespond(withSuccess(fixture("company-00126380.json"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchCompany("00126380")).isPresent();
        server.verify();
    }

    @Test
    void transportFailureAfterRetriesStopsRunWithoutLeakingKey() {
        server.expect(times(3), requestTo(startsWith(BASE_URL + "/company.json")))
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> client.fetchCompany("00126380"))
                .isInstanceOfSatisfying(DartApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(DartStatus.TRANSPORT_ERROR);
                    assertThat(e.stopsRun()).isTrue();
                    assertThat(e.getMessage()).doesNotContain(API_KEY).doesNotContain("crtfc_key");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void refusesToCallWithoutApiKey() {
        DartClient withoutKey = new DartClient(RestClient.builder(), properties(""));

        assertThatThrownBy(() -> withoutKey.fetchCompany("00126380"))
                .isInstanceOfSatisfying(DartApiException.class,
                        e -> assertThat(e.status()).isEqualTo(DartStatus.MISSING_KEY));
    }

    @Test
    void propertiesToStringMasksApiKey() {
        assertThat(properties(API_KEY).toString()).doesNotContain(API_KEY);
    }

    @Test
    void pacerKeepsMinimumInterval() {
        DartClient.RequestPacer pacer = new DartClient.RequestPacer(Duration.ofMillis(50));
        long start = System.nanoTime();

        pacer.await();
        pacer.await();
        pacer.await();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    static DartProperties properties(String apiKey) {
        return new DartProperties(apiKey, BASE_URL, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1),
                3, Duration.ZERO);
    }

    static byte[] fixture(String name) {
        try (InputStream in = DartClientTest.class.getResourceAsStream("/dart/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("fixture not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
