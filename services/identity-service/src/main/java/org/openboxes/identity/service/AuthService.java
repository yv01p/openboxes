package org.openboxes.identity.service;

import org.openboxes.identity.entity.Location;
import org.openboxes.identity.entity.LocationRole;
import org.openboxes.identity.entity.Role;
import org.openboxes.identity.entity.RoleType;
import org.openboxes.identity.entity.User;
import org.openboxes.identity.repository.LocationRepository;
import org.openboxes.identity.repository.UserRepository;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.password.PasswordComplexityValidator;
import org.openboxes.identity.security.RoleTypeCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordComplexityValidator validator;
    private final RoleTypeCache roleTypeCache;

    public AuthService(UserRepository userRepository,
                       LocationRepository locationRepository,
                       OpenboxesPasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       PasswordComplexityValidator validator,
                       RoleTypeCache roleTypeCache) {
        this.userRepository = userRepository;
        this.locationRepository = locationRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.validator = validator;
        this.roleTypeCache = roleTypeCache;
    }

    @Transactional
    public LoginResult login(String username, String password, String locationId) {
        User user = userRepository.findByUsernameOrEmail(username)
            .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new AccountDisabledException("Account is disabled");
        }

        OpenboxesPasswordEncoder.setCurrentUserId(user.getId());
        try {
            if (!passwordEncoder.matches(password, user.getPassword())) {
                throw new BadCredentialsException("Invalid username or password");
            }
        } finally {
            OpenboxesPasswordEncoder.clearCurrentUserId();
        }

        Location location = null;
        if (locationId != null) {
            location = locationRepository.findById(locationId).orElse(null);
        }

        List<String> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toList());
        String token = jwtService.issue(user, locationId, roleIds);

        return new LoginResult(user, location, roleIds, token);
    }

    @Transactional
    public ChooseLocationResult chooseLocation(String userId, String locationId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new AccountDisabledException("Account is disabled");
        }

        Location location = locationRepository.findById(locationId)
            .orElseThrow(() -> new LocationNotFoundException("Location not found"));

        if (Boolean.FALSE.equals(location.getActive())) {
            throw new LocationDisabledException("Location is disabled");
        }

        boolean hasLocationRole = user.getLocationRoles() != null &&
            user.getLocationRoles().stream().anyMatch(lr -> locationId.equals(lr.getLocationId()));

        if (!hasLocationRole) {
            throw new UserAccessDeniedException("User does not have access to this location");
        }

        user.setLastLoginDate(Instant.now());
        userRepository.save(user);

        List<String> roleIds = computeEffectiveRoles(user, locationId);
        String token = jwtService.issue(user, locationId, roleIds);

        return new ChooseLocationResult(user, location, roleIds, token);
    }

    @Transactional(readOnly = true)
    public MeResult me(String userId, String locationId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new AccountDisabledException("Account is disabled");
        }

        Location location = null;
        if (locationId != null) {
            location = locationRepository.findById(locationId).orElse(null);
        }

        List<String> roleIds = computeEffectiveRoles(user, locationId);

        return new MeResult(user, location, roleIds);
    }

    private List<String> computeEffectiveRoles(User user, String locationId) {
        Stream<String> globalRoles = user.getRoles() != null
            ? user.getRoles().stream().map(Role::getId)
            : Stream.empty();

        Stream<String> locationRoles = (user.getLocationRoles() != null && locationId != null)
            ? user.getLocationRoles().stream()
                .filter(lr -> locationId.equals(lr.getLocationId()))
                .map(LocationRole::getRole)
                .map(Role::getId)
            : Stream.empty();

        return Stream.concat(globalRoles, locationRoles)
            .distinct()
            .toList();
    }

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        validator.validate(newPassword);

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        OpenboxesPasswordEncoder.setCurrentUserId(user.getId());
        try {
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                throw new BadCredentialsException("Invalid password");
            }
        } finally {
            OpenboxesPasswordEncoder.clearCurrentUserId();
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Transactional
    public void adminChangePassword(String callerUserId, List<String> callerRoleIds, String targetUserId, String newPassword) {
        if (!roleTypeCache.hasAnyType(callerRoleIds, RoleType.ROLE_ADMIN, RoleType.ROLE_SUPERUSER)) {
            throw new UserAccessDeniedException("Insufficient privileges");
        }

        validator.validate(newPassword);

        User user = userRepository.findById(targetUserId)
            .orElseThrow(() -> new BadCredentialsException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
