package org.stockinsight.company;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기업·종목 마스터의 변경 규칙을 담당한다. 수집기는 이 서비스로만 기업 데이터를 바꾼다. */
@Service
@Transactional
public class CompanyService {

    private static final String KRW = "KRW";

    private final CompanyRepository companies;
    private final SecurityRepository securities;
    private final CompanyAliasRepository aliases;
    private final ListingScopePolicy scopePolicy;
    private final Clock clock;

    CompanyService(CompanyRepository companies, SecurityRepository securities, CompanyAliasRepository aliases,
            ListingScopePolicy scopePolicy, Clock clock) {
        this.companies = companies;
        this.securities = securities;
        this.aliases = aliases;
        this.scopePolicy = scopePolicy;
        this.clock = clock;
    }

    /** 상장 중인 기업 정보를 반영한다. 대상 종목 범위를 판정해 ACTIVE 또는 EXCLUDED로 저장한다. */
    public Company upsertListed(ListedCompany listed) {
        Instant now = clock.instant();
        Company company = companies.findByDartCorpCode(listed.dartCorpCode())
                .orElseGet(() -> new Company(listed.dartCorpCode(), now));

        if (company.getName() != null && !company.getName().equals(listed.name())) {
            addAlias(company, company.getName(), CompanyAlias.Kind.PREVIOUS_NAME, now);
        }
        company.updateProfile(listed.name(), listed.legalName(), listed.industryCode(), listed.fiscalMonth(), now);

        ListingScopePolicy.Decision decision = scopePolicy.classify(listed.market(), listed.name(), listed.legalName());
        company.changeStatus(decision.included() ? CompanyStatus.ACTIVE : CompanyStatus.EXCLUDED, decision.reason(), now);
        companies.save(company);

        Security common = securities.findByCompanyAndShareType(company, ShareType.COMMON)
                .orElseGet(() -> new Security(company, ShareType.COMMON, KRW, now));
        common.updateListing(listed.ticker(), listed.market(), now);
        securities.save(common);
        return company;
    }

    /** 상장 목록에 더 이상 없는 기업을 상장폐지로 표시한다. 실제 상장폐지일이 아니라 감지한 날짜를 기록한다. */
    public int markDelistedExcept(Set<String> listedCorpCodes, LocalDate detectedOn) {
        Instant now = clock.instant();
        int count = 0;
        for (Company company : companies.findAllByStatusNot(CompanyStatus.DELISTED)) {
            if (company.getDartCorpCode() == null || listedCorpCodes.contains(company.getDartCorpCode())) {
                continue;
            }
            company.changeStatus(CompanyStatus.DELISTED, null, now);
            for (Security security : securities.findAllByCompany(company)) {
                security.markDelisted(detectedOn, now);
            }
            count++;
        }
        return count;
    }

    /** 상장폐지되지 않은(ACTIVE + EXCLUDED) 기업 수. */
    @Transactional(readOnly = true)
    public long countListed() {
        return companies.countByStatusNot(CompanyStatus.DELISTED);
    }

    /** 대상 종목 범위(ACTIVE) 기업의 DART 고유번호 → 기업 ID. 수집기가 저장할 기업을 고를 때 쓴다 (D-27). */
    @Transactional(readOnly = true)
    public Map<String, Long> activeCompanyIdsByDartCorpCode() {
        return companies.findAllByStatus(CompanyStatus.ACTIVE).stream()
                .filter(company -> company.getDartCorpCode() != null)
                .collect(Collectors.toMap(Company::getDartCorpCode, Company::getId));
    }

    /**
     * 대상 종목 범위(ACTIVE) 기업 ID → 결산월. 재무 수집이 분기보고서의 1·3분기를 판별할 때 쓴다.
     * 값이 없는 기업(결산월 미상)은 null로 담긴다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> activeFiscalMonthsByCompanyId() {
        Map<Long, Integer> result = new HashMap<>();
        for (Company company : companies.findAllByStatus(CompanyStatus.ACTIVE)) {
            result.put(company.getId(), company.getFiscalMonth());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<Company> findByDartCorpCode(String dartCorpCode) {
        return companies.findByDartCorpCode(dartCorpCode);
    }

    @Transactional(readOnly = true)
    public Optional<Company> findById(long companyId) {
        return companies.findById(companyId);
    }

    /** AI 적용 대상(ai_covered) 기업 ID. 대상 종목 범위(ACTIVE)로 한정한다. */
    @Transactional(readOnly = true)
    public List<Long> aiCoveredCompanyIds() {
        return companies.findAllByStatusAndAiCovered(CompanyStatus.ACTIVE, true).stream()
                .map(Company::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> aliasesOf(Company company) {
        return aliases.findAllByCompany(company).stream().map(CompanyAlias::getAlias).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Security> commonSecurityOf(Company company) {
        return securities.findByCompanyAndShareType(company, ShareType.COMMON);
    }

    private void addAlias(Company company, String alias, CompanyAlias.Kind kind, Instant now) {
        if (!aliases.existsByCompanyAndAlias(company, alias)) {
            aliases.save(new CompanyAlias(company, alias, kind, now));
        }
    }

    /** 상장 중인 기업의 기본 정보. */
    public record ListedCompany(
            String dartCorpCode,
            String name,
            String legalName,
            String ticker,
            Market market,
            String industryCode,
            Integer fiscalMonth) {

        public ListedCompany {
            Objects.requireNonNull(dartCorpCode, "dartCorpCode");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(ticker, "ticker");
            Objects.requireNonNull(market, "market");
        }
    }
}
