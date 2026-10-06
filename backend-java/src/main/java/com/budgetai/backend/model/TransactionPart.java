package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Un tros d'un moviment que compta pel seu compte.
 *
 * Una mateixa línia de l'extracte pot ser diverses coses alhora: la
 * transferència a Trade Republic porta estalvi, la devolució d'un préstec, el
 * que es guarda per pagar l'assegurança i uns diners que no han de comptar.
 * Amb una sola categoria, el pressupost la comptava sencera en un sol lloc.
 *
 * El moviment no es toca: el saldo es mou una vegada, pel total, i el hash
 * segueix identificant la línia de l'extracte. Les parts només diuen com es
 * reparteix aquest total. Quan un moviment en té, **manen les parts**: la
 * categoria, la marca d'exclòs i el deute del moviment deixen de comptar.
 */
@Entity
@Table(name = "transaction_parts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Exclòs del JSON, d'equals, de hashCode i de toString: la part sempre
    // viatja dins del seu moviment, i incloure'l faria un cercle.
    @ManyToOne(optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Transaction transaction;

    @Column(name = "import", nullable = false, precision = 15, scale = 2)
    @JsonProperty("import")
    private BigDecimal amount;

    /** Sempre una fulla, com qualsevol lloc on van diners: un grup es tornaria a sumar pels fills. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "exclos_pressupost", nullable = false)
    @JsonProperty("exclos_pressupost")
    private Boolean excludedFromBudget;

    @ManyToOne
    @JoinColumn(name = "deute_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Debt debt;

    @Column(name = "descripcio")
    @JsonProperty("descripcio")
    private String description;

    @PrePersist
    void applyDefaults() {
        if (excludedFromBudget == null) excludedFromBudget = Boolean.FALSE;
    }

    /** @JsonIgnore: el camp ja surt com a "exclos_pressupost". */
    @Transient
    @JsonIgnore
    public boolean isExcludedFromBudget() {
        return Boolean.TRUE.equals(excludedFromBudget);
    }

    /** Com al moviment: al JSON, només l'identificador del deute. */
    @JsonProperty("deute_id")
    public Long getDebtId() {
        return debt != null ? debt.getId() : null;
    }

    /** Un negatiu vol dir "sense deute", el mateix conveni que al moviment. */
    @JsonProperty("deute_id")
    public void setDebtId(Long debtId) {
        if (debtId == null) {
            this.debt = null;
            return;
        }
        Debt reference = new Debt();
        reference.setId(debtId);
        this.debt = reference;
    }
}
