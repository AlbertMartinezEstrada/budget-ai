package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom", nullable = false)
    @JsonProperty("nom")
    private String name;

    @Column(name = "tipus")
    @JsonProperty("tipus")
    private String type; // CORRIENTE, AHORRO, EFECTIVO, TARJETA, INVERSIONES

    // Els valors per defecte s'apliquen en desar (@PrePersist), no com a
    // inicialitzadors de camp. Amb inicialitzadors, un objecte construït per
    // Jackson a partir d'un cos parcial arriba amb el valor per defecte i no
    // amb null, així que és impossible distingir "no m'han enviat aquest camp"
    // de "me'l volen posar a zero": una actualització parcial acabava pisant
    // el saldo i la divisa que ja hi havia desats.
    @Column(name = "saldo_actual", precision = 15, scale = 2)
    @JsonProperty("saldo_actual")
    private BigDecimal currentBalance;

    @Column(name = "moneda")
    @JsonProperty("moneda")
    private String currency;

    @Column(name = "activa")
    @JsonProperty("activa")
    private Boolean active;

    @Column(name = "color")
    @JsonProperty("color")
    private String color;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (currentBalance == null) currentBalance = BigDecimal.ZERO;
        if (currency == null) currency = "EUR";
        if (active == null) active = Boolean.TRUE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
