package com.boxy.boxy.modules.administration.repository;

import com.boxy.boxy.modules.administration.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByIdAndDeletedAtIsNull(Long id);
    Optional<Company> findFirstByDeletedAtIsNull();
    /** Case-insensitive: /catalogo/latinaTools and /catalogo/latinatools are the same catalog. */
    Optional<Company> findBySlugIgnoreCaseAndIsActiveTrueAndDeletedAtIsNull(String slug);
}
