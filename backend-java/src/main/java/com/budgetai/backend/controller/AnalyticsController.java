package com.budgetai.backend.controller;

import com.budgetai.backend.service.AnalyticsService;
import com.budgetai.backend.service.BudgetReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private BudgetReviewService budgetReviewService;

    @GetMapping("/monthly-summary")
    public Map<String, Object> getMonthlySummary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        int targetYear = year != null ? year : java.time.Year.now().getValue();
        int targetMonth = month != null ? month : java.time.LocalDate.now().getMonthValue();

        return analyticsService.getMonthlySummary(targetYear, targetMonth);
    }

    /**
     * El pla del pressupost contra el real, mes a mes, de from a to (AAAA-MM,
     * inclosos). Sense from, els sis mesos fins a to; sense to, fins al mes
     * actual.
     */
    @GetMapping("/budget-vs-actual")
    public Map<String, Object> getBudgetVsActual(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return budgetReviewService.review(monthOf(from), monthOf(to));
    }

    private static YearMonth monthOf(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("El mes «" + value + "» no és vàlid: ha de ser AAAA-MM, com 2026-10.");
        }
    }

    /** Tot el que hi ha, sense dates: el que ensenya el tauler. */
    @GetMapping("/overall")
    public Map<String, Object> getOverallSummary() {
        return analyticsService.getOverallSummary();
    }

    @GetMapping("/category-breakdown")
    public List<Map<String, Object>> getCategoryBreakdown(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        int targetYear = year != null ? year : java.time.Year.now().getValue();
        int targetMonth = month != null ? month : java.time.LocalDate.now().getMonthValue();

        return analyticsService.getCategoryBreakdown(targetYear, targetMonth);
    }

    @GetMapping("/yearly-summary")
    public Map<String, Object> getYearlySummary(@RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : java.time.Year.now().getValue();
        return analyticsService.getYearlySummary(targetYear);
    }

    @GetMapping("/monthly-trend")
    public List<Map<String, Object>> getMonthlyTrend(@RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : java.time.Year.now().getValue();
        return analyticsService.getMonthlyTrend(targetYear);
    }
}
