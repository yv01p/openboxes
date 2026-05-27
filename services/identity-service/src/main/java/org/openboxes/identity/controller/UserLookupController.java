package org.openboxes.identity.controller;

import org.openboxes.identity.dto.UserLookupResponse;
import org.openboxes.identity.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class UserLookupController {
    private final UserRepository userRepository;
    public UserLookupController(UserRepository u) { this.userRepository = u; }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserLookupResponse> get(@PathVariable String id) {
        return userRepository.findById(id)
            .map(UserLookupResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
