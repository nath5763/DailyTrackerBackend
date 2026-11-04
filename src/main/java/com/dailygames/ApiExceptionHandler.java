package com.dailygames;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import static com.dailygames.ApiModels.ErrorResponse;

@RestControllerAdvice
class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> api(ApiException exception) {
    return ResponseEntity.status(exception.status).body(new ErrorResponse(exception.getMessage(), exception.code));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ErrorResponse> validation(Exception exception) {
    return ResponseEntity.badRequest().body(new ErrorResponse("Check the submitted fields.", "INVALID_REQUEST"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception exception) {
    log.error("Unexpected API failure", exception);
    return ResponseEntity.internalServerError()
        .body(new ErrorResponse("The server could not complete the request.", "INTERNAL_SERVER_ERROR"));
  }
}
