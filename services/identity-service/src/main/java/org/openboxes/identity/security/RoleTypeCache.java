package org.openboxes.identity.security;

import jakarta.annotation.PostConstruct;
import org.openboxes.identity.entity.Role;
import org.openboxes.identity.entity.RoleType;
import org.openboxes.identity.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RoleTypeCache {
    private final RoleRepository roleRepository;
    private volatile Map<String, RoleType> cache = Map.of();

    public RoleTypeCache(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @PostConstruct void load() {
        cache = roleRepository.findAll().stream()
            .collect(Collectors.toUnmodifiableMap(Role::getId, Role::getRoleType));
    }

    /** Returns the RoleType for a role ID. On miss, reloads cache once and retries; returns null if still missing. */
    public RoleType getRoleType(String roleId) {
        RoleType type = cache.get(roleId);
        if (type != null) return type;
        load();   // refresh-on-miss
        return cache.get(roleId);
    }

    public boolean hasAnyType(Iterable<String> roleIds, RoleType... wanted) {
        var wantedSet = java.util.Set.of(wanted);
        for (String id : roleIds) {
            RoleType t = getRoleType(id);
            if (t != null && wantedSet.contains(t)) return true;
        }
        return false;
    }
}
