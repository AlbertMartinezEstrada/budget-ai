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
@Table(name = "financial_goals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinancialGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom", nullable = false)
    @JsonProperty("nom")
    private String name;

    @Column(name = "descripcio")
    @JsonProperty("descripcio")
    private String description;

    @Column(name = "quantitat_objectiu", nullable = false)
    @JsonProperty("quantitat_objectiu")
    private Double targetAmount;

    @Column(name = "quantitat_actual")
    @JsonProperty("quantitat_actual")
    private Double currentAmount = 0.0;

    @Column(name = "data_objectiu")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data_objectiu")
    private LocalDate targetDate;

    @Column(name = "completat")
    @JsonProperty("completat")
    private Boolean completed = false;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    @JsonProperty("progres_percentatge")
    public Double getProgressPercentage() {
        if (targetAmount == null || targetAmount == 0) return 0.0;
        return (currentAmount / targetAmount) * 100;
    }
}
