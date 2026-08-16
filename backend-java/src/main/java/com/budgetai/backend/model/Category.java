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
     * Vol dir dues coses segons on estigui, perquè són dues preguntes
     * diferents sobre la mateixa idea:
     *
     *   A UNA FULLA  com es mesura: un fix compta pel prorrateig del seu
     *                recurrent, un variable pel gasto real del mes.
     *   A UN GRUP    a quina secció del repartiment va el bloc sencer. A null,
     *                es dedueix: fix si totes les seves fulles ho són.
     *
     * Un grup pot ser fix i tenir fulles variables a dins —"Llar" és una
     * despesa fixa, però la llum es mesura pel consum—, i per això la secció
     * s'hi pot declarar en comptes de sortir només de les fulles.
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
    /**
     * Valor sentinella d'entrada, mai desat: en una actualització parcial vol
     * dir "buida'm la naturalesa". Sense ell no es podria distingir de "no he
     * enviat el camp", que ha de deixar el valor tal com estava.
     */
    public static final String AUTO = "AUTO";

    /** Una fulla sense naturalesa declarada compta com a variable. */
    @Transient
    @JsonProperty("es_fix")
    public boolean isFixed() {
        return FIXED.equals(costType);
    }
}
