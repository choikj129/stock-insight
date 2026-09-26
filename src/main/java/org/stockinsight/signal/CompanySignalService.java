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
     *
     * @param latestPeriodStateBasisKey 이번 판정의 최신 기간(상태 신호 근거 키, {@link org.stockinsight.financial.PeriodKey#stateBasisKey()}).
     *                                  재무가 아예 없으면 null. "최신 재무 미확인"(FIN_DATA_STALE)이 이번 초안에
     *                                  없을 때, 그 기존 근거 키가 이 값보다 앞선 기간이면 이력으로, 아니면 철회로
     *                                  본다(D-43: 해소는 철회가 아니다).
     */
    public ApplyResult applyFinancial(long companyId, List<SignalDraft> drafts, String ruleVersion,
            String latestPeriodStateBasisKey) {
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
            String signalType = parts[0];
            String basisKey = parts[1];
            if (SignalType.FIN_DATA_STALE.name().equals(signalType) && latestPeriodStateBasisKey != null
                    && basisKey.compareTo(latestPeriodStateBasisKey) < 0) {
                // 최신 기간이 이 근거 키보다 뒤로 넘어갔다: 해소다(이력), 철회가 아니다(D-43).
                repository.markPast(companyId, signalType, basisKey, now);
                continue;
            }
            withdrawn += repository.withdraw(companyId, signalType, basisKey, now);
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
