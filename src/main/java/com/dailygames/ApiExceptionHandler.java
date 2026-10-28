package com.dailygames;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import static com.dailygames.ApiModels.ErrorResponse;

@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> api(ApiException exception) {
    return ResponseEntity.status(exception.status).body(new ErrorResponse(exception.getMessage(), exception.code));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ErrorResponse> validation(Exception exception) {
    return ResponseEntity.badRequest().body(new ErrorResponse("Check the submitted fields.", "INVALID_REQUEST"));
  }
}
