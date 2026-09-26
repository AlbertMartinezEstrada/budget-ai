package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.DebtRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DebtServiceTest {

    @Mock private DebtRepository debtRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryHierarchyService hierarchyService;
    @InjectMocks private DebtService service;

    private static final LocalDate LOAN_DATE = LocalDate.of(2026, 9, 10);

    private static Debt request(String direction) {
        Debt debt = new Debt();
        debt.setName("Germà, portàtil");
        debt.setDirection(direction);
        debt.setAmount(new BigDecimal("1000.00"));
        debt.setDate(LOAN_DATE);
        return debt;
    }

    private static Transaction movement(Debt debt, String type, String amount, LocalDate date) {
        Transaction transaction = new Transaction();
        transaction.setDebt(debt);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        return transaction;
    }

    private void saveReturnsArgument() {
        when(debtRepository.save(any(Debt.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Sense forma de retorn, el deute es crea com a lliure")
    void defaultsToFreePlan() {
        saveReturnsArgument();

        Debt created = service.create(request(Debt.I_OWE));

        assertThat(created.getRepaymentPlan()).isEqualTo(Debt.PLAN_FREE);
        assertThat(created.getSchedule()).isEmpty();
        assertThat(created.getPending()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("A quotes sense freqüència, és mensual")
    void installmentsDefaultToMonthly() {
        saveReturnsArgument();
        Debt debt = request(Debt.I_OWE);
        debt.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        debt.setInstallment(new BigDecimal("100.00"));
        debt.setFirstPaymentDate(LocalDate.of(2026, 10, 1));

        Debt created = service.create(debt);

        assertThat(created.getFrequency()).isEqualTo("MENSUAL");
        assertThat(created.getSchedule()).hasSize(10);
    }

    @Test
    @DisplayName("Passar a lliure buida la quota i les dates: no queda cap calendari fantasma")
    void switchingToFreeClearsThePlan() {
        saveReturnsArgument();
        Debt stored = request(Debt.I_OWE);
        stored.setId(1L);
        stored.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        stored.setInstallment(new BigDecimal("100.00"));
        stored.setFrequency("MENSUAL");
        stored.setFirstPaymentDate(LocalDate.of(2026, 10, 1));
        when(debtRepository.findById(1L)).thenReturn(Optional.of(stored));

        Debt changes = new Debt();
        changes.setRepaymentPlan(Debt.PLAN_FREE);
        Debt updated = service.update(1L, changes);

        assertThat(updated.getInstallment()).isNull();
        assertThat(updated.getFrequency()).isNull();
        assertThat(updated.getFirstPaymentDate()).isNull();
        // La resta no s'ha enviat i no es toca.
        assertThat(updated.getName()).isEqualTo("Germà, portàtil");
        assertThat(updated.getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Les validacions arriben amb un text per a l'usuari")
    void validationMessages() {
        Debt withoutDirection = request(null);
        assertThatThrownBy(() -> service.create(withoutDirection))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deus tu");

        Debt installmentsWithoutAmount = request(Debt.I_OWE);
        installmentsWithoutAmount.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        installmentsWithoutAmount.setFirstPaymentDate(LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> service.create(installmentsWithoutAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quota");

        Debt repaidBeforeLoan = request(Debt.I_OWE);
        repaidBeforeLoan.setRepaymentPlan(Debt.PLAN_SINGLE);
        repaidBeforeLoan.setFirstPaymentDate(LOAN_DATE.minusDays(1));
        assertThatThrownBy(() -> service.create(repaidBeforeLoan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abans del préstec");
    }

    @Test
    @DisplayName("La quota no es pot reservar a un grup: es tornaria a sumar pels fills")
    void categoryMustBeALeaf() {
        Category group = new Category("Deutes i préstecs");
        group.setId(7L);
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(group));
        when(hierarchyService.isGroup(7L)).thenReturn(true);

        Debt debt = request(Debt.I_OWE);
        Category reference = new Category();
        reference.setId(7L);
        debt.setCategory(reference);

        assertThatThrownBy(() -> service.create(debt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("és un grup");
    }

    @Test
    @DisplayName("Només descompten els moviments en sentit de retorn: l'entrada del préstec no")
    void onlyRepaymentsCount() {
        Debt iOwe = request(Debt.I_OWE);
        Debt owedToMe = request(Debt.OWED_TO_ME);

        List<Transaction> iOweMovements = List.of(
                movement(iOwe, "INCOME", "1000.00", LOAN_DATE),
                movement(iOwe, "EXPENSE", "100.00", LOAN_DATE.plusMonths(1)),
                movement(iOwe, "EXPENSE", "100.00", LOAN_DATE.plusMonths(2)));
        List<Transaction> owedToMeMovements = List.of(
                movement(owedToMe, "EXPENSE", "1000.00", LOAN_DATE),
                movement(owedToMe, "INCOME", "250.00", LOAN_DATE.plusMonths(1)));

        assertThat(DebtService.repaid(iOwe, iOweMovements, null)).isEqualByComparingTo("200.00");
        assertThat(DebtService.repaid(owedToMe, owedToMeMovements, null)).isEqualByComparingTo("250.00");
        // Abans d'un dia concret, només el que ja havia arribat.
        assertThat(DebtService.repaid(iOwe, iOweMovements, LOAN_DATE.plusMonths(2)))
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("El pressupost reserva la quota del mes, com a molt el que quedava per tornar")
    void reservationIsCappedByWhatIsPending() {
        Category leaf = new Category("Pagament de deutes");
        leaf.setId(3L);

        Debt debt = request(Debt.I_OWE);
        debt.setId(1L);
        debt.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        debt.setInstallment(new BigDecimal("300.00"));
        debt.setFrequency("MENSUAL");
        debt.setFirstPaymentDate(LocalDate.of(2026, 10, 5));
        debt.setCategory(leaf);

        // A l'octubre se n'avancen 600 a més de la quota, i al novembre es
        // torna el que quedava.
        when(transactionRepository.findByDebtIsNotNull()).thenReturn(List.of(
                movement(debt, "EXPENSE", "900.00", LocalDate.of(2026, 10, 20)),
                movement(debt, "EXPENSE", "100.00", LocalDate.of(2026, 11, 5))));
        when(debtRepository.findAll()).thenReturn(List.of(debt));

        Map<Long, BigDecimal> october = service.installmentsDueByCategory(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        Map<Long, BigDecimal> november = service.installmentsDueByCategory(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        Map<Long, BigDecimal> december = service.installmentsDueByCategory(
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));

        // L'octubre, la quota sencera: l'avançament va arribar dins del mes.
        assertThat(october.get(3L)).isEqualByComparingTo("300.00");
        // El novembre en quedaven 100: la quota de 300 ja no cal sencera.
        assertThat(november.get(3L)).isEqualByComparingTo("100.00");
        // El desembre ja estava saldat, encara que el calendari original hi
        // posés una quota.
        assertThat(december).doesNotContainKey(3L);
    }

    @Test
    @DisplayName("Els deutes que em deuen i els que no tenen categoria no reserven res")
    void onlyOwnDebtsWithCategoryReserve() {
        Category leaf = new Category("Cobrament de préstecs");
        leaf.setId(4L);

        Debt owedToMe = request(Debt.OWED_TO_ME);
        owedToMe.setRepaymentPlan(Debt.PLAN_SINGLE);
        owedToMe.setFirstPaymentDate(LocalDate.of(2026, 10, 1));
        owedToMe.setCategory(leaf);

        Debt withoutCategory = request(Debt.I_OWE);
        withoutCategory.setRepaymentPlan(Debt.PLAN_SINGLE);
        withoutCategory.setFirstPaymentDate(LocalDate.of(2026, 10, 1));

        when(transactionRepository.findByDebtIsNotNull()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of(owedToMe, withoutCategory));

        assertThat(service.installmentsDueByCategory(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEmpty();
    }
}
