package com.budgetai.backend.integration;

import com.budgetai.backend.model.AuthSession;
import com.budgetai.backend.repository.AuthSessionRepository;
import com.budgetai.backend.security.JwtService;
import com.budgetai.backend.security.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Sortir tanca la sessió de debò.
 *
 * Abans, /auth/logout només demanava al navegador que esborrés la cookie: el
 * token continuava ben signat i sense caducar, i qui se l'hagués copiat podia
 * fer servir l'API set dies més. Aquests tests guarden el token d'abans de
 * sortir i proven de fer-lo servir després.
 */
@AutoConfigureMockMvc
class SessionsIntegrationTest extends AbstractIntegrationTest {

    private static final String MAC_SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Safari/605.1.15";
    private static final String ANDROID_CHROME = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtService jwtService;
    @Autowired private SessionService sessionService;
    @Autowired private AuthSessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        // Les altres classes també entren; aquí es compten les sessions.
        sessionRepository.deleteAll();
    }

    private Cookie login(String userAgent) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .header(HttpHeaders.USER_AGENT, userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test-user", "password", "test-password"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("budget_session");
    }

    @Test
    @DisplayName("Després de sortir, el token d'abans ja no obre res, encara que la signatura i la caducitat siguin bones")
    void logoutClosesTheSessionOnTheServer() throws Exception {
        Cookie session = login(MAC_SAFARI);
        mockMvc.perform(get("/accounts").cookie(session)).andExpect(status().isOk());

        mockMvc.perform(post("/auth/logout").cookie(session)).andExpect(status().isOk());

        // El token en si continua sent vàlid: el que l'ha tancat és la sessió.
        assertThat(jwtService.resolve(session.getValue())).isPresent();
        mockMvc.perform(get("/accounts").cookie(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(sessionRepository.count()).isZero();
    }

    @Test
    @DisplayName("Sortir amb una sessió ja tancada no falla i torna a esborrar la cookie")
    void logoutTwiceStillExpiresTheCookie() throws Exception {
        Cookie session = login(MAC_SAFARI);
        mockMvc.perform(post("/auth/logout").cookie(session)).andExpect(status().isOk());

        Cookie expired = mockMvc.perform(post("/auth/logout").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("budget_session");
        assertThat(expired.getMaxAge()).isZero();
    }

    @Test
    @DisplayName("Sortir d'un navegador no tanca la sessió dels altres")
    void logoutOnlyClosesItsOwnSession() throws Exception {
        Cookie laptop = login(MAC_SAFARI);
        Cookie phone = login(ANDROID_CHROME);

        mockMvc.perform(post("/auth/logout").cookie(laptop)).andExpect(status().isOk());

        mockMvc.perform(get("/accounts").cookie(laptop)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/accounts").cookie(phone)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Configuració veu les sessions obertes, amb el navegador i la d'ara marcada")
    void listsTheOpenSessions() throws Exception {
        login(ANDROID_CHROME);
        Cookie laptop = login(MAC_SAFARI);

        mockMvc.perform(get("/auth/sessions").cookie(laptop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // L'última que s'ha fet servir, primer: és la d'aquesta petició.
                .andExpect(jsonPath("$[0].navegador").value(MAC_SAFARI))
                .andExpect(jsonPath("$[0].actual").value(true))
                .andExpect(jsonPath("$[1].navegador").value(ANDROID_CHROME))
                .andExpect(jsonPath("$[1].actual").value(false))
                .andExpect(jsonPath("$[0].creada").isString())
                .andExpect(jsonPath("$[0].darrer_us").isString())
                .andExpect(jsonPath("$[0].caduca").isString())
                // L'id de la sessió no surt de l'API.
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @Test
    @DisplayName("«Tancar les altres sessions» deixa només la d'ara")
    void closeOtherSessionsLeavesOnlyTheCurrentOne() throws Exception {
        Cookie phone = login(ANDROID_CHROME);
        Cookie tablet = login(ANDROID_CHROME);
        Cookie laptop = login(MAC_SAFARI);

        mockMvc.perform(delete("/auth/sessions/others").cookie(laptop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tancades").value(2));

        mockMvc.perform(get("/accounts").cookie(phone)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/accounts").cookie(tablet)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/accounts").cookie(laptop)).andExpect(status().isOk());
        mockMvc.perform(get("/auth/sessions").cookie(laptop))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actual").value(true));
    }

    @Test
    @DisplayName("Un token ben signat d'una sessió que no existeix no serveix")
    void signedTokenWithoutSessionIsRejected() throws Exception {
        String token = jwtService.issueToken("test-user", UUID.randomUUID().toString());

        mockMvc.perform(get("/accounts").cookie(new Cookie("budget_session", token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La neteja esborra les sessions caducades i deixa les obertes")
    void expiredSessionsAreDeleted() throws Exception {
        Cookie open = login(MAC_SAFARI);
        Instant longAgo = Instant.now().minus(Duration.ofDays(30));
        sessionRepository.save(new AuthSession(UUID.randomUUID().toString(), ANDROID_CHROME,
                longAgo, longAgo.plus(Duration.ofDays(7))));

        assertThat(sessionService.deleteExpired()).isEqualTo(1);

        assertThat(sessionRepository.count()).isEqualTo(1);
        mockMvc.perform(get("/accounts").cookie(open)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sense sessió, la llista i el «tancar les altres» responen 401")
    void sessionEndpointsNeedASession() throws Exception {
        mockMvc.perform(get("/auth/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/auth/sessions/others")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }
}
