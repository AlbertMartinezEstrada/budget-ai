package com.budgetai.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * L'únic joc de credencials de l'aplicació, resolt a l'arrencada.
 *
 * Es pot configurar de dues maneres:
 *   AUTH_PASSWORD_HASH  un hash BCrypt. És el que convé.
 *   AUTH_PASSWORD       la contrasenya en clar, que es xifra en arrencar.
 *                       Més còmode, però queda escrita al fitxer .env.
 *
 * Si no n'hi ha cap de les dues, l'aplicació no arrenca: val més que falli
 * de seguida que no pas quedar-se oberta sense que ningú se n'adoni.
 */
@Component
public class LocalCredentials {

    private final String username;
    private final String passwordHash;

    public LocalCredentials(
            @Value("${app.auth.username:}") String username,
            @Value("${app.auth.password:}") String plainPassword,
            @Value("${app.auth.password-hash:}") String passwordHash,
            PasswordEncoder passwordEncoder) {

        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "Falta AUTH_USERNAME. Definiu-lo al fitxer .env.");
        }

        if (passwordHash != null && !passwordHash.isBlank()) {
            this.passwordHash = passwordHash;
        } else if (plainPassword != null && !plainPassword.isBlank()) {
            this.passwordHash = passwordEncoder.encode(plainPassword);
        } else {
            throw new IllegalStateException(
                    "Falta AUTH_PASSWORD (o AUTH_PASSWORD_HASH). Definiu-ne un al fitxer .env.");
        }

        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
