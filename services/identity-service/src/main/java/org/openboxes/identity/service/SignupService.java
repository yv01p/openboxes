package org.openboxes.identity.service;

import org.openboxes.identity.entity.Role;
import org.openboxes.identity.entity.RoleType;
import org.openboxes.identity.entity.User;
import org.openboxes.identity.repository.RoleRepository;
import org.openboxes.identity.repository.UserRepository;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.password.PasswordComplexityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final PasswordComplexityValidator passwordComplexityValidator;
    private final RecaptchaService recaptchaService;
    private final EmailService emailService;

    @Value("${openboxes.signup.enabled:false}")
    private boolean signupEnabled;

    @Value("${openboxes.signup.default-roles:ROLE_BROWSER}")
    private String defaultRoles;

    public SignupService(UserRepository userRepository,
                         RoleRepository roleRepository,
                         OpenboxesPasswordEncoder passwordEncoder,
                         PasswordComplexityValidator passwordComplexityValidator,
                         RecaptchaService recaptchaService,
                         EmailService emailService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordComplexityValidator = passwordComplexityValidator;
        this.recaptchaService = recaptchaService;
        this.emailService = emailService;
    }

    @Transactional
    public User signup(String username, String password, String firstName, String lastName,
                       String email, String phoneNumber, String recaptchaToken) {
        if (!signupEnabled) {
            throw new SignupDisabledException("Signup is currently disabled");
        }

        if (!recaptchaService.verifyToken(recaptchaToken)) {
            throw new RecaptchaException("Invalid reCAPTCHA token");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new DuplicateUsernameException("Username already exists");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateEmailException("Email already registered");
        }

        passwordComplexityValidator.validate(password);

        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setEmail(email);
        u.setPhoneNumber(phoneNumber);
        u.setActive(false);

        Set<Role> roles = new HashSet<>();
        Arrays.stream(defaultRoles.split(","))
                .map(String::trim)
                .forEach(roleTypeName -> {
                    try {
                        RoleType roleType = RoleType.valueOf(roleTypeName);
                        roleRepository.findByRoleType(roleType).ifPresent(roles::add);
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown role type: {}", roleTypeName);
                    }
                });

        if (!roles.isEmpty()) {
            u.setRoles(roles);
            u.setActive(true);
        }

        User saved = userRepository.save(u);

        try {
            emailService.sendWelcomeEmail(saved.getEmail(), saved.getUsername());
        } catch (Exception e) {
            log.error("Failed to send welcome email to {}", saved.getEmail(), e);
        }

        return saved;
    }
}
