package org.stockinsight.company;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByDartCorpCode(String dartCorpCode);

    List<Company> findAllByStatus(CompanyStatus status);

    List<Company> findAllByStatusNot(CompanyStatus status);

    long countByStatusNot(CompanyStatus status);
}

interface SecurityRepository extends JpaRepository<Security, Long> {

    Optional<Security> findByCompanyAndShareType(Company company, ShareType shareType);

    List<Security> findAllByCompany(Company company);
}

interface CompanyAliasRepository extends JpaRepository<CompanyAlias, Long> {

    boolean existsByCompanyAndAlias(Company company, String alias);

    List<CompanyAlias> findAllByCompany(Company company);
}
