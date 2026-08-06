package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Column(name = "quantitat_objectiu", nullable = false, precision = 15, scale = 2)
    @JsonProperty("quantitat_objectiu")
    private BigDecimal targetAmount;

    // Sense inicialitzador: vegeu el comentari a Account. Amb un valor per
    // defecte aquí, editar un objectiu esborrava els diners ja estalviats.
    @Column(name = "quantitat_actual", precision = 15, scale = 2)
    @JsonProperty("quantitat_actual")
    private BigDecimal currentAmount;

    @Column(name = "data_objectiu")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data_objectiu")
    private LocalDate targetDate;

    @Column(name = "completat")
    @JsonProperty("completat")
    private Boolean completed;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (currentAmount == null) currentAmount = BigDecimal.ZERO;
        if (completed == null) completed = Boolean.FALSE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @Transient
    @JsonProperty("progres_percentatge")
    public BigDecimal getProgressPercentage() {
        if (targetAmount == null || targetAmount.signum() == 0 || currentAmount == null) {
            return BigDecimal.ZERO;
        }
        return currentAmount
                .multiply(BigDecimal.valueOf(100))
                .divide(targetAmount, 2, RoundingMode.HALF_UP);
    }
}
