package com.budgetai.backend.controller;

import com.budgetai.backend.model.Debt;
import com.budgetai.backend.service.DebtService;
import com.budgetai.backend.service.DebtService.DebtNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return respond(() -> {
            debtService.delete(id);
            return Map.of("message", "Deute esborrat. Els moviments es queden, sense vincle.");
        });
    }

    /**
     * Les validacions del servei porten un text pensat per a l'usuari i ha
     * d'arribar; un deute que no existeix és un 404.
     */
    private static ResponseEntity<?> respond(Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (DebtNotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }
}
