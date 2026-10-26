package com.budgetai.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Un pagament que era de diverses persones: el sopar de 40 € entre quatre.
 *
 * En importar un extracte, la fila del pagament el porta i les dels Bizums que
 * el tornen porten la mateixa clau a "cobrament_de". Només viatja a la petició:
 * el que en queda és un deute que em deuen, el pagament dividit i els Bizums
 * vinculats.
 *
 * @param key     lliga el pagament amb els seus cobraments dins del lot
 * @param name    el nom del deute; buit, el de l'empresa del pagament
 * @param myShare la meva part, la que compta al pressupost; la resta me la deuen
 */
public record SharedExpense(
        @JsonProperty("clau") String key,
        @JsonProperty("nom") String name,
        @JsonProperty("part_meva") BigDecimal myShare) {}
