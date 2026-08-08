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
    private Boolean active;

    @Column(name = "descripcio")
    @JsonProperty("descripcio")
    private String description;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (active == null) active = Boolean.TRUE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /**
     * Import equivalent mensual, per a la vista de cost de vida.
     *
     * Una assegurança de 600 € l'any costa 50 € al mes encara que el càrrec
     * caigui de cop al març. Aquesta xifra és la que respon a "quant em costa
     * viure", i no depèn de quan es cobri.
     *
     * Criteri de conversió: es passa l'import a base anual i es divideix
     * entre dotze. Per a les freqüències curtes es fa servir l'any real
     * (365 dies, 52 setmanes) i no aproximacions com "30 dies al mes", que
     * deixarien fora cinc dies l'any.
     *
     * S'arrodoneix a dos decimals amb HALF_UP, el mateix criteri que la resta
     * d'imports de l'aplicació.
     */
    @Transient
    @JsonProperty("prorrateig_mensual")
    public BigDecimal getMonthlyAmount() {
        if (amount == null || frequency == null) return BigDecimal.ZERO;

        BigDecimal yearly = switch (frequency.toUpperCase()) {
            case "DIARIA" -> amount.multiply(BigDecimal.valueOf(365));
            case "SETMANAL" -> amount.multiply(BigDecimal.valueOf(52));
            case "MENSUAL" -> amount.multiply(BigDecimal.valueOf(12));
            case "TRIMESTRAL" -> amount.multiply(BigDecimal.valueOf(4));
            case "ANUAL" -> amount;
            // Una freqüència desconeguda no es pot prorratejar; comptar-la com
            // a mensual inventaria una xifra, així que es deixa a zero.
            default -> null;
        };

        if (yearly == null) return BigDecimal.ZERO;
        return yearly.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }
}
