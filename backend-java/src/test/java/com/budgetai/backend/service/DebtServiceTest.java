package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.DebtRemovedReceipt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.DebtRemovedReceiptRepository;
import com.budgetai.backend.repository.DebtRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.service.TransactionLines.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DebtServiceTest {

    @Mock private DebtRepository debtRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionPartRepository partRepository;
    @Mock private TransactionLines transactionLines;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryHierarchyService hierarchyService;
    @Mock private DebtRemovedReceiptRepository removedReceiptRepository;
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

    /** El moviment com el veu el pressupost: una sola línia, perquè no està dividit. */
    private static Line line(Debt debt, String type, String amount, LocalDate date) {
        return TransactionLines.expand(List.of(movement(debt, type, amount, date)), List.of()).get(0);
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

        List<Line> iOweMovements = List.of(
                line(iOwe, "INCOME", "1000.00", LOAN_DATE),
                line(iOwe, "EXPENSE", "100.00", LOAN_DATE.plusMonths(1)),
                line(iOwe, "EXPENSE", "100.00", LOAN_DATE.plusMonths(2)));
        List<Line> owedToMeMovements = List.of(
                line(owedToMe, "EXPENSE", "1000.00", LOAN_DATE),
                line(owedToMe, "INCOME", "250.00", LOAN_DATE.plusMonths(1)));

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
        when(transactionLines.all()).thenReturn(List.of(
                line(debt, "EXPENSE", "900.00", LocalDate.of(2026, 10, 20)),
                line(debt, "EXPENSE", "100.00", LocalDate.of(2026, 11, 5))));
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

        when(transactionLines.all()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of(owedToMe, withoutCategory));

        assertThat(service.installmentsDueByCategory(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEmpty();
    }

    @Test
    @DisplayName("D'un moviment dividit, la part vinculada descompta pel seu import i no pel del moviment")
    void splitPartRepaysOnlyItsAmount() {
        Debt debt = request(Debt.I_OWE);
        debt.setId(1L);

        // La transferència de 500 a Trade Republic: 100 tornen el préstec i
        // 400 són estalvi. El moviment encara porta el deute d'abans de
        // dividir-lo: no ha de comptar, manen les parts.
        Transaction transfer = movement(debt, "EXPENSE", "500.00", LOAN_DATE.plusMonths(1));
        transfer.setId(10L);
        TransactionPart repayment = part(transfer, 1L, "100.00", debt);
        TransactionPart savings = part(transfer, 2L, "400.00", null);

        Debt described = service.describe(debt, LOAN_DATE.plusMonths(2),
                TransactionLines.expand(List.of(transfer), List.of(repayment, savings)));

        assertThat(described.getRepaid()).isEqualByComparingTo("100.00");
        assertThat(described.getMovements()).hasSize(1);
        assertThat(described.getMovements().get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(described.getMovements().get(0).part()).isTrue();
        assertThat(described.getMovements().get(0).transactionId()).isEqualTo(10L);
    }

    // ============ REBUTS TRETS ============

    private static final LocalDate SEPTEMBER_8 = LocalDate.of(2026, 9, 8);
    private static final LocalDate OCTOBER_8 = LocalDate.of(2026, 10, 8);

    /** La Steam Deck, desada: el servei la troba pel seu id. */
    private Debt storedSteamDeck() {
        Debt debt = steamDeck();
        when(debtRepository.findById(1L)).thenReturn(Optional.of(debt));
        return debt;
    }

    /** La Steam Deck: 779 € a 97,38 € al mes des del 8 de setembre. */
    private static Debt steamDeck() {
        Debt debt = request(Debt.I_OWE);
        debt.setId(1L);
        debt.setAmount(new BigDecimal("779.00"));
        debt.setDate(LocalDate.of(2026, 9, 1));
        debt.setRepaymentPlan(Debt.PLAN_INSTALLMENTS);
        debt.setInstallment(new BigDecimal("97.38"));
        debt.setFrequency("MENSUAL");
        debt.setFirstPaymentDate(SEPTEMBER_8);
        return debt;
    }

    private DebtRemovedReceipt savedRemoval() {
        ArgumentCaptor<DebtRemovedReceipt> captor = ArgumentCaptor.forClass(DebtRemovedReceipt.class);
        verify(removedReceiptRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Saltar un rebut el desa sense descompte, pel dia que tenia al calendari")
    void skipReceipt() {
        Debt debt = storedSteamDeck();
        when(transactionLines.all()).thenReturn(List.of(line(debt, "EXPENSE", "97.35", LocalDate.of(2026, 10, 6))));

        // Qualsevol dia del mes serveix per dir quin rebut és.
        service.removeReceipt(1L, LocalDate.of(2026, 9, 30), false);

        DebtRemovedReceipt removal = savedRemoval();
        assertThat(removal.getDate()).isEqualTo(SEPTEMBER_8);
        assertThat(removal.getDiscount()).isEqualByComparingTo("0");
        assertThat(removal.getDebt()).isSameAs(debt);
    }

    @Test
    @DisplayName("Descomptar un rebut en desa l'import, el que valia al calendari")
    void discountReceipt() {
        storedSteamDeck();
        when(transactionLines.all()).thenReturn(List.of());

        service.removeReceipt(1L, SEPTEMBER_8, true);

        assertThat(savedRemoval().getDiscount()).isEqualByComparingTo("97.38");
    }

    @Test
    @DisplayName("Un rebut que algun pagament ha triat no es pot treure: el pagament no tindria on anar")
    void receiptChosenByAPaymentCannotBeRemoved() {
        Debt debt = storedSteamDeck();
        Transaction payment = movement(debt, "EXPENSE", "97.35", LocalDate.of(2026, 10, 6));
        payment.setDebtReceipt(OCTOBER_8);
        when(transactionLines.all()).thenReturn(TransactionLines.expand(List.of(payment), List.of()));

        assertThatThrownBy(() -> service.removeReceipt(1L, OCTOBER_8, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canvia'ls de rebut");
        verify(removedReceiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("Només es treuen rebuts d'un deute a quotes, i que siguin al calendari")
    void onlyInstallmentReceiptsCanBeRemoved() {
        Debt single = request(Debt.I_OWE);
        single.setId(2L);
        single.setRepaymentPlan(Debt.PLAN_SINGLE);
        single.setFirstPaymentDate(LocalDate.of(2026, 10, 1));
        when(debtRepository.findById(2L)).thenReturn(Optional.of(single));

        assertThatThrownBy(() -> service.removeReceipt(2L, LocalDate.of(2026, 10, 1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a quotes");

        storedSteamDeck();
        when(transactionLines.all()).thenReturn(List.of());
        assertThatThrownBy(() -> service.removeReceipt(1L, LocalDate.of(2026, 8, 8), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no és al calendari");
    }

    @Test
    @DisplayName("Un rebut tret es pot tornar al calendari")
    void restoreReceipt() {
        Debt debt = storedSteamDeck();
        DebtRemovedReceipt removal = new DebtRemovedReceipt(5L, debt, SEPTEMBER_8, BigDecimal.ZERO, null);
        when(removedReceiptRepository.findByDebt(1L)).thenReturn(List.of(removal));
        when(transactionLines.all()).thenReturn(List.of());

        service.restoreReceipt(1L, SEPTEMBER_8);

        verify(removedReceiptRepository).deleteAll(List.of(removal));
        assertThatThrownBy(() -> service.restoreReceipt(1L, OCTOBER_8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no està tret");
    }

    @Test
    @DisplayName("El calendari descriu els rebuts trets i resta el descompte del que queda")
    void describeIncludesRemovedReceipts() {
        Debt debt = steamDeck();
        when(removedReceiptRepository.findByDebt(1L)).thenReturn(List.of(
                new DebtRemovedReceipt(5L, debt, SEPTEMBER_8, new BigDecimal("97.38"), null)));

        Debt described = service.describe(debt, LocalDate.of(2026, 10, 9),
                List.of(line(debt, "EXPENSE", "97.35", LocalDate.of(2026, 10, 6))));

        assertThat(described.getDiscounted()).isEqualByComparingTo("97.38");
        assertThat(described.getPending()).isEqualByComparingTo("584.27");
        assertThat(described.getSchedule().get(0).status()).isEqualTo("DESCOMPTAT");
        assertThat(described.getSchedule().get(1).status()).isEqualTo("PAGAT");
        assertThat(described.getMovements().get(0).receipt()).isEqualTo(OCTOBER_8);
        assertThat(described.getOverdue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Un mes saltat no reserva res al pressupost: la quota passa al final")
    void skippedMonthReservesNothing() {
        Category leaf = new Category("Pagament de deutes");
        leaf.setId(3L);
        Debt debt = steamDeck();
        debt.setCategory(leaf);
        DebtRemovedReceipt skipped = new DebtRemovedReceipt(5L, debt, OCTOBER_8, BigDecimal.ZERO, null);
        when(removedReceiptRepository.findAll()).thenReturn(List.of(skipped));
        when(transactionLines.all()).thenReturn(List.of());
        when(debtRepository.findAll()).thenReturn(List.of(debt));

        assertThat(service.installmentsDueByCategory(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .doesNotContainKey(3L);
        assertThat(service.installmentsDueByCategory(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 31)).get(3L))
                .isEqualByComparingTo("97.34");
    }

    private static TransactionPart part(Transaction transaction, Long id, String amount, Debt debt) {
        TransactionPart part = new TransactionPart();
        part.setId(id);
        part.setTransaction(transaction);
        part.setAmount(new BigDecimal(amount));
        part.setDebt(debt);
        part.setExcludedFromBudget(false);
        return part;
    }
}
