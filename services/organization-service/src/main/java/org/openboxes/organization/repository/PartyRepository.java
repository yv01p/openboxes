package org.openboxes.organization.repository;

import org.openboxes.organization.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, String> {}
