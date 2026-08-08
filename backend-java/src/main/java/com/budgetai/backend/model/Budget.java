package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "budgets")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "quantitat_limit", nullable = false, precision = 15, scale = 2)
    @JsonProperty("quantitat_limit")
    private BigDecimal limitAmount;

    /**
     * Percentatge del sou que s'assigna a aquesta categoria.
     *
     * Si està informat, mana sobre quantitat_limit: el sostre del mes es
     * calcula com sou × percentatge ÷ 100, de manera que si el sou canvia, el
     * pressupost s'hi ajusta sol. Si és null, s'aplica quantitat_limit tal com
     * abans.
     *
     * quantitat_limit segueix sent NOT NULL a la base de dades i guarda
     * l'últim import calculat: així un pressupost per percentatge continua
     * tenint una xifra llegible per a qui consulti la taula directament.
     */
    @Column(name = "percentatge", precision = 5, scale = 2)
    @JsonProperty("percentatge")
    private BigDecimal percentage;

    @Column(name = "periode_inici", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("periode_inici")
    private LocalDate periodStart;

    @Column(name = "periode_fi", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("periode_fi")
    private LocalDate periodEnd;

    @Column(name = "actiu")
    @JsonProperty("actiu")
    private Boolean active;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (active == null) active = Boolean.TRUE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Transient
    @JsonProperty("gasto_actual")
    private BigDecimal currentSpent; // Se calculará dinámicamente
}
