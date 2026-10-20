package com.budgetai.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limitador d'intents de login, per adreça IP i per a tothom.
 *
 * Amb un sol usuari i una contrasenya, el punt d'entrada natural és provar
 * contrasenyes fins a encertar-la. Hi ha dos nivells:
 *
 * - **Per IP.** Uns quants intents fallits seguits des d'una adreça bloquegen
 *   aquella adreça. Cada bloqueig seguit dura el doble que l'anterior (1, 2,
 *   4, 8 minuts…) fins al màxim, i un login correcte des d'aquella adreça ho
 *   reinicia. Abans el comptador era un de sol per a tot el backend: qualsevol
 *   aparell de la wifi podia deixar-te sense entrar a propòsit.
 * - **Per a tothom.** Si entre totes les adreces hi ha molts intents fallits en
 *   poca estona, el login es bloqueja per a tothom, amb el mateix bloqueig que
 *   creix. Només per IP, qui anés canviant d'adreça no xocaria mai amb res.
 *
 * L'adreça és la de la connexió (getRemoteAddr), mai la capçalera
 * X-Forwarded-For: no hi ha cap proxy davant del backend i qualsevol la podria
 * inventar. Les adreces IPv6 es compten per bloc /64, que és el que té una
 * sola connexió: comptades una per una, n'hi hauria prou de canviar-ne els
 * últims dígits per començar de zero.
 *
 * És en memòria. Reiniciar el backend neteja els comptadors, cosa assumible
 * per a una aplicació d'una sola persona. Perquè ningú la pugui omplir
 * inventant adreces, se'n recorden com a molt {@link #MAX_TRACKED_ADDRESSES},
 * i una adreça que fa una hora que no falla ni està bloquejada s'oblida.
 */
@Component
public class LoginThrottle {

    private static final Logger LOG = LoggerFactory.getLogger(LoginThrottle.class);

    /** Quantes adreces es recorden com a molt; les que fa més que no es veuen marxen primer. */
    static final int MAX_TRACKED_ADDRESSES = 10_000;

    /** Sense errors ni bloqueig durant aquesta estona, una adreça s'oblida i el bloqueig torna a començar pel curt. */
    static final Duration FORGET_AFTER = Duration.ofHours(1);

    private final int maxAttemptsPerAddress;
    private final int maxFailuresForEveryone;
    private final Duration failuresWindow;
    private final Duration lockDuration;
    private final Duration maxLockDuration;
    private final Clock clock;

