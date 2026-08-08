package com.budgetai.backend.service;

import com.budgetai.backend.model.MonthlyIncome;
import com.budgetai.backend.repository.MonthlyIncomeRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Resol quin sou fa de base per als percentatges d'un mes.
 *
 * L'ordre és: primer el sou concret d'aquell mes, si n'hi ha; si no, el sou
 * de referència de la configuració. No es fa servir mai l'ingrés real
 * importat com a base, perquè faria ballar el pressupost: amb dades reals,
 * un mes amb la nòmina encara no importada tindria un pla de zero euros, i
 * un mes amb una devolució inflaria tots els sostres.
 *
 * L'ingrés real sí que es reporta al costat, per veure la desviació.
 */
@Service
public class IncomeBaseService {

    private final MonthlyIncomeRepository monthlyIncomeRepository;
    private final SettingsService settingsService;

    public IncomeBaseService(MonthlyIncomeRepository monthlyIncomeRepository,
                             SettingsService settingsService) {
        this.monthlyIncomeRepository = monthlyIncomeRepository;
        this.settingsService = settingsService;
    }

    /** D'on surt el sou base d'un mes. */
    public enum Origin {
        /** Hi ha un import desat per a aquest mes concret. */
        MES,
        /** S'aplica el sou de referència de la configuració. */
        PER_DEFECTE,
        /** No hi ha ni l'un ni l'altre: els percentatges no es poden calcular. */
        SENSE_DEFINIR
    }

    public record Base(BigDecimal amount, Origin origin) {
        public boolean isDefined() {
            return amount != null && amount.signum() > 0;
        }
    }

    public Base resolve(YearMonth period) {
        Optional<MonthlyIncome> override = monthlyIncomeRepository.findByPeriod(period.toString());
        if (override.isPresent() && override.get().getAmount() != null) {
            return new Base(override.get().getAmount(), Origin.MES);
        }

        BigDecimal fallback = settingsService.getSettings().getExpectedMonthlyIncome();
        if (fallback != null && fallback.signum() > 0) {
            return new Base(fallback, Origin.PER_DEFECTE);
        }

        return new Base(null, Origin.SENSE_DEFINIR);
    }

    /**
     * Import que correspon a un percentatge del sou base.
     *
     * Torna null si el sou no està definit: així qui ho consumeix pot dir
     * "falta configurar el sou" en comptes d'ensenyar un zero, que semblaria
     * un pressupost de zero euros.
     */
    public BigDecimal applyPercentage(Base base, BigDecimal percentage) {
        if (!base.isDefined() || percentage == null) return null;

        return base.amount()
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public List<MonthlyIncome> listOverrides() {
        return monthlyIncomeRepository.findAllByOrderByPeriodDesc();
    }

    /** Desa el sou d'un mes; si ja n'hi havia un, el substitueix. */
    public MonthlyIncome saveOverride(String period, BigDecimal amount, String notes) {
        MonthlyIncome income = monthlyIncomeRepository.findByPeriod(period)
                .orElseGet(() -> new MonthlyIncome(period, amount));
        income.setAmount(amount);
        income.setNotes(notes);
        return monthlyIncomeRepository.save(income);
    }

    public void deleteOverride(String period) {
        monthlyIncomeRepository.findByPeriod(period)
                .ifPresent(monthlyIncomeRepository::delete);
    }
}
