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
@Table(name = "recurring_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecurringTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom", nullable = false)
    @JsonProperty("nom")
    private String name;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "import", nullable = false, precision = 15, scale = 2)
    @JsonProperty("import")
    private BigDecimal amount;

    @Column(name = "tipus", nullable = false)
    @JsonProperty("tipus")
    private String type; // EXPENSE, INCOME

    @Column(name = "frequencia", nullable = false)
    @JsonProperty("frequencia")
    private String frequency; // DIARIA, SETMANAL, MENSUAL, TRIMESTRAL, ANUAL

    @Column(name = "proxima_data", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("proxima_data")
    private LocalDate nextDate;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "activa")
    @JsonProperty("activa")
    private Boolean active = true;

    @Column(name = "descripcio")
    @JsonProperty("descripcio")
    private String description;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
