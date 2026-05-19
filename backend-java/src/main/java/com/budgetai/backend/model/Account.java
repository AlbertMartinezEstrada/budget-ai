package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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

    @Column(name = "saldo_actual")
    @JsonProperty("saldo_actual")
    private Double currentBalance = 0.0;

    @Column(name = "moneda")
    @JsonProperty("moneda")
    private String currency = "EUR";

    @Column(name = "activa")
    @JsonProperty("activa")
    private Boolean active = true;

    @Column(name = "color")
    @JsonProperty("color")
    private String color;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
