package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class RecaptchaService {

    private static final String RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify?secret={s}&response={t}";

    @Value("${openboxes.signup.recaptcha.enabled:false}")
    private boolean recaptchaEnabled;

    @Value("${openboxes.signup.recaptcha.secret:}")
    private String recaptchaSecret;

    private final RestClient restClient;

    public RecaptchaService() {
        this.restClient = RestClient.create();
    }

    public boolean verifyToken(String token) {
        if (!recaptchaEnabled) {
            return true;
        }
        Map<String, Object> response = restClient.post()
                .uri(RECAPTCHA_VERIFY_URL, recaptchaSecret, token)
                .retrieve()
                .body(Map.class);
        return Boolean.TRUE.equals(response.get("success"));
    }
}
