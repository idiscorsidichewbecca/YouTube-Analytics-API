package it.chewbecca.youtubeanalytics.exception;

import it.chewbecca.youtubeanalytics.client.GoogleApiException;
import it.chewbecca.youtubeanalytics.meta.client.MetaGraphApiException;
import it.chewbecca.youtubeanalytics.meta.exception.MetaNotConnectedException;
import it.chewbecca.youtubeanalytics.meta.exception.MetaTokenExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({YoutubeNotConnectedException.class, YoutubeMonetaryScopeException.class})
    ResponseEntity<ApiError> youtubeUnavailable(RuntimeException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler({MetaNotConnectedException.class, MetaTokenExpiredException.class})
    ResponseEntity<ApiError> metaUnavailable(RuntimeException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiError> badRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(GoogleApiException.class)
    ResponseEntity<ApiError> googleApi(GoogleApiException ex) {
        log.error("Google API error status={} body={}", ex.getStatusCode(), ex.getResponseBody());
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(MetaGraphApiException.class)
    ResponseEntity<ApiError> metaApi(MetaGraphApiException ex) {
        log.error("Meta Graph API error operation={} status={} code={} subcode={} body={}",
                ex.getOperation(), ex.getStatusCode(), ex.getGraphCode(), ex.getGraphSubcode(), ex.getResponseBody());
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Errore inatteso", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno dell'applicazione");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message
        ));
    }

    public record ApiError(Instant timestamp, int status, String error, String message) {
    }
}
