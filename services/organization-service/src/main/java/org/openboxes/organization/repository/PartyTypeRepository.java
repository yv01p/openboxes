package org.openboxes.organization.repository;

import org.openboxes.organization.entity.PartyType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PartyTypeRepository extends JpaRepository<PartyType, String> {
    Optional<PartyType> findByCode(String code);
}
