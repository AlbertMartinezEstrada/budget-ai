package com.budgetai.backend.security;

import com.budgetai.backend.model.AuthSession;
import com.budgetai.backend.repository.AuthSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Les sessions obertes, desades a la taula sessions.
 *
 * Abans no se'n desava cap: el token de la cookie era tota la sessió, i sortir
 * només demanava al navegador que l'esborrés. Una còpia del token (d'un
 * ordinador compartit, d'una còpia de seguretat del navegador) continuava
 * obrint l'API fins que caducava. Ara cada token porta l'id d'una fila, i
 * sortir l'esborra.
 *
 * Cada petició pregunta si la seva sessió és oberta. Per no anar a la base de
 * dades cada vegada, una sessió confirmada es dona per bona durant un minut, i
 * de passada s'hi apunta l'últim ús. Sortir i tancar les altres sessions passen
 * per aquí i buiden aquesta memòria a l'acte: el token deixa de servir a la
 * petició següent, no al cap d'un minut. Només un canvi fet directament a la
 * base de dades (esborrar files a mà, restaurar una còpia) pot trigar aquest
 * minut a notar-se.
 */
@Service
public class SessionService {

    /** Quanta estona es dona per bona una sessió confirmada sense tornar-ho a mirar. */
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);

    /** La mida de la columna user_agent. */
    static final int USER_AGENT_LENGTH = 255;

    private final AuthSessionRepository repository;
    private final Duration validity;
    private final Clock clock;

    /** Sessió → quan es va confirmar a la base de dades per última vegada. */
    private final Map<String, Instant> confirmedAt = new ConcurrentHashMap<>();

    /**
     * Confirmar (llegir la base de dades i apuntar-ho a la memòria) i tancar
     * (esborrar i treure-ho de la memòria) no es poden encavalcar: una
     * confirmació que hagués llegit la fila just abans d'esborrar-la la tornaria
     * a posar a la memòria, i el token serviria un minut més.
     */
    private final Object lock = new Object();

    @Autowired
    public SessionService(AuthSessionRepository repository,
                          @Value("${app.auth.session-duration}") Duration validity) {
        this(repository, validity, Clock.systemUTC());
    }

    SessionService(AuthSessionRepository repository, Duration validity, Clock clock) {
        this.repository = repository;
        this.validity = validity;
        this.clock = clock;
    }

    /** Obre una sessió nova, que dura el mateix que el token. */
    public AuthSession open(String userAgent) {
        Instant now = clock.instant();
        AuthSession session = new AuthSession(UUID.randomUUID().toString(), shorten(userAgent), now, now.plus(validity));
        repository.save(session);
        confirmedAt.put(session.getId(), now);
        return session;
    }

    /** Si la sessió és oberta: existeix i no ha caducat. */
    public boolean isOpen(String sessionId) {
        Instant now = clock.instant();
        Instant lastCheck = confirmedAt.get(sessionId);
        if (lastCheck != null && now.isBefore(lastCheck.plus(CHECK_INTERVAL))) return true;

        synchronized (lock) {
            if (repository.touch(sessionId, now) == 0) {
                confirmedAt.remove(sessionId);
                return false;
            }
            confirmedAt.put(sessionId, now);
            return true;
        }
    }

    /** Tanca la sessió: el seu token ja no obre res. */
    public void close(String sessionId) {
        synchronized (lock) {
            repository.deleteById(sessionId);
            confirmedAt.remove(sessionId);
        }
    }

    /** Tanca totes les sessions menys aquesta. Torna quantes n'ha tancat. */
    public int closeAllExcept(String currentSessionId) {
        synchronized (lock) {
            int closed = repository.deleteAllExcept(currentSessionId);
            confirmedAt.keySet().removeIf(sessionId -> !sessionId.equals(currentSessionId));
            return closed;
        }
    }

    /** Les que encara serveixen, l'última que s'ha fet servir primer. */
    public List<AuthSession> openSessions() {
        return repository.findByExpiresAtAfterOrderByLastUsedAtDesc(clock.instant());
    }

    /**
     * Les caducades no obren res (el token també ha caducat), però sense això
     * la taula creixeria amb cada entrada. Cada hora, i també en arrencar.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public int deleteExpired() {
        Instant now = clock.instant();
        confirmedAt.values().removeIf(lastCheck -> !now.isBefore(lastCheck.plus(CHECK_INTERVAL)));
        return repository.deleteExpired(now);
    }

    private static String shorten(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return null;
        return userAgent.length() > USER_AGENT_LENGTH ? userAgent.substring(0, USER_AGENT_LENGTH) : userAgent;
    }
}
