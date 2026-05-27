package org.openboxes.identity.controller;

import org.openboxes.identity.password.PasswordTooWeakException;
import org.openboxes.identity.service.AccountDisabledException;
import org.openboxes.identity.service.BadCredentialsException;
import org.openboxes.identity.service.DuplicateEmailException;
import org.openboxes.identity.service.DuplicateUsernameException;
import org.openboxes.identity.service.InvalidTokenException;
import org.openboxes.identity.service.LocationDisabledException;
import org.openboxes.identity.service.LocationNotFoundException;
import org.openboxes.identity.service.RecaptchaException;
import org.openboxes.identity.service.SignupDisabledException;
import org.openboxes.identity.service.UserAccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> badCreds() { return ResponseEntity.status(401).body(Map.of("error","invalid credentials")); }
    @ExceptionHandler({AccountDisabledException.class, UserAccessDeniedException.class, LocationDisabledException.class, RecaptchaException.class, SignupDisabledException.class})
    public ResponseEntity<?> forbidden(Exception e) { return ResponseEntity.status(403).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<?> notFound(Exception e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({DuplicateUsernameException.class, DuplicateEmailException.class})
    public ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({PasswordTooWeakException.class, InvalidTokenException.class})
    public ResponseEntity<?> badRequest(Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
}
