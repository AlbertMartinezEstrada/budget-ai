package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Regla que s'aplica sola en importar un extracte.
 *
 * El cas que la va fer falta: totes les entrades de Revolut que diuen "*9469"
 * venen del compte principal. Són diners que ja es van comptar en sortir
 * d'allà, i marcar-les a mà una per una a cada importació és feina repetida i
 * fàcil d'oblidar-se'n.
 *
 * Mira el concepte original del moviment i, si hi troba el seu patró, li posa
 * la marca de "no comptar al pressupost" i, si en té, la categoria.
 *
 * No decideix res irreversible: s'aplica a la pantalla de revisió, on encara
 * es veu tot i es pot desmarcar abans de confirmar.
 */
@Entity
@Table(name = "import_rules")
@Data
@NoArgsConstructor
public class ImportRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Text a trobar dins del concepte. No distingeix majúscules. */
    @Column(name = "patro", nullable = false)
    @JsonProperty("patro")
    private String pattern;

    @Column(name = "marca_exclos")
    @JsonProperty("marca_exclos")
    private Boolean marksExcluded;

    /** Categoria a assignar. Null vol dir que la regla no la toca. */
    @Column(name = "categoria")
    @JsonProperty("categoria")
    private String categoryName;

    /**
     * Si el moviment és un traspàs, el compte de l'altre costat: «REVOLUT» →
     * Revolut, «TRADE REPUBLIC» → Trade Republic. Null vol dir que no ho és.
     *
     * Quan n'hi ha, "marca_exclos" no s'aplica: si un traspàs compta o no ho
     * decideix el tipus dels dos comptes, no la regla.
     */
    @ManyToOne
    @JoinColumn(name = "compte_traspas_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account transferAccount;

    @Column(name = "notes")
    @JsonProperty("notes")
    private String notes;

    @Column(name = "activa")
    @JsonProperty("activa")
    private Boolean active;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("compte_traspas_id")
    public Long getTransferAccountId() {
        return transferAccount != null ? transferAccount.getId() : null;
    }

    /** Només l'identificador; el servei el resol abans de desar. */
    @JsonProperty("compte_traspas_id")
    public void setTransferAccountId(Long accountId) {
        if (accountId == null) {
            this.transferAccount = null;
            return;
        }
        Account reference = new Account();
        reference.setId(accountId);
        this.transferAccount = reference;
    }

    @PrePersist
    void applyDefaults() {
        if (marksExcluded == null) marksExcluded = Boolean.TRUE;
        if (active == null) active = Boolean.TRUE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
