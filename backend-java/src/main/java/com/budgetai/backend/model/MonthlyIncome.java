package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Sou d'un mes concret, quan no és el de sempre.
 *
 * El sou base viu a Settings i serveix per a tots els mesos. Aquesta taula
 * només guarda les excepcions: una paga extra, un mes amb menys hores, un
 * canvi de feina a mitjan any. Si un mes no hi surt, s'aplica el base.
 *
 * El període es desa com a text "2026-03" i no com a dos enters. "any" és una
 * paraula reservada de SQL, i un any i un mes per separat obliguen a repetir
 * la parella a totes les consultes i a vigilar que no es descompensin.
 * A més, coincideix amb el format que ja retorna AnalyticsService.
 */
@Entity
@Table(name = "monthly_income")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyIncome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Format "YYYY-MM". */
    @Column(name = "periode", nullable = false, unique = true, length = 7)
    @JsonProperty("periode")
    private String period;

    @Column(name = "import", nullable = false, precision = 15, scale = 2)
    @JsonProperty("import")
    private BigDecimal amount;

    @Column(name = "notes")
    @JsonProperty("notes")
    private String notes;

    public MonthlyIncome(String period, BigDecimal amount) {
        this.period = period;
        this.amount = amount;
    }
}
