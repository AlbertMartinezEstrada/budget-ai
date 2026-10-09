package com.budgetai.backend.controller;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.service.DebtService;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deutes i préstecs, en els dos sentits.
 *
 * Cada deute surt amb el que s'ha retornat, el que queda, el calendari de
 * retorn amb l'estat de cada pagament i els moviments vinculats: tot calculat
 * a partir dels moviments, que són la veritat.
 */
@RestController
@RequestMapping("/debts")
public class DebtController {

    private final DebtService debtService;

    public DebtController(DebtService debtService) {
        this.debtService = debtService;
    }

    @GetMapping
    public List<Debt> list() {
        return debtService.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return respond(() -> debtService.get(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Debt request) {
        return respond(() -> debtService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Debt changes) {
        return respond(() -> debtService.update(id, changes));
    }

    /**
     * Treu un rebut del calendari. {"data": "2026-09-08", "descomptar": false}
     * el salta (el pla s'allarga pel final); amb "descomptar": true, el seu
     * import es resta del deute.
     */
    @PostMapping("/{id}/rebuts-eliminats")
    public ResponseEntity<?> removeReceipt(@PathVariable Long id, @RequestBody ReceiptRemoval request) {
        return respond(() -> debtService.removeReceipt(id, request.date(), Boolean.TRUE.equals(request.discount())));
    }

    /** Torna al calendari un rebut tret. */
    @DeleteMapping("/{id}/rebuts-eliminats/{date}")
    public ResponseEntity<?> restoreReceipt(@PathVariable Long id,
                                            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return respond(() -> debtService.restoreReceipt(id, date));
    }

    public record ReceiptRemoval(
            @JsonProperty("data") @JsonFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @JsonProperty("descomptar") Boolean discount) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return respond(() -> {
            debtService.delete(id);
            return Map.of("message", "Deute esborrat. Els moviments es queden, sense vincle.");
        });
    }

    /**
     * Les validacions del servei porten un text pensat per a l'usuari i ha
     * d'arribar. Un deute que no existeix ho resol el gestor d'errors global,
     * amb un 404 que diu quin.
     */
    private static ResponseEntity<?> respond(Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }
}
