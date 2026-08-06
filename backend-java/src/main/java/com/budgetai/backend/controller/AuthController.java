package com.budgetai.backend.controller;

import com.budgetai.backend.security.AuthCookies;
import com.budgetai.backend.security.JwtService;
import com.budgetai.backend.security.LocalCredentials;
import com.budgetai.backend.security.LoginThrottle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final LocalCredentials credentials;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final boolean secureCookie;

    public AuthController(LocalCredentials credentials,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          LoginThrottle throttle,
                          @Value("${app.auth.secure-cookie:false}") boolean secureCookie) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.secureCookie = secureCookie;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (throttle.isLocked()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Massa intents fallits. Torna-ho a provar d'aquí a "
                            + Math.max(1, throttle.remainingLock().toSeconds()) + " segons."));
        }

        boolean userMatches = credentials.getUsername().equals(request.username());
        // La comprovació de la contrasenya s'executa encara que l'usuari no
        // coincideixi, perquè el temps de resposta no delati quin dels dos
        // camps és el que falla.
        boolean passwordMatches = passwordEncoder.matches(
                request.password() == null ? "" : request.password(),
                credentials.getPasswordHash());

        if (!userMatches || !passwordMatches) {
            throttle.recordFailure();
            // Un sol missatge per als dos casos: dir quin ha fallat és regalar
            // mitja credencial.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Usuari o contrasenya incorrectes"));
        }

        throttle.recordSuccess();
        String token = jwtService.issueToken(credentials.getUsername());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        AuthCookies.build(token, jwtService.getValidity().toSeconds(), secureCookie))
                .body(Map.of("username", credentials.getUsername()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthCookies.expired(secureCookie))
                .body(Map.of("message", "Sessió tancada"));
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
}
