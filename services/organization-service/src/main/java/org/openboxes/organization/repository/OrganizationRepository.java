package org.openboxes.organization.repository;

import org.openboxes.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OrganizationRepository extends JpaRepository<Organization, String> {
    long countByCode(String code);

    @Query("SELECT o.code FROM Organization o WHERE o.code LIKE CONCAT(:prefix, '%')")
    List<String> findCodesStartingWith(@Param("prefix") String prefix);

    @Query("SELECT DISTINCT o FROM Organization o LEFT JOIN o.roles r WHERE " +
           "(:q IS NULL OR LOWER(o.id) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.code) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.description) LIKE LOWER(CONCAT('%', :q, '%'))) AND " +
           "(:active IS NULL OR o.active = :active) AND " +
           "(:hasRoles = FALSE OR r.roleType IN :roleTypes)")
    List<Organization> findFiltered(@Param("q") String q,
                                    @Param("active") Boolean active,
                                    @Param("hasRoles") boolean hasRoles,
                                    @Param("roleTypes") List<String> roleTypes);
}
