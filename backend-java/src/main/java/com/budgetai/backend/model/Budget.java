package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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

    @Column(name = "quantitat_limit", nullable = false)
    @JsonProperty("quantitat_limit")
    private Double limitAmount;

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
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    @JsonProperty("gasto_actual")
    private Double currentSpent; // Se calculará dinámicamente
}
