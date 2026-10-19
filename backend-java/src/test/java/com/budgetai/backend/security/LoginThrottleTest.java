package com.budgetai.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private static final String PHONE = "192.168.1.20";
    private static final String LAPTOP = "192.168.1.30";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));

    /** Per adreça: {@code attempts} errors; per a tothom, 50 en 10 minuts. Bloqueig d'1 a 15 minuts. */
    private LoginThrottle throttle(int attempts) {
        return new LoginThrottle(attempts, 50, Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofMinutes(15), clock);
    }

    private static void fail(LoginThrottle throttle, String address, int times) {
        for (int attempt = 0; attempt < times; attempt++) throttle.recordFailure(address);
    }

    @Test
    @DisplayName("No es bloqueja fins que s'arriba al límit d'intents")
    void locksOnlyAtTheLimit() {
        LoginThrottle throttle = throttle(3);

        fail(throttle, PHONE, 2);
        assertThat(throttle.isLocked(PHONE)).isFalse();

        throttle.recordFailure(PHONE);
        assertThat(throttle.isLocked(PHONE)).isTrue();
    }

    @Test
    @DisplayName("Els errors d'una adreça la bloquegen a ella, no a les altres")
    void failuresFromOneAddressDoNotLockTheOthers() {
        // Abans el comptador era un per a tot el backend: qualsevol de la wifi
        // podia deixar-te sense entrar.
        LoginThrottle throttle = throttle(5);

        fail(throttle, PHONE, 5);

        assertThat(throttle.isLocked(PHONE)).isTrue();
        assertThat(throttle.isLocked(LAPTOP)).isFalse();
        assertThat(throttle.remainingLock(LAPTOP)).isZero();
    }

    @Test
    @DisplayName("Un login correcte esborra els intents acumulats de la seva adreça, no els de les altres")
    void successResetsOnlyItsAddress() {
        LoginThrottle throttle = throttle(3);

        fail(throttle, PHONE, 2);
        fail(throttle, LAPTOP, 2);
        throttle.recordSuccess(PHONE);

        // Si el comptador no s'hagués reiniciat, aquest tercer error bloquejaria.
        throttle.recordFailure(PHONE);
        assertThat(throttle.isLocked(PHONE)).isFalse();
        throttle.recordFailure(LAPTOP);
        assertThat(throttle.isLocked(LAPTOP)).isTrue();
    }

    @Test
    @DisplayName("Molts errors des d'adreces diferents bloquegen el login per a tothom")
    void manyFailuresFromManyAddressesLockEveryone() {
        // Qui va canviant d'adreça no arriba mai als cinc errors de cap: per
        // això hi ha el límit de tothom.
        LoginThrottle throttle = throttle(5);

        for (int address = 1; address < 50; address++) throttle.recordFailure("203.0.113." + address);
        assertThat(throttle.isLocked(LAPTOP)).isFalse();

        throttle.recordFailure("203.0.113.50");

        assertThat(throttle.isLocked(LAPTOP)).isTrue();
        assertThat(throttle.isLocked("198.51.100.1")).isTrue();
        assertThat(throttle.remainingLock(LAPTOP)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Els errors de tothom compten dins de la finestra: repartits en més estona, no bloquegen")
    void failuresOutsideTheWindowDoNotAddUp() {
        LoginThrottle throttle = throttle(5);

        for (int address = 1; address <= 49; address++) throttle.recordFailure("203.0.113." + address);
        clock.advance(Duration.ofMinutes(11));
        throttle.recordFailure("203.0.113.50");

        assertThat(throttle.isLocked(LAPTOP)).isFalse();
    }

    @Test
    @DisplayName("Cada bloqueig de tothom seguit també dura el doble")
    void consecutiveLocksForEveryoneDouble() {
        LoginThrottle throttle = throttle(5);

        for (int address = 1; address <= 50; address++) throttle.recordFailure("203.0.113." + address);
        clock.advance(Duration.ofMinutes(1));
        for (int address = 51; address <= 100; address++) throttle.recordFailure("203.0.113." + address);

        assertThat(throttle.remainingLock(LAPTOP)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("El bloqueig caduca sol")
    void lockExpires() {
        LoginThrottle throttle = throttle(1);

        throttle.recordFailure(PHONE);
        assertThat(throttle.isLocked(PHONE)).isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(throttle.isLocked(PHONE)).isFalse();
    }

    @Test
    @DisplayName("El temps restant mai no és negatiu")
    void remainingLockIsNeverNegative() {
        LoginThrottle throttle = throttle(1);

        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ZERO);
        throttle.recordFailure(PHONE);
        clock.advance(Duration.ofHours(3));
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Cada bloqueig seguit dura el doble, fins al màxim")
    void consecutiveLocksDoubleUpToTheMaximum() {
        LoginThrottle throttle = throttle(5);

        assertThat(throttle.lockFor(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(throttle.lockFor(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(throttle.lockFor(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(throttle.lockFor(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(throttle.lockFor(5)).isEqualTo(Duration.ofMinutes(15));
        assertThat(throttle.lockFor(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Qui segueix provant des de la mateixa adreça després d'un bloqueig, espera més")
    void aSecondRoundOfFailuresLocksLonger() {
        LoginThrottle throttle = throttle(2);

        fail(throttle, PHONE, 2);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        fail(throttle, PHONE, 2);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("Un login correcte torna el bloqueig de la seva adreça al minut de partida")
    void successResetsTheEscalation() {
        LoginThrottle throttle = throttle(1);

        for (int round = 0; round < 3; round++) {
            throttle.recordFailure(PHONE);
            clock.advance(throttle.remainingLock(PHONE));
        }
        throttle.recordFailure(PHONE);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(8));

        clock.advance(Duration.ofMinutes(8));
        throttle.recordSuccess(PHONE);
        throttle.recordFailure(PHONE);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Una adreça que fa una hora que no falla s'oblida, i torna a començar pel bloqueig curt")
    void quietAddressesAreForgotten() {
        LoginThrottle throttle = throttle(1);

        throttle.recordFailure(PHONE);
        clock.advance(Duration.ofMinutes(1));
        throttle.recordFailure(PHONE);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(2));

        clock.advance(Duration.ofMinutes(2).plus(LoginThrottle.FORGET_AFTER));
        throttle.recordFailure(LAPTOP);
        assertThat(throttle.trackedAddresses()).isEqualTo(1);

        throttle.recordFailure(PHONE);
        assertThat(throttle.remainingLock(PHONE)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("Inventar adreces no omple la memòria: se'n recorden com a molt deu mil")
    void trackedAddressesAreBounded() {
        // Sense límit de tothom, perquè aquí només es mira la memòria.
        LoginThrottle throttle = new LoginThrottle(5, Integer.MAX_VALUE, Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofMinutes(15), clock);

        for (int address = 0; address < LoginThrottle.MAX_TRACKED_ADDRESSES + 500; address++) {
            throttle.recordFailure("10." + (address >> 16 & 255) + "." + (address >> 8 & 255) + "." + (address & 255));
        }

        assertThat(throttle.trackedAddresses()).isEqualTo(LoginThrottle.MAX_TRACKED_ADDRESSES);
    }

    @Test
    @DisplayName("Una IPv6 es compta pel seu bloc /64: canviar-ne els últims dígits no torna a començar")
    void ipv6AddressesCountByTheirBlock() {
        assertThat(LoginThrottle.addressKey("2001:db8:1:2:aaaa::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(LoginThrottle.addressKey("2001:db8:1:2:bbbb:cccc:dddd:eeee")).isEqualTo("2001:db8:1:2::/64");
        assertThat(LoginThrottle.addressKey("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3::/64");
        // Una IPv4 escrita com a IPv6 és la IPv4, i una IPv4 es queda tal com és.
        assertThat(LoginThrottle.addressKey("::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
        assertThat(LoginThrottle.addressKey("192.0.2.1")).isEqualTo("192.0.2.1");
        assertThat(LoginThrottle.addressKey(null)).isEqualTo("desconeguda");

        LoginThrottle throttle = throttle(2);
        throttle.recordFailure("2001:db8:1:2::1");
        throttle.recordFailure("2001:db8:1:2::2");
        assertThat(throttle.isLocked("2001:db8:1:2::3")).isTrue();
        assertThat(throttle.isLocked("2001:db8:1:3::1")).isFalse();
    }

    @Test
    @DisplayName("Un màxim per sota del bloqueig base no l'escurça")
    void maximumBelowTheBaseKeepsTheBase() {
        LoginThrottle throttle = new LoginThrottle(5, 50, Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofMinutes(1), clock);

        assertThat(throttle.lockFor(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(throttle.lockFor(3)).isEqualTo(Duration.ofMinutes(2));
    }
}
