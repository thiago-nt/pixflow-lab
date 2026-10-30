package dev.pixflow.lab.psp;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class PspSimulatorErrors {
    @ExceptionHandler(PspReferenceConflictException.class)
    ResponseEntity<?> conflict(PspReferenceConflictException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "REFERENCE_CONFLICT")); }
    @ExceptionHandler(PspTransactionNotFoundException.class)
    ResponseEntity<?> missing(PspTransactionNotFoundException ex) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "TRANSACTION_NOT_FOUND")); }
}
