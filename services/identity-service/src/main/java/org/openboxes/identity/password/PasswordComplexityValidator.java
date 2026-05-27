package org.openboxes.identity.password;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class PasswordComplexityValidator {
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]");

    /** Throws PasswordTooWeakException with a list of failed rules. */
    public void validate(String password) {
        if (password == null) throw new PasswordTooWeakException("password is required");
        var failures = new java.util.ArrayList<String>();
        if (password.length() < 8) failures.add("minSize 8");
        if (password.length() > 255) failures.add("maxSize 255");
        if (!UPPER.matcher(password).find()) failures.add("at least 1 uppercase");
        if (!LOWER.matcher(password).find()) failures.add("at least 1 lowercase");
        if (!DIGIT.matcher(password).find()) failures.add("at least 1 digit");
        if (!SPECIAL.matcher(password).find()) failures.add("at least 1 special character");
        if (!failures.isEmpty()) throw new PasswordTooWeakException("password fails: " + String.join(", ", failures));
    }
}
