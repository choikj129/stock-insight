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
