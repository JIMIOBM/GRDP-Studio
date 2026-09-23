package com.grdp.studio.softwareintegration.support;

import com.grdp.studio.common.ApiResponse;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationController;
import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationRunController;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SoftwareIntegrationRunController.class, SoftwareIntegrationController.class})
public class SoftwareIntegrationRunExceptionHandler {
    @ExceptionHandler(RunException.class)
    public ResponseEntity<ApiResponse<Void>> handleRunException(RunException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiResponse.failure(exception.status().value(), exception.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getCode());
        if (status == null) status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, message));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, "请求参数校验失败"));
    }

    public static final class RunException extends RuntimeException {
        private final HttpStatus status;

        public RunException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() { return status; }
    }
}
