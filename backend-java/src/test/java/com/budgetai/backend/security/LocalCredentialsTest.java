package com.budgetai.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCredentialsTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("La contrasenya en clar es xifra en arrencar i no es desa mai tal qual")
    void plainPasswordIsHashed() {
        LocalCredentials credentials = new LocalCredentials("albert", "secreta", "", encoder);

        assertThat(credentials.getPasswordHash()).doesNotContain("secreta");
        assertThat(encoder.matches("secreta", credentials.getPasswordHash())).isTrue();
        assertThat(encoder.matches("una-altra", credentials.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("Si es dona un hash, es fa servir tal qual")
    void hashTakesPrecedence() {
        String hash = encoder.encode("secreta");

        LocalCredentials credentials = new LocalCredentials("albert", "ignorada", hash, encoder);

        assertThat(credentials.getPasswordHash()).isEqualTo(hash);
        assertThat(encoder.matches("secreta", credentials.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Sense usuari, l'aplicació no arrenca")
    void missingUsernameFailsFast() {
        assertThatThrownBy(() -> new LocalCredentials("", "secreta", "", encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_USERNAME");
    }

    @Test
    @DisplayName("Sense contrasenya ni hash, l'aplicació no arrenca")
    void missingPasswordFailsFast() {
        // Val més que peti l'arrencada que no pas quedar-se obert sense que
        // ningú se n'adoni.
        assertThatThrownBy(() -> new LocalCredentials("albert", "", "", encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PASSWORD");
    }
}
