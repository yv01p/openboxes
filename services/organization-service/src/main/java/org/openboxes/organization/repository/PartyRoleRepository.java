package org.openboxes.organization.repository;

import org.openboxes.organization.entity.PartyRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PartyRoleRepository extends JpaRepository<PartyRole, String> {
    List<PartyRole> findByPartyId(String partyId);
    List<PartyRole> findByRoleType(String roleType);
    List<PartyRole> findByPartyIdAndRoleType(String partyId, String roleType);
}
