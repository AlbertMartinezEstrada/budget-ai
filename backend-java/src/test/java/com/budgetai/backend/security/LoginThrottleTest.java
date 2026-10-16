package com.budgetai.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    @Test
    @DisplayName("No es bloqueja fins que s'arriba al límit d'intents")
    void locksOnlyAtTheLimit() {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(1), Duration.ofMinutes(15));

        throttle.recordFailure();
        assertThat(throttle.isLocked()).isFalse();
        throttle.recordFailure();
        assertThat(throttle.isLocked()).isFalse();

        throttle.recordFailure();
        assertThat(throttle.isLocked()).isTrue();
    }

    @Test
    @DisplayName("Un login correcte esborra els intents acumulats")
    void successResetsTheCounter() {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(1), Duration.ofMinutes(15));

        throttle.recordFailure();
        throttle.recordFailure();
        throttle.recordSuccess();

        // Si el comptador no s'hagués reiniciat, aquest tercer error bloquejaria.
        throttle.recordFailure();
        assertThat(throttle.isLocked()).isFalse();
    }

    @Test
    @DisplayName("El bloqueig caduca sol")
    void lockExpires() throws Exception {
        LoginThrottle throttle = new LoginThrottle(1, Duration.ofMillis(30), Duration.ofMillis(30));

        throttle.recordFailure();
        assertThat(throttle.isLocked()).isTrue();

        Thread.sleep(60);
        assertThat(throttle.isLocked()).isFalse();
    }

    @Test
    @DisplayName("El temps restant mai no és negatiu")
    void remainingLockIsNeverNegative() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(1), Duration.ofMinutes(15));

        assertThat(throttle.remainingLock()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Cada bloqueig seguit dura el doble, fins al màxim")
    void consecutiveLocksDoubleUpToTheMaximum() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(1), Duration.ofMinutes(15));

        assertThat(throttle.lockFor(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(throttle.lockFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(throttle.lockFor(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(throttle.lockFor(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(throttle.lockFor(5)).isEqualTo(Duration.ofMinutes(15));
        assertThat(throttle.lockFor(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Qui segueix provant després d'un bloqueig, espera més")
    void aSecondRoundOfFailuresLocksLonger() {
        LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(1), Duration.ofMinutes(15));

        throttle.recordFailure();
        throttle.recordFailure();
        assertThat(throttle.remainingLock()).isLessThanOrEqualTo(Duration.ofMinutes(1));

        // Passat el primer bloqueig (aquí es força perquè no cal esperar-lo),
        // dos errors més bloquegen el doble.
        throttle.recordFailure();
        throttle.recordFailure();
        assertThat(throttle.remainingLock()).isGreaterThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Un login correcte torna el bloqueig al minut de partida")
    void successResetsTheEscalation() {
        LoginThrottle throttle = new LoginThrottle(1, Duration.ofMinutes(1), Duration.ofMinutes(15));

        throttle.recordFailure();
        throttle.recordFailure();
        throttle.recordFailure();
        assertThat(throttle.remainingLock()).isGreaterThan(Duration.ofMinutes(3));

        throttle.recordSuccess();
        throttle.recordFailure();
        assertThat(throttle.remainingLock()).isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Un màxim per sota del bloqueig base no l'escurça")
    void maximumBelowTheBaseKeepsTheBase() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(2), Duration.ofMinutes(1));

        assertThat(throttle.lockFor(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(throttle.lockFor(3)).isEqualTo(Duration.ofMinutes(2));
    }
}
