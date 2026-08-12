package eckerlin.dev.web;

import eckerlin.dev.utils.Alert;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Sorgt dafuer, dass die Dashboard-API in jedem Fall JSON zurueckgibt.
 *
 * <p>Ohne diesen Handler lieferte Spring bei einem Fehler die Standard-
 * Fehlerseite aus. Das Dashboard liest den Body dann als Text und schrieb
 * kompletten HTML-Quelltext in den Statusbalken - fuer den Nutzer sah das aus
 * wie ein zufaelliger Abbruch ohne erkennbaren Grund.
 */
@RestControllerAdvice(assignableTypes = {DashboardApiController.class, AdminApiController.class})
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return build(
                status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                exception.getReason() == null ? "Die Anfrage konnte nicht verarbeitet werden." : exception.getReason()
        );
    }

    /**
     * Greift, wenn ein Player-Endpunkt laenger braucht als
     * {@code spring.mvc.async.request-timeout}.
     */
    @ExceptionHandler({AsyncRequestTimeoutException.class, TimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout() {
        return build(
                HttpStatus.GATEWAY_TIMEOUT,
                "Der Bot hat nicht rechtzeitig geantwortet. Die Aktion laeuft moeglicherweise trotzdem noch - "
                        + "die Anzeige aktualisiert sich gleich von selbst."
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException exception) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, safeMessage(exception, "Der Bot ist gerade nicht bereit."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return build(HttpStatus.BAD_REQUEST, safeMessage(exception, "Die Eingabe ist ungueltig."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception, HttpServletRequest request) {
        Throwable root = unwrap(exception);
        if (root instanceof TimeoutException) {
            return handleTimeout();
        }

        Alert.send("WARN", "WEB", "Unerwarteter API-Fehler bei " + request.getRequestURI() + ": " + root);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, safeMessage(root, "Unerwarteter Fehler im Bot."));
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String safeMessage(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message.length() > 400 ? message.substring(0, 397) + "..." : message;
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
