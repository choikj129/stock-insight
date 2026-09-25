package org.stockinsight.company;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String dartCorpCode;

    private String name;

    private String legalName;

    private String nameInitials;

    private String industryCode;

    private Integer fiscalMonth;

    @Enumerated(EnumType.STRING)
    private CompanyStatus status;

    @Enumerated(EnumType.STRING)
    private ExclusionReason exclusionReason;

    private boolean aiCovered;

    private Instant createdAt;

    private Instant updatedAt;

    protected Company() {
    }

    Company(String dartCorpCode, Instant now) {
        this.dartCorpCode = dartCorpCode;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void updateProfile(String name, String legalName, String industryCode, Integer fiscalMonth, Instant now) {
        this.name = name;
        this.legalName = legalName;
        this.nameInitials = KoreanInitials.of(name);
        this.industryCode = industryCode;
        this.fiscalMonth = fiscalMonth;
        this.updatedAt = now;
    }

    void changeStatus(CompanyStatus status, ExclusionReason exclusionReason, Instant now) {
        this.status = status;
        this.exclusionReason = exclusionReason;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getDartCorpCode() {
        return dartCorpCode;
    }

    public String getName() {
        return name;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getNameInitials() {
        return nameInitials;
    }

    public String getIndustryCode() {
        return industryCode;
    }

    public Integer getFiscalMonth() {
        return fiscalMonth;
    }

    public CompanyStatus getStatus() {
        return status;
    }

    public ExclusionReason getExclusionReason() {
        return exclusionReason;
    }

    public boolean isAiCovered() {
        return aiCovered;
    }
}
