package org.openboxes.identity.controller;

import org.openboxes.identity.dto.*;
import org.openboxes.identity.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class PasswordController {
    private final AuthService authService;
    private final PasswordResetService resetService;
    public PasswordController(AuthService a, PasswordResetService r) {
        this.authService = a; this.resetService = r;
    }

    @PostMapping("/password/change")
    public ResponseEntity<Object> changeSelf(@RequestBody ChangePasswordRequest req,
                                              @RequestAttribute("userId") String userId) {
        authService.changePassword(userId, req.currentPassword(), req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<Object> changeAdmin(@PathVariable("id") String targetUserId,
                                                @RequestBody AdminChangePasswordRequest req,
                                                @RequestAttribute("userId") String callerUserId,
                                                @RequestAttribute("roleIds") java.util.List<String> callerRoleIds) {
        authService.adminChangePassword(callerUserId, callerRoleIds, targetUserId, req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }

    @PostMapping("/password/reset-request")
    public ResponseEntity<Object> requestReset(@RequestBody ResetRequest req) {
        resetService.requestReset(req.email());
        return ResponseEntity.ok(java.util.Map.of());   // always 200
    }

    @PostMapping("/password/reset/{token}")
    public ResponseEntity<Object> confirmReset(@PathVariable("token") String token,
                                                 @RequestBody ResetConfirmRequest req) {
        resetService.confirmReset(token, req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }
}
