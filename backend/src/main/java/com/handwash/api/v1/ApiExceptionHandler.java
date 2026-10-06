package com.handwash.api.v1;

import com.handwash.api.v1.dto.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Keeps framework-level REST input errors inside the versioned API error DTO. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.withMessage(
            "SOLICITUD_INVALIDA", "El cuerpo de la solicitud está mal formado o incompleto."));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> missingInput(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.withMessage(
            "ENTRADA_REQUERIDA", "Falta un parámetro o archivo obligatorio."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter(MethodArgumentTypeMismatchException ignored) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.withMessage(
            "PARAMETRO_INVALIDO", "Un parámetro de la solicitud tiene un formato inválido."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException ignored) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiErrorResponse.withMessage(
            "TIPO_CONTENIDO_NO_SOPORTADO", "El Content-Type de la solicitud no está admitido."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ignored) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiErrorResponse.withMessage(
            "CARGA_EXCEDE_LIMITE", "El tamaño de la carga supera el límite permitido."));
    }
}
