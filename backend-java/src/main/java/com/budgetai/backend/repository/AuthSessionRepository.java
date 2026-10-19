package com.budgetai.backend.repository;

import com.budgetai.backend.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Les escriptures porten @Transactional aquí i no al servei: així cadascuna
 * queda desada en tornar, i SessionService pot buidar la seva memòria just
 * després sabent que la base de dades ja ho diu.
 */
@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    List<AuthSession> findByExpiresAtAfterOrderByLastUsedAtDesc(Instant now);

    /** Apunta l'últim ús si la sessió encara hi és i no ha caducat. Torna quantes files ha tocat: 0 o 1. */
    @Transactional
    @Modifying
    @Query("UPDATE AuthSession session SET session.lastUsedAt = :now WHERE session.id = :id AND session.expiresAt > :now")
    int touch(@Param("id") String id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("DELETE FROM AuthSession session WHERE session.id <> :id")
    int deleteAllExcept(@Param("id") String id);

    @Transactional
    @Modifying
    @Query("DELETE FROM AuthSession session WHERE session.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
