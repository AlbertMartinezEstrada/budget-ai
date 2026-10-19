package com.budgetai.backend.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "una-clau-prou-llarga-per-a-hmac-sha256-de-debo";
    private static final String SESSION_ID = "8f0c2a4e-1d3b-4c5a-9e7f-0a1b2c3d4e5f";

    private JwtService service(Duration validity) {
        return new JwtService(SECRET, validity);
    }

    @Test
    @DisplayName("Un token emès es pot tornar a llegir, amb l'usuari i la sessió")
    void roundTrip() {
        JwtService service = service(Duration.ofHours(1));

        String token = service.issueToken("albert", SESSION_ID);

        assertThat(service.resolve(token)).contains(new JwtService.SessionToken("albert", SESSION_ID));
    }

    @Test
    @DisplayName("Un token ben signat però sense sessió és dels d'abans i es rebutja")
    void tokenWithoutSessionIsRejected() {
        // Els d'abans de la taula sessions no portaven jti: no es podrien tancar.
        String oldToken = Jwts.builder()
                .subject("albert")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(service(Duration.ofHours(1)).resolve(oldToken)).isEmpty();
    }

    @Test
    @DisplayName("Un token amb la signatura manipulada es rebutja")
    void tamperedSignatureIsRejected() {
        JwtService service = service(Duration.ofHours(1));
        String token = service.issueToken("albert", SESSION_ID);

        String tampered = token.substring(0, token.lastIndexOf('.')) + ".signatura-falsa";

        assertThat(service.resolve(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Un token signat amb una altra clau es rebutja")
    void tokenFromAnotherKeyIsRejected() {
        String foreignToken = service(Duration.ofHours(1)).issueToken("albert", SESSION_ID);

        JwtService other = new JwtService(
                "una-clau-completament-diferent-pero-igual-de-llarga", Duration.ofHours(1));

        assertThat(other.resolve(foreignToken)).isEmpty();
    }

    @Test
    @DisplayName("Un token caducat es rebutja")
    void expiredTokenIsRejected() throws Exception {
        JwtService service = service(Duration.ofMillis(1));
        String token = service.issueToken("albert", SESSION_ID);

        Thread.sleep(50);

        assertThat(service.resolve(token)).isEmpty();
    }

    @Test
    @DisplayName("Els valors buits i les cadenes que no són tokens es rebutgen sense petar")
    void garbageIsRejected() {
        JwtService service = service(Duration.ofHours(1));

        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve("   ")).isEmpty();
        assertThat(service.resolve("no.es.un.token")).isEmpty();
    }

    @Test
    @DisplayName("Una clau massa curta fa fallar l'arrencada, no la primera petició")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("massa-curta", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");

        assertThatThrownBy(() -> new JwtService(null, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
