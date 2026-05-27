package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class RecaptchaService {

    private static final String RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify?secret={s}&response={t}";

    private final boolean enabled;
    private final String secret;
    private final RestClient http = RestClient.create();

    public RecaptchaService(@Value("${openboxes.signup.recaptcha.enabled:false}") boolean enabled,
                            @Value("${openboxes.signup.recaptcha.secret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret;
    }

    public boolean validate(String token) {
        if (!enabled) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        Map<String, Object> response = http.post()
                .uri(RECAPTCHA_VERIFY_URL, secret, token)
                .retrieve()
                .body(Map.class);
        return Boolean.TRUE.equals(response.get("success"));
    }
}