    /** Per adreça, en ordre d'ús: la primera és la que fa més que no es veu. */
    private final LinkedHashMap<String, Lockout> byAddress = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lockout> eldest) {
            return size() > MAX_TRACKED_ADDRESSES;
        }
    };

    /** El bloqueig de tothom, i els errors recents de totes les adreces. */
    private final Lockout everyone = new Lockout();
    private final Deque<Instant> recentFailures = new ArrayDeque<>();

    @Autowired
    public LoginThrottle(
            @Value("${app.auth.max-login-attempts:5}") int maxAttemptsPerAddress,
            @Value("${app.auth.global-max-login-failures:50}") int maxFailuresForEveryone,
            @Value("${app.auth.global-login-failures-window:PT10M}") Duration failuresWindow,
            @Value("${app.auth.login-lock-duration:PT1M}") Duration lockDuration,
            @Value("${app.auth.login-lock-max-duration:PT15M}") Duration maxLockDuration) {
        this(maxAttemptsPerAddress, maxFailuresForEveryone, failuresWindow, lockDuration, maxLockDuration,
                Clock.systemUTC());
    }

    LoginThrottle(int maxAttemptsPerAddress, int maxFailuresForEveryone, Duration failuresWindow,
                  Duration lockDuration, Duration maxLockDuration, Clock clock) {
        this.maxAttemptsPerAddress = maxAttemptsPerAddress;
        this.maxFailuresForEveryone = maxFailuresForEveryone;
        this.failuresWindow = failuresWindow;
        this.lockDuration = lockDuration;
        // Un màxim per sota del bloqueig base no té sentit: mana el base.
        this.maxLockDuration = maxLockDuration.compareTo(lockDuration) < 0 ? lockDuration : maxLockDuration;
        this.clock = clock;
    }

    /** Si el login està tancat per a aquesta adreça, o per a tothom. */
    public synchronized boolean isLocked(String remoteAddress) {
        return !remainingLock(remoteAddress).isZero();
    }

    /** Quant falta perquè aquesta adreça pugui tornar a provar: el més llarg dels dos bloquejos. */
    public synchronized Duration remainingLock(String remoteAddress) {
        Instant now = clock.instant();
        Lockout address = byAddress.get(addressKey(remoteAddress));
        Instant until = address != null && address.lockedUntil.isAfter(everyone.lockedUntil)
                ? address.lockedUntil : everyone.lockedUntil;
        Duration remaining = Duration.between(now, until);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public synchronized void recordFailure(String remoteAddress) {
        Instant now = clock.instant();
        forgetQuietAddresses(now);

        // Al log, perquè es vegi quina adreça veu el backend: amb Docker Desktop
        // pot ser la de Docker per a totes les connexions (AUTENTICACION.md).
        String key = addressKey(remoteAddress);
        LOG.info("Intent de login fallit des de {}", key);
        Lockout address = byAddress.computeIfAbsent(key, unused -> new Lockout());
        address.lastFailure = now;
        if (++address.failures >= maxAttemptsPerAddress) {
            address.lock(now);
            LOG.warn("Login bloquejat per a {} durant {}: {} intents fallits seguits",
                    key, Duration.between(now, address.lockedUntil), maxAttemptsPerAddress);
        }

        recentFailures.addLast(now);
        while (!recentFailures.isEmpty() && !recentFailures.peekFirst().isAfter(now.minus(failuresWindow))) {
            recentFailures.removeFirst();
        }
        if (recentFailures.size() >= maxFailuresForEveryone) {
            everyone.lock(now);
            recentFailures.clear();
            LOG.warn("Login bloquejat per a tothom durant {}: {} intents fallits en {} des de diverses adreces",
                    Duration.between(now, everyone.lockedUntil), maxFailuresForEveryone, failuresWindow);
        }
    }

    /** Un login correcte reinicia la seva adreça, no la resta ni el comptador de tothom. */
    public synchronized void recordSuccess(String remoteAddress) {
        byAddress.remove(addressKey(remoteAddress));
    }

    /** Quantes adreces es recorden ara. Per als tests: la memòria ha de quedar fitada. */
    synchronized int trackedAddresses() {
        return byAddress.size();
    }

    /** El bloqueig número {@code lockNumber} seguit: el base, doblat cada vegada, fins al màxim. */
    Duration lockFor(int lockNumber) {
        Duration duration = lockDuration;
        for (int doubling = 1; doubling < lockNumber && duration.compareTo(maxLockDuration) < 0; doubling++) {
            duration = duration.multipliedBy(2);
        }
        return duration.compareTo(maxLockDuration) > 0 ? maxLockDuration : duration;
    }

    /**
     * Com es compta una adreça: una IPv4 tal com és, i una IPv6 pel seu bloc
     * /64. Una IPv4 escrita com a IPv6 (::ffff:192.0.2.1) és la IPv4.
     */
    static String addressKey(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) return "desconeguda";
        // Només una IPv6 porta «:», i escrita així InetAddress no fa cap consulta DNS.
        if (!remoteAddress.contains(":")) return remoteAddress;
        try {
            byte[] bytes = InetAddress.getByName(remoteAddress).getAddress();
            if (bytes.length == 4) return InetAddress.getByAddress(bytes).getHostAddress();
            StringBuilder prefix = new StringBuilder();
            for (int group = 0; group < 4; group++) {
                prefix.append(Integer.toHexString(((bytes[group * 2] & 0xff) << 8) | (bytes[group * 2 + 1] & 0xff)))
                        .append(':');
            }
            return prefix.append(":/64").toString();
        } catch (UnknownHostException exception) {
            return remoteAddress;
        }
    }

    /** Les adreces que fa més d'una hora que no fallen ni estan bloquejades, fora. */
    private void forgetQuietAddresses(Instant now) {
        Iterator<Lockout> addresses = byAddress.values().iterator();
        while (addresses.hasNext()) {
            Lockout address = addresses.next();
            if (address.isQuiet(now)) addresses.remove();
        }
    }

    /** Els errors seguits i els bloquejos d'una adreça, o de tothom. */
    private final class Lockout {
        private int failures;
        private int consecutiveLocks;
        private Instant lockedUntil = Instant.EPOCH;
        private Instant lastFailure = Instant.EPOCH;

        void lock(Instant now) {
            // Passada una bona estona des de l'últim bloqueig, es torna a començar pel curt.
            if (now.isAfter(lockedUntil.plus(FORGET_AFTER))) consecutiveLocks = 0;
            lockedUntil = now.plus(lockFor(++consecutiveLocks));
            failures = 0;
        }

        boolean isQuiet(Instant now) {
            return !now.isBefore(lockedUntil.plus(FORGET_AFTER)) && !now.isBefore(lastFailure.plus(FORGET_AFTER));
        }
    }
}
