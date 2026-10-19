package com.budgetai.backend.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Una sessió oberta: cada vegada que s'entra, una fila.
 *
 * El token de la cookie porta l'id d'aquesta fila (jti). Sortir esborra la
 * fila, i a partir d'aquí el token ja no obre res encara que la signatura i la
 * caducitat siguin bones: abans, sortir només esborrava la cookie d'aquell
 * navegador, i una còpia del token continuava servint set dies.
 *
 * Les hores són Instant, no LocalDateTime: Configuració les ensenya amb hora,
 * i el backend (en UTC dins de Docker) i el navegador no són a la mateixa zona.
 */
@Entity
@Table(name = "sessions")
public class AuthSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AuthSession() {}

    public AuthSession(String id, String userAgent, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
