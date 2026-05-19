package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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

    @Column(name = "import")
    @JsonProperty("cost") 
    private Double amount;

    @Column(name = "saldo_resultant")
    @JsonProperty("saldo")
    private Double balance;

    @Column(name = "tipus")
    private String type; // EXPENSE, INCOME, TRANSFER

    @Column(name = "concepte_original")
    @JsonProperty("concepte_original")
    private String originalConcept;

    @Column(name = "compte_nom")
    private String accountName = "Principal";

    @Column(name = "moneda")
    private String currency = "EUR";

    @Column(name = "hash_verificacio", unique = true)
    @JsonIgnore
    private String verificationHash;

    @Column(name = "created_at", updatable = false)
    @JsonIgnore
    private LocalDateTime createdAt = LocalDateTime.now();

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
