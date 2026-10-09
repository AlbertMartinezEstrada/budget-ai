package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un rebut que s'ha tret del calendari d'un deute a quotes.
 *
 * De dues maneres, segons el descompte:
 *
 * - **Saltat** (descompte zero): aquell mes no toca pagar, però el que es deu
 *   no canvia. El pla s'allarga un rebut pel final. Serveix, per exemple, si el
 *   calendari començava un mes abans del que tocava.
 * - **Descomptat**: el rebut desapareix i el seu import es resta del deute. És
 *   una rebaixa, o una part de la compra que s'ha tornat.
 *
 * El rebut s'identifica pel seu dia, però es compara pel període: si després
 * es canvia el dia de la primera quota del 8 al 10, el rebut d'octubre
 * continua sent el d'octubre.
 */
@Entity
@Table(name = "debt_removed_receipts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtRemovedReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "deute_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Debt debt;

    /** El dia del rebut, tal com sortia al calendari. */
    @Column(name = "data", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data")
    private LocalDate date;

    /** El que es resta del deute. Zero si només s'ha saltat. */
    @Column(name = "descompte", nullable = false, precision = 15, scale = 2)
    @JsonProperty("descompte")
    private BigDecimal discount;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (discount == null) discount = BigDecimal.ZERO;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
