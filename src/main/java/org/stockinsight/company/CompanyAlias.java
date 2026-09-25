package org.stockinsight.company;

import java.time.Instant;

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

@Entity
@Table(name = "company_alias")
public class CompanyAlias {

    public enum Kind {
        /** 사명 변경 전 이름 */
        PREVIOUS_NAME,
        /** 관리자가 추가한 별칭 */
        MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    private String alias;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private Instant createdAt;

    protected CompanyAlias() {
    }

    CompanyAlias(Company company, String alias, Kind kind, Instant now) {
        this.company = company;
        this.alias = alias;
        this.kind = kind;
        this.createdAt = now;
    }

    public String getAlias() {
        return alias;
    }

    public Kind getKind() {
        return kind;
    }
}
