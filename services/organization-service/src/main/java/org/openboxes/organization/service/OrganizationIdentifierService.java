package org.openboxes.organization.service;

import org.openboxes.organization.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.stream.Collectors;

// Path (a) alternative: add `implementation 'org.apache.commons:commons-lang3:3.14.0'` to build.gradle,
//                       then `import org.apache.commons.lang3.text.WordUtils;` and replace `initials(sanitized)` below with `WordUtils.initials(sanitized)`.
// Path (b) alternative: add `implementation 'org.apache.commons:commons-text:1.12.0'` to build.gradle,
//                       then `import org.apache.commons.text.WordUtils;` and replace `initials(sanitized)` below with `WordUtils.initials(sanitized)`.
// Default: path (c) — pure Java; no new dependency.

@Service
@Transactional
public class OrganizationIdentifierService {

    @Value("${openboxes.identifier.organization.minSize}")
    private int minSize;

    @Value("${openboxes.identifier.organization.maxSize}")
    private int maxSize;

    private final OrganizationRepository repo;

    public OrganizationIdentifierService(OrganizationRepository r) { this.repo = r; }

    public String generate(String name) {
        String identifier = generateOrganizationIdentifier(name);
        if (name == null || name.isBlank()) return null;

        if (!idAlreadyExists(identifier)) return identifier;

        // If identifier exists, suffix with lowest available digit (BB0..BB9).
        String prefix = identifier.substring(0, identifier.length() - 1);
        String highest = getIdentifierWithHighestSuffix(prefix);
        if (highest != null) {
            char suffix = highest.charAt(highest.length() - 1);
            // TODO: If suffix is '9', doing suffix++ produces ':', which is garbage.
            //       Port preserves the bug verbatim per spec §13.
            suffix++;
            return identifier.toUpperCase().substring(0, identifier.length() - 1) + suffix;
        }
        return identifier.length() < maxSize
            ? identifier.toUpperCase() + '0'
            : identifier.toUpperCase().substring(0, maxSize - 1) + '0';
    }

    private String generateOrganizationIdentifier(String name) {
        // Mirror Grails: trim everything after comma; strip non-alphanumeric (keep spaces).
        String sanitized = (name == null) ? null
            : name.split(",")[0].replaceAll("[^a-zA-Z0-9 ]", "");
        if (sanitized == null || sanitized.isBlank()) return null;

        String initials = initials(sanitized);  // path (c) — swap for WordUtils.initials(sanitized) for path (a)/(b)

        String identifier;
        if (initials.length() == 1 || initials.length() < minSize) {
            String noSpaces = sanitized.replaceAll("\\s+", "");
            identifier = noSpaces.substring(0, Math.min(maxSize, noSpaces.length()));
        } else if (initials.length() > maxSize) {
            identifier = initials.substring(0, maxSize);
        } else {
            identifier = initials;
        }

        return identifier.toUpperCase();
    }

    /** Path (c) — pure Java initials helper. Remove and import WordUtils for path (a)/(b). */
    private static String initials(String s) {
        return Arrays.stream(s.split("\\s+"))
            .filter(w -> !w.isEmpty())
            .map(w -> String.valueOf(w.charAt(0)))
            .collect(Collectors.joining());
    }

    private boolean idAlreadyExists(String id) {
        return repo.countByCode(id) > 0;
    }

    private String getIdentifierWithHighestSuffix(String prefix) {
        // Mirrors Grails `like('code', prefix + '%')` + filter to digit-suffix + sort.
        return repo.findCodesStartingWith(prefix).stream()
            .filter(c -> !c.isEmpty() && Character.isDigit(c.charAt(c.length() - 1)))
            .sorted()
            .reduce((first, second) -> second)  // last element = largest digit-suffix
            .orElse(null);
    }
}
