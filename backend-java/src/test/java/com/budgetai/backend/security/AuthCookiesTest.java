package com.budgetai.backend.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookiesTest {

    @Test
    @DisplayName("La cookie de sessió és httpOnly i SameSite=Strict")
    void cookieCarriesTheProtections() {
        String cookie = AuthCookies.build("un-token", 604800, false);

        // httpOnly: un XSS no la pot llegir des de JavaScript.
        assertThat(cookie).contains("HttpOnly");
        // SameSite=Strict: no s'envia en peticions iniciades des d'un altre
        // lloc, que és el que protegeix contra CSRF.
        assertThat(cookie).contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/");
        assertThat(cookie).contains("Max-Age=604800");
    }

    @Test
    @DisplayName("Secure només s'afegeix quan es demana, perquè en local es fa servir HTTP")
    void secureIsOptional() {
        assertThat(AuthCookies.build("t", 60, false)).doesNotContain("Secure");
        assertThat(AuthCookies.build("t", 60, true)).contains("Secure");
    }

    @Test
    @DisplayName("La cookie de tancament de sessió caduca immediatament")
    void expiredCookieHasZeroMaxAge() {
        assertThat(AuthCookies.expired(false)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Es llegeix el token de la petició")
    void readsTokenFromRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("altra", "x"), new Cookie(AuthCookies.COOKIE_NAME, "el-token"));

        assertThat(AuthCookies.readToken(request)).contains("el-token");
    }

    @Test
    @DisplayName("Sense cookies, o amb la cookie buida, no hi ha token")
    void missingCookieYieldsEmpty() {
        assertThat(AuthCookies.readToken(new MockHttpServletRequest())).isEmpty();

        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.setCookies(new Cookie(AuthCookies.COOKIE_NAME, ""));
        assertThat(AuthCookies.readToken(blank)).isEmpty();
    }
}
