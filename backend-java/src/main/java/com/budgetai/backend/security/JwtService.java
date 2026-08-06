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
 * El token és un JWT signat amb HMAC-SHA256 i no guarda res més que el nom
 * d'usuari i la caducitat: com que només hi ha un usuari, no cal desar cap
 * estat de sessió al servidor.
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

    public String issueToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validity.toMillis()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Retorna el nom d'usuari si el token és vàlid i no ha caducat.
     * Un token manipulat, mal signat o caducat retorna un Optional buit: mai
     * una excepció cap amunt, perquè un token dolent és un cas normal.
     */
    public Optional<String> resolveUsername(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration getValidity() {
        return validity;
    }
}
