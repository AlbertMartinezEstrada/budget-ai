package com.budgetai.backend.security;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Qui fa la petició i des de quina sessió. Configuració ho necessita per
 * marcar «aquesta sessió» i per tancar totes les altres sense tancar-la.
 *
 * Com que és un AuthenticatedPrincipal, authentication.getName() continua
 * tornant el nom d'usuari.
 */
public record SessionUser(String username, String sessionId) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return username;
    }
}
