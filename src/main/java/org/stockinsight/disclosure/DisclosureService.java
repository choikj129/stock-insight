package org.stockinsight.disclosure;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공시의 저장 규칙을 담당한다. 수집기는 이 서비스로만 공시 데이터를 바꾼다. */
@Service
@Transactional
public class DisclosureService {

    private final DisclosureRepository disclosures;
    private final Clock clock;

    DisclosureService(DisclosureRepository disclosures, Clock clock) {
        this.disclosures = disclosures;
        this.clock = clock;
    }

    /**
     * 공시를 공시번호 기준으로 저장한다. 이미 있는 공시는 새로 읽은 값(비고 등)으로 갱신하고 행을 늘리지 않는다.
     * 저장한 기업들의 정정 공시는 원 공시와 다시 연결한다 (원 공시가 나중에 들어와도 연결되게 하기 위함).
     */
    public SaveResult saveAll(List<NewDisclosure> newDisclosures) {
        Instant now = clock.instant();
        int inserted = 0;
        int updated = 0;
        for (NewDisclosure disclosure : newDisclosures) {
            switch (disclosures.upsert(disclosure, ReportName.parse(disclosure.reportName()), now)) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case UNCHANGED -> {
                }
            }
        }
        Set<Long> companyIds = newDisclosures.stream().map(NewDisclosure::companyId).collect(Collectors.toSet());
        int linked = disclosures.relinkAmendments(companyIds, now);
        return new SaveResult(inserted, updated, linked);
    }

    @Transactional(readOnly = true)
    public Optional<Disclosure> findByReceiptNo(String receiptNo) {
        return disclosures.findByReceiptNo(receiptNo);
    }

    /** 재무 수집이 다시 받을 대상을 정하는 계기(정기공시). 기업·기본 보고서명별 가장 큰 공시번호다. */
    @Transactional(readOnly = true)
    public List<PeriodicTrigger> latestPeriodicTriggers() {
        return disclosures.latestPeriodicByCompanyAndBaseName();
    }

    /** 수집한 공시 한 건. */
    public record NewDisclosure(
            String receiptNo,
            long companyId,
            DisclosureType type,
            String reportName,
            LocalDate receivedOn,
            String filerName,
            String remark) {

        private static final Pattern RECEIPT_NO = Pattern.compile("\\d{14}");

        public NewDisclosure {
            Objects.requireNonNull(receiptNo, "receiptNo");
            if (!RECEIPT_NO.matcher(receiptNo).matches()) {
                throw new IllegalArgumentException("공시번호 형식이 아닙니다: " + receiptNo);
            }
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(reportName, "reportName");
            Objects.requireNonNull(receivedOn, "receivedOn");
            filerName = filerName == null || filerName.isBlank() ? null : filerName.strip();
            remark = remark == null ? "" : remark.strip();
        }
    }

    /**
     * @param linked 원 공시 연결이 새로 생기거나 바뀐 정정 공시 수
     */
    public record SaveResult(int inserted, int updated, int linked) {
    }
}
