package org.stockinsight.ingest.dart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenDART HTTP 클라이언트.
 * <ul>
 *     <li>요청 사이에 최소 간격을 둔다.</li>
 *     <li>통신 오류와 5xx 응답만 재시도한다. OpenDART 상태 코드 오류는 재시도하지 않는다.</li>
 *     <li>예외 메시지와 로그에 요청 URL을 남기지 않는다 (URL에 인증키가 들어 있다).</li>
 * </ul>
 */
public class DartClient implements DartApi {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);

    /** 공시검색의 페이지당 최대 건수. */
    static final int DISCLOSURE_PAGE_SIZE = 100;

    /** 다중회사 주요계정 한 호출의 최대 기업 수. */
    static final int MAX_KEY_ACCOUNT_CORP_CODES = 100;

    private final RestClient restClient;
    private final DartProperties properties;
    private final RequestPacer pacer;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final CorpCodeXmlParser xmlParser = new CorpCodeXmlParser();

    public DartClient(RestClient.Builder restClientBuilder, DartProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.pacer = new RequestPacer(properties.minInterval());
    }

    @Override
    public List<DartCorpCode> fetchCorpCodes() {
        byte[] body = get("/corpCode.xml", Map.of());
        if (!isZip(body)) {
            // 오류일 때는 zip 대신 HTTP 200과 XML 상태 응답이 온다.
            CorpCodeXmlParser.StatusMessage status = xmlParser.parseStatus(new ByteArrayInputStream(body));
            throw toException(status.status(), status.message(), "corpCode.xml");
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().toLowerCase().endsWith(".xml")) {
                    return xmlParser.parseCorpCodes(zip);
                }
            }
        } catch (IOException e) {
            throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "고유번호 파일 압축을 풀지 못했습니다: " + e.getMessage());
        }
        throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "고유번호 파일 압축 안에 XML이 없습니다");
    }

    @Override
    public Optional<DartCompanyOverview> fetchCompany(String corpCode) {
        byte[] body = get("/company.json", Map.of("corp_code", corpCode));
        DartCompanyOverview overview;
        try {
            overview = jsonMapper.readValue(body, DartCompanyOverview.class);
        } catch (JacksonException e) {
            throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "기업개황 응답을 읽지 못했습니다: " + corpCode);
        }
        DartStatus status = DartStatus.fromCode(overview.status());
        if (status == DartStatus.SUCCESS) {
            return Optional.of(overview);
        }
        if (status == DartStatus.NO_DATA) {
            return Optional.empty();
        }
        throw toException(overview.status(), overview.message(), "company.json " + corpCode);
    }

    @Override
    public DartDisclosurePage fetchDisclosures(LocalDate receivedOn, String disclosureType, int pageNo) {
        String day = receivedOn.format(DateTimeFormatter.BASIC_ISO_DATE);
        String request = "list.json " + day + " " + disclosureType + " p" + pageNo;
        byte[] body = get("/list.json", Map.of(
                "bgn_de", day,
                "end_de", day,
                "pblntf_ty", disclosureType,
                "last_reprt_at", "N",
                "page_no", String.valueOf(pageNo),
                "page_count", String.valueOf(DISCLOSURE_PAGE_SIZE)));
        DartDisclosurePage page;
        try {
            page = jsonMapper.readValue(body, DartDisclosurePage.class);
        } catch (JacksonException e) {
            throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "공시 목록 응답을 읽지 못했습니다: " + request);
        }
        DartStatus status = DartStatus.fromCode(page.status());
        if (status == DartStatus.SUCCESS) {
            return page;
        }
        if (status == DartStatus.NO_DATA) {
            // 공시가 없는 날(주말 등)과 마지막 페이지를 넘긴 요청 모두 013이다.
            return DartDisclosurePage.empty(pageNo);
        }
        throw toException(page.status(), page.message(), request);
    }

    @Override
    public List<DartKeyAccount> fetchKeyAccounts(List<String> corpCodes, int bsnsYear, String reportCode) {
        if (corpCodes.isEmpty() || corpCodes.size() > MAX_KEY_ACCOUNT_CORP_CODES) {
            throw new IllegalArgumentException("corpCodes는 1~%d개여야 합니다: %d개".formatted(MAX_KEY_ACCOUNT_CORP_CODES, corpCodes.size()));
        }
        String request = "fnlttMultiAcnt.json " + bsnsYear + " " + reportCode + " " + corpCodes.size() + "개사";
        byte[] body = get("/fnlttMultiAcnt.json", Map.of(
                "corp_code", String.join(",", corpCodes),
                "bsns_year", String.valueOf(bsnsYear),
                "reprt_code", reportCode));
        DartKeyAccountResponse response;
        try {
            response = jsonMapper.readValue(body, DartKeyAccountResponse.class);
        } catch (JacksonException e) {
            throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "주요계정 응답을 읽지 못했습니다: " + request);
        }
        DartStatus status = DartStatus.fromCode(response.status());
        if (status == DartStatus.SUCCESS) {
            return response.list();
        }
        if (status == DartStatus.NO_DATA) {
            return List.of();
        }
        throw toException(response.status(), response.message(), request);
    }

    private byte[] get(String path, Map<String, String> params) {
        if (!properties.hasApiKey()) {
            throw new DartApiException(DartStatus.MISSING_KEY, "OpenDART 인증키(DART_API_KEY)가 설정되지 않았습니다");
        }
        int maxAttempts = Math.max(1, properties.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            pacer.await();
            try {
                byte[] body = restClient.get()
                        .uri(builder -> {
                            builder.path(path).queryParam("crtfc_key", properties.apiKey());
                            params.forEach(builder::queryParam);
                            return builder.build();
                        })
                        .retrieve()
                        .body(byte[].class);
                if (body == null || body.length == 0) {
                    throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE, "빈 응답: " + path);
                }
                return body;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                String reason = describe(e);
                if (attempt >= maxAttempts) {
                    throw new DartApiException(DartStatus.TRANSPORT_ERROR,
                            "OpenDART 통신 실패 (" + attempt + "회 시도): " + path + ", " + reason);
                }
                log.warn("OpenDART 요청 재시도 {}/{}: {}, {}", attempt, maxAttempts, path, reason);
                sleep(properties.retryBackoff().multipliedBy(attempt));
            } catch (RestClientResponseException e) {
                throw new DartApiException(DartStatus.UNEXPECTED_RESPONSE,
                        "OpenDART HTTP 오류 " + e.getStatusCode().value() + ": " + path);
            }
        }
    }

    private static DartApiException toException(String code, String message, String request) {
        DartStatus status = DartStatus.fromCode(code);
        return new DartApiException(status, "OpenDART " + code + " " + message + " (" + request + ")");
    }

    /** 원래 예외 메시지에는 인증키가 든 URL이 포함되므로 쓰지 않는다. */
    private static String describe(RuntimeException e) {
        if (e instanceof HttpServerErrorException serverError) {
            return "HTTP " + serverError.getStatusCode().value();
        }
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName();
    }

    private static boolean isZip(byte[] body) {
        return body.length >= 2 && body[0] == 'P' && body[1] == 'K';
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DartApiException(DartStatus.TRANSPORT_ERROR, "OpenDART 요청 대기 중 인터럽트");
        }
    }

    /** 요청 사이의 최소 간격을 지킨다. */
    static final class RequestPacer {

        private final long intervalNanos;
        private long nextAllowedAt = System.nanoTime();

        RequestPacer(Duration interval) {
            this.intervalNanos = interval == null ? 0 : interval.toNanos();
        }

        synchronized void await() {
            long now = System.nanoTime();
            long waitNanos = nextAllowedAt - now;
            if (waitNanos > 0) {
                sleep(Duration.ofNanos(waitNanos));
                now = System.nanoTime();
            }
            nextAllowedAt = now + intervalNanos;
        }
    }
}
