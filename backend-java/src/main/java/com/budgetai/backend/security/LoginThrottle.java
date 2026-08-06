package com.budgetai.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Limitador d'intents de login.
 *
 * Amb un sol usuari i una contrasenya, el punt d'entrada natural és provar
 * contrasenyes fins a encertar-la. Uns quants intents fallits seguits bloquegen
 * el login una estona.
 *
 * És en memòria i per a tot el procés, no per adreça IP: amb un sol usuari no
 * té sentit distingir qui prova, i comptar per IP només serviria perquè
 * l'atacant les fes rotar. Reiniciar el backend neteja el comptador, cosa
 * assumible per a una aplicació d'una sola persona.
 */
@Component
public class LoginThrottle {

    private final int maxAttempts;
    private final Duration lockDuration;

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<Instant> lockedUntil = new AtomicReference<>(Instant.EPOCH);

    public LoginThrottle(
            @Value("${app.auth.max-login-attempts:5}") int maxAttempts,
            @Value("${app.auth.login-lock-duration:PT1M}") Duration lockDuration) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
    }

    public boolean isLocked() {
        return Instant.now().isBefore(lockedUntil.get());
    }

    public Duration remainingLock() {
        Duration remaining = Duration.between(Instant.now(), lockedUntil.get());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public void recordFailure() {
        if (failures.incrementAndGet() >= maxAttempts) {
            lockedUntil.set(Instant.now().plus(lockDuration));
            failures.set(0);
        }
    }

    public void recordSuccess() {
        failures.set(0);
        lockedUntil.set(Instant.EPOCH);
    }
}
