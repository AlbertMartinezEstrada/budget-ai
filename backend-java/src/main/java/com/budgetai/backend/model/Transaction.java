package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
     * Diners que no s'han de tornar a comptar al pressupost.
     *
     * Un cop surten del compte principal ja estan comptats: el traspàs cap a
     * Revolut compta com a despesa, i a partir d'aquí l'entrada a Revolut i la
     * compra que s'hi faci són el mateix diner una altra vegada. Sense això,
     * 100 € traspassats i invertits sortien com a 200 € de despesa, i l'entrada
     * al compte destí a més inflava el bot a repartir.
     *
     * Es diu "exclòs del pressupost" i no "és traspàs" perquè la compra de dins
     * del compte destí no és cap traspàs, però tampoc s'ha de comptar.
     *
     * El saldo sí que es mou igualment: cada extracte és la veritat del seu
     * compte i el moviment hi ha passat de debò.
     */
    @Column(name = "exclos_pressupost")
    @JsonProperty("exclos_pressupost")
    private Boolean excludedFromBudget;

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
