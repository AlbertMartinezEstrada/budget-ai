package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("data")
    private LocalDate date;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "empresa")
    private String companyName;

    @Column(name = "categoria")
    private String categoryName;

    @Column(name = "descripcio_curta")
    @JsonProperty("descripcio_curta")
    private String shortDescription;

    @Column(name = "import", precision = 15, scale = 2)
    @JsonProperty("cost")
    private BigDecimal amount;

    @Column(name = "saldo_resultant", precision = 15, scale = 2)
    @JsonProperty("saldo")
    private BigDecimal balance;

    @Column(name = "tipus")
    private String type; // EXPENSE, INCOME, TRANSFER

    @Column(name = "concepte_original")
    @JsonProperty("concepte_original")
    private String originalConcept;

    /**
     * Diners que no compten al pressupost: no són ni despesa ni ingrés.
     *
     * Un traspàs entre comptes del dia a dia (compte_contrapart_id) es desa
     * així: els diners només canvien de lloc, i el que compta és el que es
     * paga des de l'altre compte. Sense això, 100 € passats a Revolut i
     * gastats allà sortien com a 200 € de despesa, i l'entrada a Revolut a més
     * inflava el bot a repartir.
     *
     * Es diu "exclòs del pressupost" i no "és traspàs" perquè també serveix
     * per a coses que no són cap traspàs i tampoc han de comptar.
     *
     * El saldo sí que es mou igualment: el moviment ha passat de debò.
     */
    @Column(name = "exclos_pressupost")
    @JsonProperty("exclos_pressupost")
    private Boolean excludedFromBudget;

    /**
     * El deute que mou aquest moviment: una devolució, o l'entrada o la
     * sortida del préstec mateix.
     *
     * És independent de la categoria. Si un amic em paga el sopar i li torno
     * per Bizum, aquell Bizum és una despesa de "Bars i restaurants" i alhora
     * salda el deute: el vincle diu quant falta per tornar, la categoria diu
     * com compta al pressupost.
     *
     * Al JSON surt només l'identificador, com a "deute_id": el deute sencer ja
     * porta la llista dels seus moviments, i incloure'l aquí faria un cercle.
     * Exclòs d'equals, hashCode i toString pel mateix motiu.
     */
    @ManyToOne
    @JoinColumn(name = "deute_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Debt debt;

    /**
     * El rebut del deute que paga aquest moviment, pel dia del rebut.
     *
     * Null vol dir "el que toca": el primer rebut que encara no està pagat. Es
     * tria quan el pagament no és el del rebut que tocaria, per exemple el que
     * es fa a l'octubre per pagar el rebut d'octubre quan el de setembre no
     * tocava. Sense deute no vol dir res: el controlador el buida.
     *
     * Les parts d'un moviment dividit no en porten: paguen sempre el que toca.
     */
    @Column(name = "deute_rebut")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonProperty("deute_rebut")
    private LocalDate debtReceipt;

    /**
     * Les parts en què està dividit, si n'hi ha. Quan n'hi ha, manen elles:
     * la categoria, la marca d'exclòs i el deute del moviment deixen de
     * comptar.
     *
     * No és una relació de JPA: el pressupost llegeix tots els moviments de
     * cop, i una col·lecció per moviment faria una consulta per cada un. Les
     * hi posa qui les necessita ensenyar.
     *
     * Es llegeixen en importar un extracte i en l'alta manual, on un moviment
     * es pot dividir abans de desar-lo. L'edició les rebutja: per a un moviment
     * ja desat hi ha /gastos/{id}/parts.
     */
    @Transient
    @JsonProperty("parts")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TransactionPart> parts;

    /**
     * L'altre compte propi d'un traspàs: on van a parar els diners que surten,
     * o d'on venen els que entren. Null vol dir que no és cap traspàs.
     *
     * Un traspàs no és ni despesa ni ingrés: els diners continuen sent meus.
     * Entre comptes del dia a dia (Principal → Revolut) no compta al
     * pressupost, i el que es paga des de Revolut compta a la seva categoria.
     * Cap a un compte d'estalvi (Trade Republic) compta com a estalvi.
     *
     * Mou el saldo dels dos comptes, així que el moviment de l'altre extracte,
     * si s'importa, és el mateix diner: la importació el reconeix i no el torna
     * a desar.
     *
     * Al JSON surt només l'identificador, com deute_id.
     */
    @ManyToOne
    @JoinColumn(name = "compte_contrapart_id")
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account counterpartAccount;

    /**
     * En revisar un extracte, el traspàs ja desat del qual aquesta línia és
     * l'altra pota. No es desa: només serveix perquè la pantalla la desmarqui.
     */
    @Transient
    @JsonProperty(value = "traspas_registrat", access = JsonProperty.Access.READ_ONLY)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Long registeredTransferId;

    @Column(name = "compte_nom")
    private String accountName;

    @Column(name = "moneda")
    private String currency;

    @Column(name = "hash_verificacio", unique = true)
    @JsonIgnore
    private String verificationHash;

    @Column(name = "created_at", updatable = false)
    @JsonIgnore
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (accountName == null) accountName = "Principal";
        if (currency == null) currency = "EUR";
        if (createdAt == null) createdAt = LocalDateTime.now();
        // La columna és NOT NULL. El valor per defecte va aquí i no a la
        // declaració del camp: allà, una actualització parcial arribaria amb
        // el valor per defecte i no es podria distingir de "no me l'han enviat".
        if (excludedFromBudget == null) excludedFromBudget = Boolean.FALSE;
    }

    /**
     * Un moviment sense la marca compta, que és el comportament de sempre.
     *
     * @JsonIgnore perquè el camp ja surt com a "exclos_pressupost": sense això,
     * Jackson afegiria un "excludedFromBudget" duplicat al costat.
     */
    @Transient
    @JsonIgnore
    public boolean isExcludedFromBudget() {
        return Boolean.TRUE.equals(excludedFromBudget);
    }

    @JsonProperty("deute_id")
    public Long getDebtId() {
        return debt != null ? debt.getId() : null;
    }

    /**
     * Només en guarda l'identificador: el controlador el resol contra la base
     * de dades abans de desar. Un identificador negatiu vol dir "desvincula'l",
     * el mateix conveni que parent_id, perquè en una actualització parcial un
     * null vol dir "no me l'han enviat".
     */
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

    /** Si és un traspàs entre comptes propis. */
    @Transient
    @JsonIgnore
    public boolean isTransfer() {
        return counterpartAccount != null;
    }

    @JsonProperty("compte_contrapart_id")
    public Long getCounterpartAccountId() {
        return counterpartAccount != null ? counterpartAccount.getId() : null;
    }

    /**
     * Com deute_id: només en guarda l'identificador, i el controlador el resol.
     * Un negatiu vol dir "ja no és un traspàs".
     */
    @JsonProperty("compte_contrapart_id")
    public void setCounterpartAccountId(Long accountId) {
        if (accountId == null) {
            this.counterpartAccount = null;
            return;
        }
        Account reference = new Account();
        reference.setId(accountId);
        this.counterpartAccount = reference;
    }

    // Mètodes per assegurar entrada/sortida correcta del JSON
    @JsonProperty("empresa")
    public String getEmpresa() {
        return company != null ? company.getName() : (companyName != null ? companyName : "Desconegut");
    }

    @JsonProperty("empresa")
    public void setEmpresa(String empresa) {
        this.companyName = empresa;
    }

    @JsonProperty("categoria")
    public String getCategoria() {
        return category != null ? category.getName() : (categoryName != null ? categoryName : "Altres");
    }

    @JsonProperty("categoria")
    public void setCategoria(String categoria) {
        this.categoryName = categoria;
    }
}
