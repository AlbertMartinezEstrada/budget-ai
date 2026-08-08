package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Categoria de despesa o ingrés.
 *
 * Les categories formen un arbre: una categoria amb fills és un GRUP
 * ("Cotxe personal", "Gastos passius") i una sense fills és una FULLA
 * ("Assegurança cotxe", "Supermercat").
 *
 * Regla del model: les transaccions només s'assignen a fulles. Els grups
 * existeixen per agregar, no per rebre moviments.
 *
 * El parentescs es guarda com a identificador i no com a @ManyToOne a
 * propòsit: amb una relació d'entitat, serialitzar una categoria
 * n'arrossegaria tota la branca de pares cap al JSON, i el frontend només
 * necessita l'identificador per reconstruir l'arbre.
 */
@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nom", unique = true, nullable = false)
    @JsonProperty("nom")
    private String name;

    /** Grup al qual pertany. Null vol dir que la categoria és d'primer nivell. */
    @Column(name = "parent_id")
    @JsonProperty("parent_id")
    private Long parentId;

    /**
     * Naturalesa del cost: FIXED o VARIABLE.
     *
     * Només té sentit a les fulles. Als grups es deixa a null, perquè un grup
     * pot barrejar despeses fixes i variables.
     *
     * Una fulla amb el camp a null es tracta com a VARIABLE: és el
     * comportament que ja tenien totes les categories abans d'existir aquest
     * camp, i així les dades existents no canvien de significat.
     */
    @Column(name = "tipus_cost", length = 20)
    @JsonProperty("tipus_cost")
    private String costType;

    public Category(String name) {
        this.name = name;
    }

    public static final String FIXED = "FIXED";
    public static final String VARIABLE = "VARIABLE";

    /** Una fulla sense naturalesa declarada compta com a variable. */
    @Transient
    @JsonProperty("es_fix")
    public boolean isFixed() {
        return FIXED.equals(costType);
    }
}
