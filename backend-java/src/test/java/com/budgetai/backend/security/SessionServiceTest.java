package com.budgetai.backend.security;

import com.budgetai.backend.model.AuthSession;
import com.budgetai.backend.repository.AuthSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * La memòria de SessionService: estalvia anar a la base de dades a cada
 * petició, però no pot deixar passar mai una sessió que s'ha tancat.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final Duration VALIDITY = Duration.ofDays(7);

    @Mock private AuthSessionRepository repository;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        sessions = new SessionService(repository, VALIDITY, clock);
    }

    @Test
    @DisplayName("Una sessió nova dura el mateix que el token i desa el navegador")
    void openSavesTheSession() {
        AuthSession session = sessions.open("Mozilla/5.0 (Macintosh) Safari/605.1.15");

        ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(session);
        assertThat(session.getId()).hasSize(36);
        assertThat(session.getUserAgent()).isEqualTo("Mozilla/5.0 (Macintosh) Safari/605.1.15");
        assertThat(session.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(session.getLastUsedAt()).isEqualTo(clock.instant());
        assertThat(session.getExpiresAt()).isEqualTo(clock.instant().plus(VALIDITY));

        // Cada sessió, el seu id.
        assertThat(sessions.open(null).getId()).isNotEqualTo(session.getId());
    }

    @Test
    @DisplayName("Un navegador que no cap a la columna es retalla, i un de buit no es desa")
    void userAgentIsShortened() {
        assertThat(sessions.open("x".repeat(400)).getUserAgent()).hasSize(SessionService.USER_AGENT_LENGTH);
        assertThat(sessions.open("   ").getUserAgent()).isNull();
    }

    @Test
    @DisplayName("Una sessió recent es dona per bona sense anar a la base de dades")
    void recentSessionIsNotCheckedAgain() {
        String sessionId = sessions.open("navegador").getId();

        clock.advance(Duration.ofSeconds(30));

        assertThat(sessions.isOpen(sessionId)).isTrue();
        verify(repository, never()).touch(any(), any());
    }

    @Test
    @DisplayName("Passat el minut, es torna a mirar a la base de dades i s'hi apunta l'últim ús")
    void sessionIsCheckedAgainAfterTheInterval() {
        String sessionId = sessions.open("navegador").getId();
        clock.advance(SessionService.CHECK_INTERVAL);
        when(repository.touch(sessionId, clock.instant())).thenReturn(1);

        assertThat(sessions.isOpen(sessionId)).isTrue();
        assertThat(sessions.isOpen(sessionId)).isTrue();

        // La segona ja surt de la memòria.
        verify(repository, times(1)).touch(eq(sessionId), any());
    }

    @Test
    @DisplayName("Una sessió que la base de dades no té no serveix, i no es recorda com a bona")
    void unknownSessionIsRejected() {
        when(repository.touch(eq("inventada"), any())).thenReturn(0);

        assertThat(sessions.isOpen("inventada")).isFalse();
        assertThat(sessions.isOpen("inventada")).isFalse();
        verify(repository, times(2)).touch(eq("inventada"), any());
    }

    @Test
    @DisplayName("Tancar una sessió la treu de la memòria a l'acte, sense esperar el minut")
    void closeForgetsTheSessionImmediately() {
        String sessionId = sessions.open("navegador").getId();
        assertThat(sessions.isOpen(sessionId)).isTrue();

        sessions.close(sessionId);
        when(repository.touch(eq(sessionId), any())).thenReturn(0);

        assertThat(sessions.isOpen(sessionId)).isFalse();
        verify(repository).deleteById(sessionId);
    }

    @Test
    @DisplayName("Tancar les altres sessions les oblida totes menys la d'ara")
    void closeAllExceptKeepsOnlyTheCurrentOne() {
        String current = sessions.open("aquest").getId();
        String other = sessions.open("un altre").getId();
        when(repository.deleteAllExcept(current)).thenReturn(1);

        assertThat(sessions.closeAllExcept(current)).isEqualTo(1);
        when(repository.touch(eq(other), any())).thenReturn(0);

        assertThat(sessions.isOpen(current)).isTrue();
        assertThat(sessions.isOpen(other)).isFalse();
        verify(repository, never()).touch(eq(current), any());
    }

    @Test
    @DisplayName("La neteja esborra les caducades a la base de dades")
    void deleteExpiredUsesTheCurrentTime() {
        when(repository.deleteExpired(clock.instant())).thenReturn(3);

        assertThat(sessions.deleteExpired()).isEqualTo(3);
    }
}
