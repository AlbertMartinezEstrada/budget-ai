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
@Table(name = "transfers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "account_origen_id")
    private Account sourceAccount;

    @ManyToOne
    @JoinColumn(name = "account_desti_id")
    private Account destinationAccount;

    @Column(name = "import", nullable = false, precision = 15, scale = 2)
    @JsonProperty("import")
    private BigDecimal amount;

    @Column(name = "data", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data")
    private LocalDate date;

    @Column(name = "descripcio")
    @JsonProperty("descripcio")
    private String description;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
