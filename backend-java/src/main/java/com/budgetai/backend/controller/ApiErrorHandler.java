package com.budgetai.backend.controller;

import com.budgetai.backend.i18n.Messages;
import com.budgetai.backend.service.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tots els errors arriben al navegador amb el mateix format i un missatge que
 * diu què ha passat, en l'idioma de la capçalera Accept-Language:
 *
 * <pre>{"status": "error", "message": "...", "path": "/gastos/12/parts"}</pre>
 *
 * Abans cada controlador ho resolia a la seva manera, i el que no resolia cap
 * acabava al cos d'error per defecte de Spring, que no porta missatge. La
 * pantalla deia "Not Found", "Bad Request" o "Error 500" i res més. Alguns
 * controladors, a més, convertien qualsevol error en un 404 buit.
 *
 * Arriba després que l'excepció hagi sortit del mètode, i per tant del seu
 * @Transactional: el rollback ja s'ha fet. Els controladors que mouen diners
 * poden llançar sense capturar, com demana la regla 4.
 *
 * Els @ExceptionHandler propis d'un controlador (importacions, transferències)
 * tenen preferència sobre aquests.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** El que s'ha demanat no existeix. El missatge ja diu què. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, Messages.forUser(exception), request);
    }

    /** Validacions pròpies: el text està pensat per a l'usuari. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ClientErrors.messageFor(exception, request.getRequestURI()), request);
    }

    /** L'estat no ho permet: una categoria amb moviments, un compte amb transferències... */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ClientErrors.messageFor(exception, request.getRequestURI()), request);
    }

    /**
     * Una ruta que el backend no coneix.
     *
     * Gairebé sempre vol dir que el frontend és més nou que el backend: el
     * frontend se serveix des del disc i s'actualitza en recarregar, però el
     * backend cal reconstruir-lo. Dividir un moviment donava "Not Found" per
     * això, i res a la pantalla ho deia.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> unknownRoute(NoResourceFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, Messages.get("errors.unknown-route",
                "method", request.getMethod(), "path", request.getRequestURI()), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException exception,
                                                           HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, Messages.get("errors.wrong-method",
                "path", request.getRequestURI(), "method", request.getMethod()), request);
    }

    /**
     * Un cos que no es pot llegir: JSON mal format, una data o un número que no
     * ho són. El text de Jackson porta noms de classes, així que no es reenvia.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException exception,
                                                          HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, Messages.get("errors.unreadable-body"), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> wrongParameter(MethodArgumentTypeMismatchException exception,
                                                              HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, Messages.get("errors.invalid-parameter",
                "value", exception.getValue(), "name", exception.getName()), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException exception,
                                                                HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, Messages.get("errors.missing-parameter", "name", exception.getParameterName()),
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMediaType(HttpMediaTypeNotSupportedException exception,
                                                              HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Messages.get("errors.json-expected"), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException exception,
                                                        HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, Messages.get("errors.file-too-large"), request);
    }

    /** Els llançats a mà amb un codi i un motiu, com el rang de dates del llistat. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> withStatus(ResponseStatusException exception, HttpServletRequest request) {
        String reason = exception.getReason() != null ? exception.getReason() : Messages.get("errors.invalid-request");
        return error(exception.getStatusCode(), reason, request);
    }

    /**
     * Una clau forana o una restricció única de la base de dades.
     *
     * El text de PostgreSQL porta noms de taules i no surt: va al log amb una
     * referència.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException exception,
                                                         HttpServletRequest request) {
        String internal = ClientErrors.messageFor(exception, request.getRequestURI());
        return error(HttpStatus.CONFLICT, Messages.get("errors.integrity", "detail", internal), request);
    }

    /** Qualsevol altra cosa és nostra: el detall va al log, amb una referència per trobar-lo. */
    /**
     * El navegador ha tallat la connexió a mitja resposta: s'ha canviat de
     * vista o s'ha tancat la pestanya mentre carregava. No és cap error del
     * backend i no hi ha a qui respondre. Abans queia a «unexpected» i sortia
     * al registre com a error inesperat amb la traça sencera, cada vegada.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void clientDisconnected(Exception exception) {
        LOG.debug("El client ha tallat la connexió: {}", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ClientErrors.messageFor(exception, request.getRequestURI()),
                request);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatusCode status, String message,
                                                             HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
