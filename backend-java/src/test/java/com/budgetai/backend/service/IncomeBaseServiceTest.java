package com.budgetai.backend.service;

import com.budgetai.backend.model.MonthlyIncome;
import com.budgetai.backend.model.Settings;
import com.budgetai.backend.repository.MonthlyIncomeRepository;
import com.budgetai.backend.repository.SettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Quin sou fa de base per als percentatges d'un mes.
 *
 * L'ordre és: el sou d'aquell mes concret si n'hi ha, i si no el de la
 * configuració. L'ingrés real importat no s'hi fa servir mai.
 */
@ExtendWith(MockitoExtension.class)
class IncomeBaseServiceTest {

    // SettingsService no es simula: Mockito no pot crear subclasses d'aquesta
    // classe amb el JDK actual. Es construeix el servei de debò damunt d'un
    // repositori simulat, que a més prova el camí real.
    @Mock private MonthlyIncomeRepository monthlyIncomeRepository;
    @Mock private SettingsRepository settingsRepository;

    private IncomeBaseService service;

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    private void givenDefaultSalary(String amount) {
        Settings settings = new Settings();
        settings.setExpectedMonthlyIncome(amount == null ? null : new BigDecimal(amount));
        lenient().when(settingsRepository.findFirstBy()).thenReturn(Optional.of(settings));
        service = new IncomeBaseService(monthlyIncomeRepository, new SettingsService(settingsRepository));
    }

    @Test
    @DisplayName("Sense sou del mes, s'aplica el de la configuració")
    void fallsBackToDefault() {
        when(monthlyIncomeRepository.findByPeriod("2026-03")).thenReturn(Optional.empty());
        givenDefaultSalary("2000.00");

        IncomeBaseService.Base base = service.resolve(MARCH);

        assertThat(base.amount()).isEqualByComparingTo("2000.00");
        assertThat(base.origin()).isEqualTo(IncomeBaseService.Origin.PER_DEFECTE);
        assertThat(base.isDefined()).isTrue();
    }

    @Test
    @DisplayName("El sou d'un mes concret mana sobre el de la configuració")
    void monthOverrideWins() {
        when(monthlyIncomeRepository.findByPeriod("2026-03"))
                .thenReturn(Optional.of(new MonthlyIncome("2026-03", new BigDecimal("3500.00"))));
        givenDefaultSalary("2000.00");

        IncomeBaseService.Base base = service.resolve(MARCH);

        // El mes de la paga extra.
        assertThat(base.amount()).isEqualByComparingTo("3500.00");
        assertThat(base.origin()).isEqualTo(IncomeBaseService.Origin.MES);
    }

    @Test
    @DisplayName("Sense sou configurat, la base queda sense definir")
    void undefinedWhenNothingConfigured() {
        when(monthlyIncomeRepository.findByPeriod("2026-03")).thenReturn(Optional.empty());
        givenDefaultSalary(null);

        IncomeBaseService.Base base = service.resolve(MARCH);

        assertThat(base.isDefined()).isFalse();
        assertThat(base.origin()).isEqualTo(IncomeBaseService.Origin.SENSE_DEFINIR);
    }

    @Test
    @DisplayName("Un sou de zero no compta com a definit")
    void zeroIsNotDefined() {
        when(monthlyIncomeRepository.findByPeriod("2026-03")).thenReturn(Optional.empty());
        givenDefaultSalary("0.00");

        assertThat(service.resolve(MARCH).isDefined()).isFalse();
    }

    @Test
    @DisplayName("El percentatge es tradueix a euros amb dos decimals")
    void percentageBecomesAmount() {
        givenDefaultSalary("2000.00");
        IncomeBaseService.Base base =
                new IncomeBaseService.Base(new BigDecimal("2000.00"), IncomeBaseService.Origin.PER_DEFECTE);

        assertThat(service.applyPercentage(base, new BigDecimal("40"))).isEqualByComparingTo("800.00");
        assertThat(service.applyPercentage(base, new BigDecimal("12.5"))).isEqualByComparingTo("250.00");
        // 2000 × 33,33% = 666,60
        assertThat(service.applyPercentage(base, new BigDecimal("33.33"))).isEqualByComparingTo("666.60");
    }

    @Test
    @DisplayName("Sense sou definit, un percentatge no dona cap import")
    void percentageWithoutBaseIsNull() {
        givenDefaultSalary("2000.00");
        IncomeBaseService.Base undefined =
                new IncomeBaseService.Base(null, IncomeBaseService.Origin.SENSE_DEFINIR);

        // Null i no zero: qui ho consumeix ha de poder dir "falta el sou" en
        // comptes d'ensenyar un sostre de 0 €, que semblaria intencionat.
        assertThat(service.applyPercentage(undefined, new BigDecimal("40"))).isNull();
    }

    @Test
    @DisplayName("Un percentatge absent no dona cap import")
    void nullPercentageIsNull() {
        givenDefaultSalary("2000.00");
        IncomeBaseService.Base base =
                new IncomeBaseService.Base(new BigDecimal("2000.00"), IncomeBaseService.Origin.PER_DEFECTE);

        assertThat(service.applyPercentage(base, null)).isNull();
    }
}
