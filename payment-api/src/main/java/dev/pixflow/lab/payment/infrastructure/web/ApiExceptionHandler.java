package dev.pixflow.lab.payment.infrastructure.web;

import dev.pixflow.lab.payment.domain.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InvalidPaymentException.class) ProblemDetail invalid(InvalidPaymentException e){return problem(HttpStatus.BAD_REQUEST,"Invalid request",e.getMessage());}
    @ExceptionHandler(MethodArgumentNotValidException.class) ProblemDetail validation(MethodArgumentNotValidException e){return problem(HttpStatus.BAD_REQUEST,"Validation failed",e.getBindingResult().getFieldErrors().stream().map(x->x.getField()+": "+x.getDefaultMessage()).findFirst().orElse("invalid request"));}
    @ExceptionHandler(IdempotencyConflictException.class) ProblemDetail conflict(IdempotencyConflictException e){return problem(HttpStatus.CONFLICT,"Idempotency conflict",e.getMessage());}
    @ExceptionHandler(PaymentNotFoundException.class) ProblemDetail missing(PaymentNotFoundException e){return problem(HttpStatus.NOT_FOUND,"Payment not found",e.getMessage());}
    @ExceptionHandler(DataAccessException.class) ProblemDetail database(DataAccessException e){return problem(HttpStatus.SERVICE_UNAVAILABLE,"Persistence failure","payment persistence is temporarily unavailable");}
    private ProblemDetail problem(HttpStatus status,String title,String detail){var p=ProblemDetail.forStatusAndDetail(status,detail);p.setTitle(title);return p;}
}
