package com.budgetai.backend.controller;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.service.SharedExpenseService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compartir un pagament ja desat: el sopar que vaig pagar per a quatre i els
 * Bizums que me'n tornen la part. A la importació es fa a la mateixa
 * confirmació (TransactionController.confirmUpload).
 */
@RestController
public class SharedExpenseController {

    private final SharedExpenseService sharedExpenses;

    public SharedExpenseController(SharedExpenseService sharedExpenses) {
        this.sharedExpenses = sharedExpenses;
    }

    /**
     * @param paymentId    el pagament, una despesa
     * @param myShare      la part que és meva i compta; la resta me la deuen
     * @param name         el nom del deute; buit, el de l'empresa
     * @param repaymentIds els ingressos que la tornen, si ja han arribat
     */
    public record ShareRequest(
            @JsonProperty("moviment_id") Long paymentId,
            @JsonProperty("part_meva") BigDecimal myShare,
            @JsonProperty("nom") String name,
            @JsonProperty("cobraments") List<Long> repaymentIds) {}

    /** Torna el deute nou: el que vaig avançar, el que ja m'han tornat i el que en falta. */
    @PostMapping("/despeses-compartides")
    public Debt share(@RequestBody ShareRequest request) {
        return sharedExpenses.share(request.paymentId(), request.myShare(), request.name(), request.repaymentIds());
    }
}
