package org.openboxes.identity.repository;

import org.openboxes.identity.entity.Role;
import org.openboxes.identity.entity.RoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByRoleType(RoleType roleType);
}
