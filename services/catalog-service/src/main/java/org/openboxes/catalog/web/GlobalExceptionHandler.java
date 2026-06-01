package org.openboxes.catalog.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// Maps catalog write-path failures to honest 4xx IN-DISPATCHER, so the request is never
// forwarded to /error (which would re-enter the security chain and return a spurious 401 via
// HttpStatusEntryPoint(UNAUTHORIZED) — see write-contract-reconciliation design §6).
// Handled set is extended per task as write paths land (T3: DuplicatePreferenceException).
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Uniqueness/constraint conflict at the DB layer (e.g. ProductPackage's unique index).
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("error", "constraint violation"));
    }

    // Bad or unreadable request body.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.status(400).body(Map.of("error", "bad request"));
    }
}
