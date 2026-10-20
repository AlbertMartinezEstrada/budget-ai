package com.budgetai.backend.controller;

import com.budgetai.backend.i18n.Messages;
import com.budgetai.backend.model.AuthSession;
import com.budgetai.backend.security.AuthCookies;
import com.budgetai.backend.security.JwtService;
import com.budgetai.backend.security.LocalCredentials;
import com.budgetai.backend.security.LoginThrottle;
import com.budgetai.backend.security.SessionService;
import com.budgetai.backend.security.SessionUser;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final LocalCredentials credentials;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final SessionService sessions;
    private final boolean secureCookie;

    public AuthController(LocalCredentials credentials,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginThrottle throttle,
                          SessionService sessions,
                          @Value("${app.auth.secure-cookie:false}") boolean secureCookie) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.sessions = sessions;
        this.secureCookie = secureCookie;
    }

    public record LoginRequest(String username, String password) {}

    /** Una sessió oberta tal com la veu Configuració. L'id no hi surt: no cal per a res. */
    public record SessionView(
            @JsonProperty("navegador") String userAgent,
            @JsonProperty("creada") Instant createdAt,
            @JsonProperty("darrer_us") Instant lastUsedAt,
            @JsonProperty("caduca") Instant expiresAt,
            @JsonProperty("actual") boolean current) {}

    /**
     * Els intents es compten per l'adreça de la connexió (getRemoteAddr), no
     * per la capçalera X-Forwarded-For: no hi ha cap proxy davant i qualsevol
     * se la podria inventar per començar de zero a cada intent.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                   HttpServletRequest httpRequest) {
        String address = httpRequest.getRemoteAddr();
        if (throttle.isLocked(address)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", Messages.get("auth.too-many-attempts",
                            "seconds", Math.max(1, throttle.remainingLock(address).toSeconds()))));
        }

        boolean userMatches = credentials.getUsername().equals(request.username());
        // La comprovació de la contrasenya s'executa encara que l'usuari no
        // coincideixi, perquè el temps de resposta no delati quin dels dos
        // camps és el que falla.
        boolean passwordMatches = passwordEncoder.matches(
                request.password() == null ? "" : request.password(),
                credentials.getPasswordHash());

        if (!userMatches || !passwordMatches) {
            throttle.recordFailure(address);
            // Un sol missatge per als dos casos: dir quin ha fallat és regalar
            // mitja credencial.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", Messages.get("auth.wrong-credentials")));
        }

        throttle.recordSuccess(address);
        AuthSession session = sessions.open(userAgent);
        String token = jwtService.issueToken(credentials.getUsername(), session.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        AuthCookies.build(token, jwtService.getValidity().toSeconds(), secureCookie))
                .body(Map.of("username", credentials.getUsername()));
    }

    /**
     * Tanca la sessió al servidor, no només al navegador: abans s'esborrava la
     * cookie i prou, i una còpia del token continuava servint fins que caducava.
     *
     * Respon igual sense sessió (ja tancada o caducada): sortir ha de deixar
     * sempre el navegador sense cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal SessionUser user) {
        if (user != null) sessions.close(user.sessionId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.expired(secureCookie))
                .body(Map.of("message", Messages.get("auth.logged-out")));
    }

    /**
     * El frontend crida això en arrencar per saber si ha de mostrar la
     * pantalla d'entrada o l'aplicació. Requereix sessió, així que un 401
     * ja és la resposta "no has iniciat sessió".
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        return ResponseEntity.ok(Map.of("username", authentication.getName()));
    }

    /** Les sessions obertes, per a Configuració, amb la d'aquesta petició marcada. */
    @GetMapping("/sessions")
    public List<SessionView> openSessions(@AuthenticationPrincipal SessionUser user) {
        return sessions.openSessions().stream()
                .map(session -> new SessionView(session.getUserAgent(), session.getCreatedAt(),
                        session.getLastUsedAt(), session.getExpiresAt(), session.getId().equals(user.sessionId())))
                .toList();
    }

    /** Tanca totes les sessions menys la d'aquesta petició: el que es fa si no se'n reconeix alguna. */
    @DeleteMapping("/sessions/others")
    public Map<String, Integer> closeOtherSessions(@AuthenticationPrincipal SessionUser user) {
        return Map.of("tancades", sessions.closeAllExcept(user.sessionId()));
    }
}
