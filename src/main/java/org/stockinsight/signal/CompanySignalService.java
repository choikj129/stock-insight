package org.stockinsight.signal;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기업 신호의 저장 규칙을 담당한다. 계산기는 이 서비스로만 신호를 바꾼다 (D-36). */
@Service
@Transactional
public class CompanySignalService {

    private final CompanySignalRepository repository;
    private final Clock clock;

    CompanySignalService(CompanySignalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 계산한 재무 신호 초안을 자연키로 반영한다. 이번 초안에 없는 그 기업의 기존 신호(철회되지 않은 것)는 철회한다.
     */
    public ApplyResult applyFinancial(long companyId, List<SignalDraft> drafts, String ruleVersion) {
        Instant now = clock.instant();
        Set<String> existingKeys = new HashSet<>(repository.activeOrPastKeys(companyId));
        Set<String> seenKeys = new HashSet<>();

        for (SignalDraft draft : drafts) {
            SignalStatus status = draft.active() ? SignalStatus.ACTIVE : SignalStatus.PAST;
            repository.upsert(companyId, draft, status, ruleVersion, now);
            seenKeys.add(draft.type().name() + ":" + draft.basisKey());
        }

        int withdrawn = 0;
        for (String key : existingKeys) {
            if (seenKeys.contains(key)) {
                continue;
            }
            String[] parts = key.split(":", 2);
            withdrawn += repository.withdraw(companyId, parts[0], parts[1], now);
        }
        return new ApplyResult(drafts.size(), withdrawn);
    }

    @Transactional(readOnly = true)
    public List<CompanySignal> findByCompany(long companyId) {
        return repository.findByCompany(companyId);
    }

    public record ApplyResult(int applied, int withdrawn) {
    }
}
