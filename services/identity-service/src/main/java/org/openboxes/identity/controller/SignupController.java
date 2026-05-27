package org.openboxes.identity.controller;

import org.openboxes.identity.dto.SignupRequest;
import org.openboxes.identity.dto.SignupResponse;
import org.openboxes.identity.entity.User;
import org.openboxes.identity.service.SignupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity/signup")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest req) {
        User user = signupService.signup(req.username(), req.password(), req.firstName(),
                req.lastName(), req.email(), req.phoneNumber(), req.recaptchaToken());
        return ResponseEntity.ok(SignupResponse.from(user));
    }
}
