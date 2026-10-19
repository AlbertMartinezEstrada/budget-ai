package com.budgetai.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Llegeix la cookie de sessió i, si el token és vàlid i la seva sessió encara
 * és oberta, marca la petició com a autenticada. Si no, no fa res: la cadena de
 * seguretat s'encarrega de rebutjar-la amb un 401.
 *
 * La sessió es mira a part perquè un token tancat continua ben signat i sense
 * caducar: només SessionService sap que s'ha sortit.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionService sessions;

    public JwtAuthenticationFilter(JwtService jwtService, SessionService sessions) {
        this.jwtService = jwtService;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthCookies.readToken(request)
                    .flatMap(jwtService::resolve)
                    .filter(token -> sessions.isOpen(token.sessionId()))
                    .ifPresent(token -> {
                        var authentication = new UsernamePasswordAuthenticationToken(
                                new SessionUser(token.username(), token.sessionId()),
                                null, AuthorityUtils.createAuthorityList("ROLE_USER"));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }

        filterChain.doFilter(request, response);
    }
}
