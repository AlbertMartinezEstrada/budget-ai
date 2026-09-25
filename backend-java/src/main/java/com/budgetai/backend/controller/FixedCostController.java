package com.budgetai.backend.controller;

import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.service.FixedCostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Costos fixos del menú de Pressupostos.
 *
 * Totes les operacions porten el mes des del qual valen (`year`, `month`),
 * que és el que s'està mirant a la pantalla: els mesos anteriors no canvien.
 */
@RestController
@RequestMapping("/fixed-costs")
public class FixedCostController {

    private final FixedCostService fixedCostService;

    public FixedCostController(FixedCostService fixedCostService) {
        this.fixedCostService = fixedCostService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) Integer month) {
        return respond(() -> {
            YearMonth period = periodOf(year, month);
            List<RecurringTransaction> fixedCosts = fixedCostService.listFor(period);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("periode", period.toString());
            response.put("costos", fixedCosts);
            response.put("total_mensual", FixedCostService.monthlyTotal(fixedCosts));
            return response;
        });
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestParam(required = false) Integer year,
                                    @RequestParam(required = false) Integer month,
                                    @RequestBody RecurringTransaction request) {
        return respond(() -> fixedCostService.create(periodOf(year, month), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestParam(required = false) Integer year,
                                    @RequestParam(required = false) Integer month,
                                    @RequestBody RecurringTransaction request) {
        return respond(() -> fixedCostService.update(id, periodOf(year, month), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable Long id,
                                    @RequestParam(required = false) Integer year,
                                    @RequestParam(required = false) Integer month) {
        return respond(() -> {
            fixedCostService.remove(id, periodOf(year, month));
            return Map.of("message", "Cost fix tret a partir d'aquest mes");
        });
    }

    private static YearMonth periodOf(Integer year, Integer month) {
        LocalDate today = LocalDate.now();
        int monthValue = month != null ? month : today.getMonthValue();
        if (monthValue < 1 || monthValue > 12) {
            throw new IllegalArgumentException("El mes ha de ser entre 1 i 12");
        }
        return YearMonth.of(year != null ? year : today.getYear(), monthValue);
    }

    private interface Action {
        Object run();
    }

    private static ResponseEntity<?> respond(Action action) {
        try {
            return ResponseEntity.ok(action.run());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }
}
