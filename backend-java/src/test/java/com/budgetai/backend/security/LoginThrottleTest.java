package com.budgetai.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    @Test
    @DisplayName("No es bloqueja fins que s'arriba al límit d'intents")
    void locksOnlyAtTheLimit() {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(1));

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
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(1));

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
        LoginThrottle throttle = new LoginThrottle(1, Duration.ofMillis(30));

        throttle.recordFailure();
        assertThat(throttle.isLocked()).isTrue();

        Thread.sleep(60);
        assertThat(throttle.isLocked()).isFalse();
    }

    @Test
    @DisplayName("El temps restant mai no és negatiu")
    void remainingLockIsNeverNegative() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(1));

        assertThat(throttle.remainingLock()).isEqualTo(Duration.ZERO);
    }
}
