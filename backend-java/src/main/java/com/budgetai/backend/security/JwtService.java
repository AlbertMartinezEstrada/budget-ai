package com.budgetai.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * Emissió i validació dels tokens de sessió.
 *
 * El token és un JWT signat amb HMAC-SHA256 amb el nom d'usuari, la caducitat
 * i l'id de la sessió (jti). La signatura i la caducitat es miren aquí; si la
 * sessió encara és oberta, a SessionService. Abans no portava cap id ni hi
 * havia res desat al servidor, i per això sortir no podia tancar la sessió de
 * debò.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration validity;

    public JwtService(
            @Value("${app.auth.jwt-secret}") String secret,
            @Value("${app.auth.session-duration}") Duration validity) {

        // HMAC-SHA256 necessita com a mínim 256 bits. Es comprova a l'arrencada
        // perquè una clau curta faci fallar l'aplicació de seguida i no en
        // enviar el primer token.
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret ha de tenir com a mínim 32 caràcters (en té "
                            + keyBytes.length + "). Definiu JWT_SECRET al fitxer .env.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.validity = validity;
    }

    /** El que diu un token vàlid: de qui és i de quina sessió. */
    public record SessionToken(String username, String sessionId) {}

    public String issueToken(String username, String sessionId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .id(sessionId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validity.toMillis()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Retorna l'usuari i la sessió si el token és vàlid i no ha caducat.
     * Un token manipulat, mal signat o caducat retorna un Optional buit: mai
     * una excepció cap amunt, perquè un token dolent és un cas normal.
     *
     * Un token sense sessió també: és dels d'abans, que no es podien tancar.
     * Qui en tingui un ha de tornar a entrar una vegada.
     */
    public Optional<SessionToken> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getSubject() == null || claims.getId() == null) return Optional.empty();
            return Optional.of(new SessionToken(claims.getSubject(), claims.getId()));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public Duration getValidity() {
        return validity;
    }
}
