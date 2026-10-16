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
 * Cada bloqueig seguit dura el doble que l'anterior (1, 2, 4, 8 minuts…) fins
 * al màxim, i un login correcte ho reinicia tot. Abans era sempre un minut:
 * 5 intents per minut, unes 7.200 contrasenyes al dia des de qualsevol aparell
 * de la wifi, on el backend és accessible per poder entrar des del mòbil. Amb
 * el màxim de 15 minuts en queden unes 480. Qui s'equivoca escrivint-la només
 * espera el primer minut.
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
    private final Duration maxLockDuration;

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger consecutiveLocks = new AtomicInteger();
    private final AtomicReference<Instant> lockedUntil = new AtomicReference<>(Instant.EPOCH);

    public LoginThrottle(
            @Value("${app.auth.max-login-attempts:5}") int maxAttempts,
            @Value("${app.auth.login-lock-duration:PT1M}") Duration lockDuration,
            @Value("${app.auth.login-lock-max-duration:PT15M}") Duration maxLockDuration) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        // Un màxim per sota del bloqueig base no té sentit: mana el base.
        this.maxLockDuration = maxLockDuration.compareTo(lockDuration) < 0 ? lockDuration : maxLockDuration;
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
            lockedUntil.set(Instant.now().plus(lockFor(consecutiveLocks.incrementAndGet())));
            failures.set(0);
        }
    }

    public void recordSuccess() {
        failures.set(0);
        consecutiveLocks.set(0);
        lockedUntil.set(Instant.EPOCH);
    }

    /** El bloqueig número {@code lockNumber} seguit: el base, doblat cada vegada, fins al màxim. */
    Duration lockFor(int lockNumber) {
        Duration duration = lockDuration;
        for (int doubling = 1; doubling < lockNumber && duration.compareTo(maxLockDuration) < 0; doubling++) {
            duration = duration.multipliedBy(2);
        }
        return duration.compareTo(maxLockDuration) > 0 ? maxLockDuration : duration;
    }
}
