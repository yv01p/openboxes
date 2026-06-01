package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.UnitOfMeasureConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface UnitOfMeasureConversionRepository extends JpaRepository<UnitOfMeasureConversion, String> {
    // Port of the Grails conversionRateLookup named query (UnitOfMeasureConversion.groovy): the active
    // conversion rate from one UoM code to another, most-recent first (order by lastUpdated DESC;
    // tolerates multiples — the named query's uniqueResult takes the first). Returns rates as a list;
    // the service takes findFirst(). NOT cache-served (navigates fromUnitOfMeasure.code, so it runs as a
    // real query inside the read tx rather than off a detached cached entity).
    @Query("SELECT c.conversionRate FROM UnitOfMeasureConversion c "
         + "WHERE c.active = true "
         + "AND c.fromUnitOfMeasure.code = :fromCode "
         + "AND c.toUnitOfMeasure.code = :toCode "
         + "ORDER BY c.lastUpdated DESC")
    List<BigDecimal> findActiveConversionRates(@Param("fromCode") String fromCode, @Param("toCode") String toCode);
}
