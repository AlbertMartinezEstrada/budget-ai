package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Diners deixats: els que dec o els que em deuen.
 *
 * No és ni un ingrés ni una despesa. Quan em deixen 1.000 €, el saldo puja
 * 1.000 però jo no soc més ric: els dec. Aquesta taula només porta el compte
 * de qui deu què i com s'ha acordat tornar-ho.
 *
 * Què compta al pressupost ho decideixen els moviments, com sempre: la
 * categoria i la marca d'exclòs. Un moviment es vincula al deute amb
 * `transactions.deute_id`, i d'aquí surt el que s'ha retornat. No hi ha taula
 * de pagaments a part: duplicaria cada línia de l'extracte i les dues còpies
 * acabarien dient coses diferents.
 */
@Entity
@Table(name = "debts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Debt {

    /** M'han deixat diners: els he de tornar. */
    public static final String I_OWE = "DEC";
    /** He deixat diners: me'ls han de tornar. */
    public static final String OWED_TO_ME = "EM_DEUEN";

    /** Sense calendari: es va tornant quan es pot. */
    public static final String PLAN_FREE = "LLIURE";
    /** Tot de cop, un dia concret. */
    public static final String PLAN_SINGLE = "UNIC";
    /** Una quota fixa cada setmana, mes o trimestre fins a saldar-lo. */
    public static final String PLAN_INSTALLMENTS = "QUOTES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A qui o per a què: "Pares, entrada del pis", "Joan, sopar Lisboa". */
    @Column(name = "nom", nullable = false)
    @JsonProperty("nom")
    private String name;

    @Column(name = "direccio", nullable = false)
    @JsonProperty("direccio")
    private String direction;

    /** El que es va deixar. El que queda per tornar es calcula, no es desa. */
    @Column(name = "import", nullable = false, precision = 15, scale = 2)
    @JsonProperty("import")
    private BigDecimal amount;

    @Column(name = "data", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data")
    private LocalDate date;

    @Column(name = "forma_retorn", nullable = false)
    @JsonProperty("forma_retorn")
    private String repaymentPlan;

    /** Import de cada pagament. Només a QUOTES; l'últim és el que falti. */
    @Column(name = "quota", precision = 15, scale = 2)
    @JsonProperty("quota")
    private BigDecimal installment;

    /** SETMANAL, MENSUAL o TRIMESTRAL. Només a QUOTES. */
    @Column(name = "frequencia")
    @JsonProperty("frequencia")
    private String frequency;

    /** A UNIC, el dia que es torna tot; a QUOTES, el dia de la primera. */
    @Column(name = "data_primer_pagament")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data_primer_pagament")
    private LocalDate firstPaymentDate;

    /**
     * On es reserva al pressupost la quota d'un deute que dec.
     *
     * Amb la quota pactada, cada mes ja se sap quants diners estan compromesos:
     * reservar-los a la fulla evita que el pressupost els doni per lliures.
     * Als deutes que em deuen no s'hi reserva res: uns diners que encara no han
     * arribat no han d'eixamplar el que es reparteix.
     */
    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "notes")
    @JsonProperty("notes")
    private String notes;

    @Column(name = "created_at", updatable = false)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    // Tot el que ve a continuació ho calcula DebtService a partir dels
    // moviments vinculats. Són de només lectura: si arribessin en una petició
    // no voldrien dir res, perquè el que s'ha retornat ho diuen els moviments.

    @Transient
    @JsonProperty(value = "retornat", access = JsonProperty.Access.READ_ONLY)
    private BigDecimal repaid;

    /**
     * El que s'ha rebaixat del deute descomptant rebuts. Es resta del que es
     * deu, però "import" continua sent el que es va deixar.
     */
    @Transient
    @JsonProperty(value = "descomptat", access = JsonProperty.Access.READ_ONLY)
    private BigDecimal discounted;

    /**
     * El que hauria d'estar retornat i encara no ho està: el que falta dels
     * rebuts dels mesos que ja s'han acabat.
     */
    @Transient
    @JsonProperty(value = "endarrerit", access = JsonProperty.Access.READ_ONLY)
    private BigDecimal overdue;

    /** El primer rebut que encara no està pagat, pel que en falta. */
    @Transient
    @JsonProperty(value = "proper_pagament", access = JsonProperty.Access.READ_ONLY)
    private Installment nextPayment;

    @Transient
    @JsonProperty(value = "calendari", access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Installment> schedule;

    /** Els moviments i les parts vinculats, per l'import que toca al deute. */
    @Transient
    @JsonProperty(value = "moviments", access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Movement> movements;

    @PrePersist
    void applyDefaults() {
        if (repaymentPlan == null) repaymentPlan = PLAN_FREE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @JsonProperty("pendent")
    public BigDecimal getPending() {
        if (amount == null) return null;
        BigDecimal returned = repaid != null ? repaid : BigDecimal.ZERO;
        BigDecimal rebate = discounted != null ? discounted : BigDecimal.ZERO;
        return amount.subtract(rebate).subtract(returned).max(BigDecimal.ZERO);
    }

    @JsonProperty("saldat")
    public boolean isSettled() {
        BigDecimal pending = getPending();
        return pending != null && pending.signum() == 0;
    }

    /**
     * Qui el té al seu compte: les devolucions d'un deute que dec són sortides.
     *
     * @JsonIgnore perquè el JSON ja porta "direccio": sense això, Jackson hi
     * afegiria un "owedByMe" duplicat.
     */
    @JsonIgnore
    public boolean isOwedByMe() {
        return I_OWE.equals(direction);
    }

    /**
     * Uns diners vinculats al deute: un moviment sencer o una part d'un de
     * dividit.
     *
     * Els noms del JSON són els d'un moviment perquè la pestanya els pinti
     * igual. D'una part, "cost" és l'import de la part i no el del moviment: és
     * el que compta per al deute.
     *
     * @param transactionId el moviment d'on surt, per poder-lo trobar
     * @param part si és només un tros del moviment
     * @param receipt el dia del rebut on compta; null si no en paga cap (el
     *                préstec mateix, o uns diners de més quan ja estava saldat)
     * @param receiptChosen si el rebut el va triar l'usuari; si no, és el que tocava
     */
    public record Movement(
            @JsonProperty("id") Long transactionId,
            @JsonProperty("data") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @JsonProperty("type") String type,
            @JsonProperty("cost") BigDecimal amount,
            @JsonProperty("empresa") String company,
            @JsonProperty("descripcio_curta") String description,
            @JsonProperty("es_part") boolean part,
            @JsonProperty("rebut") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate receipt,
            @JsonProperty("rebut_triat") boolean receiptChosen) {
    }

    /**
     * Un rebut del calendari de retorn.
     *
     * @param amount    el que val el rebut. D'un de descomptat, el que s'ha
     *                  rebaixat; d'un de saltat, zero.
     * @param paid      el que hi ha arribat. Pot passar del que val: el que
     *                  sobra treu rebuts del final.
     * @param status    PAGAT, PARCIAL, TOCA (és el del període en curs i encara
     *                  no s'ha pagat), PENDENT, ENDARRERIT (el seu període ja
     *                  s'ha acabat i no està pagat), SALTAT o DESCOMPTAT.
     * @param removable si es pot treure del calendari: no, si algun pagament
     *                  l'ha triat a ell, ni si ja està tret.
     */
    public record Installment(
            @JsonProperty("data") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @JsonProperty("import") BigDecimal amount,
            @JsonProperty("pagat") BigDecimal paid,
            @JsonProperty("estat") String status,
            @JsonProperty("eliminable") boolean removable) {
    }
}
