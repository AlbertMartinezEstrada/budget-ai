package com.budgetai.backend.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Optional;

/**
 * La sessió viatja en una cookie httpOnly, no en localStorage: així un XSS
 * no pot llegir-la ni robar-la des de JavaScript.
 *
 * SameSite=Strict evita que la cookie s'enviï en peticions iniciades des
 * d'un altre lloc, que és la protecció contra CSRF. El frontend
 * (localhost:3000) i el backend (localhost:8000) es consideren el mateix
 * lloc perquè el port no forma part del "site", així que la cookie sí que
 * viatja en les crides normals de l'aplicació.
 */
public final class AuthCookies {

    public static final String COOKIE_NAME = "budget_session";

    private AuthCookies() {}

    public static String build(String token, long maxAgeSeconds, boolean secure) {
        StringBuilder cookie = new StringBuilder()
                .append(COOKIE_NAME).append('=').append(token)
                .append("; Path=/")
                .append("; HttpOnly")
                .append("; SameSite=Strict")
                .append("; Max-Age=").append(maxAgeSeconds);

        // Secure només quan es serveix per HTTPS: si es posés sempre, el
        // navegador descartaria la cookie en desenvolupament sobre HTTP.
        if (secure) cookie.append("; Secure");

        return cookie.toString();
    }

    public static String expired(boolean secure) {
        return build("", 0, secure);
    }

    public static Optional<String> readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();

        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
