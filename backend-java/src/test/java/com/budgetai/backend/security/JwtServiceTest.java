package com.budgetai.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "una-clau-prou-llarga-per-a-hmac-sha256-de-debo";

    private JwtService service(Duration validity) {
        return new JwtService(SECRET, validity);
    }

    @Test
    @DisplayName("Un token emès es pot tornar a llegir")
    void roundTrip() {
        JwtService service = service(Duration.ofHours(1));

        String token = service.issueToken("albert");

        assertThat(service.resolveUsername(token)).contains("albert");
    }

    @Test
    @DisplayName("Un token amb la signatura manipulada es rebutja")
    void tamperedSignatureIsRejected() {
        JwtService service = service(Duration.ofHours(1));
        String token = service.issueToken("albert");

        String tampered = token.substring(0, token.lastIndexOf('.')) + ".signatura-falsa";

        assertThat(service.resolveUsername(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Un token signat amb una altra clau es rebutja")
    void tokenFromAnotherKeyIsRejected() {
        String foreignToken = service(Duration.ofHours(1)).issueToken("albert");

        JwtService other = new JwtService(
                "una-clau-completament-diferent-pero-igual-de-llarga", Duration.ofHours(1));

        assertThat(other.resolveUsername(foreignToken)).isEmpty();
    }

    @Test
    @DisplayName("Un token caducat es rebutja")
    void expiredTokenIsRejected() throws Exception {
        JwtService service = service(Duration.ofMillis(1));
        String token = service.issueToken("albert");

        Thread.sleep(50);

        assertThat(service.resolveUsername(token)).isEmpty();
    }

    @Test
    @DisplayName("Els valors buits i les cadenes que no són tokens es rebutgen sense petar")
    void garbageIsRejected() {
        JwtService service = service(Duration.ofHours(1));

        assertThat(service.resolveUsername(null)).isEmpty();
        assertThat(service.resolveUsername("")).isEmpty();
        assertThat(service.resolveUsername("   ")).isEmpty();
        assertThat(service.resolveUsername("no.es.un.token")).isEmpty();
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
