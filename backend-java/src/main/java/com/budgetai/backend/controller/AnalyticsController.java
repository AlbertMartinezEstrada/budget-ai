package com.budgetai.backend.controller;

import com.budgetai.backend.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/monthly-summary")
    public Map<String, Object> getMonthlySummary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        int targetYear = year != null ? year : java.time.Year.now().getValue();
        int targetMonth = month != null ? month : java.time.LocalDate.now().getMonthValue();

        return analyticsService.getMonthlySummary(targetYear, targetMonth);
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
