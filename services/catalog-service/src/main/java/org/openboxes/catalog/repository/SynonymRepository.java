package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Synonym;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynonymRepository extends JpaRepository<Synonym, String> {
    long countByProductIdAndLocaleAndSynonymTypeCode(String productId, java.util.Locale locale, String synonymTypeCode);
}
