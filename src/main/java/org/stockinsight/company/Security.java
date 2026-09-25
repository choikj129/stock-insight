package org.stockinsight.company;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 상장 종목. 기업과 분리해 우선주·해외 상장에 대비한다 (docs/decisions.md D-18). */
@Entity
@Table(name = "security")
public class Security {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    private String ticker;

    private String isin;

    @Enumerated(EnumType.STRING)
    private Market market;

    @Enumerated(EnumType.STRING)
    private ShareType shareType;

    private String currency;

    private LocalDate listedOn;

    private LocalDate delistedOn;

    private Instant createdAt;

    private Instant updatedAt;

    protected Security() {
    }

    Security(Company company, ShareType shareType, String currency, Instant now) {
        this.company = company;
        this.shareType = shareType;
        this.currency = currency;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void updateListing(String ticker, Market market, Instant now) {
        this.ticker = ticker;
        this.market = market;
        this.delistedOn = null;
        this.updatedAt = now;
    }

    void markDelisted(LocalDate date, Instant now) {
        if (this.delistedOn == null) {
            this.delistedOn = date;
        }
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getTicker() {
        return ticker;
    }

    public Market getMarket() {
        return market;
    }

    public ShareType getShareType() {
        return shareType;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getDelistedOn() {
        return delistedOn;
    }
}
