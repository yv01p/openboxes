package org.openboxes.identity.controller;

import org.openboxes.identity.dto.*;
import org.openboxes.identity.service.AuthService;
import org.openboxes.identity.service.ChooseLocationResult;
import org.openboxes.identity.service.CookieService;
import org.openboxes.identity.service.LoginResult;
import org.openboxes.identity.service.MeResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class AuthController {
    private final AuthService authService;
    private final CookieService cookieService;

    public AuthController(AuthService authService, CookieService cookieService) {
        this.authService = authService;
        this.cookieService = cookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResult result = authService.login(
            request.username(),
            request.password(),
            request.location()
        );
        String cookie = cookieService.build(result.token(), false);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie)
            .body(LoginResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        String cookie = cookieService.build("", true);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie)
            .build();
    }

    @PutMapping("/chooseLocation/{id}")
    public ResponseEntity<ChooseLocationResponse> chooseLocation(
        @PathVariable String id,
        @RequestAttribute("userId") String userId
    ) {
        ChooseLocationResult result = authService.chooseLocation(userId, id);
        String cookie = cookieService.build(result.token(), false);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie)
            .body(ChooseLocationResponse.from(result));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(
        @RequestAttribute("userId") String userId,
        @RequestAttribute(value = "locationId", required = false) String locationId
    ) {
        MeResult result = authService.me(userId, locationId);
        return ResponseEntity.ok(MeResponse.from(result));
    }
}
