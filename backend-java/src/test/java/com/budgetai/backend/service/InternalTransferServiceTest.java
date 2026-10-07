package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalTransferServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountService accountService;
    @InjectMocks private InternalTransferService service;

    private final Account principal = account(1L, "Compte Principal", "CORRIENTE");
    private final Account revolut = account(2L, "Revolut", "CORRIENTE");
    private final Account tradeRepublic = account(3L, "Trade Republic", "INVERSIONES");

    private static Account account(long id, String name, String type) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setType(type);
        return account;
    }

    private static Transaction movement(Account account, String type, String amount, LocalDate date) {
        Transaction transaction = new Transaction();
        transaction.setAccount(account);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setDate(date);
        return transaction;
    }

    @Test
    @DisplayName("Un traspàs entre comptes del dia a dia no compta; cap a l'estalvi, sí")
    void onlySavingsTransfersCount() {
        assertThat(InternalTransferService.movesSavings(principal, revolut)).isFalse();
        assertThat(InternalTransferService.movesSavings(principal, tradeRepublic)).isTrue();
        // El que torna de l'estalvi també: resta estalvi.
        assertThat(InternalTransferService.movesSavings(tradeRepublic, principal)).isTrue();
    }

    @Test
    @DisplayName("Lligar un traspàs del dia a dia el marca com a exclòs; un d'estalvi no el toca")
    void linkingMarksOnlyNeutralTransfers() {
        Transaction toRevolut = movement(principal, "EXPENSE", "100.00", LocalDate.of(2026, 10, 1));
        service.link(toRevolut, principal, revolut);
        assertThat(toRevolut.isExcludedFromBudget()).isTrue();

        Transaction toSavings = movement(principal, "EXPENSE", "300.00", LocalDate.of(2026, 10, 1));
        service.link(toSavings, principal, tradeRepublic);
        assertThat(toSavings.isExcludedFromBudget()).isFalse();
        assertThat(toSavings.getCounterpartAccount()).isSameAs(tradeRepublic);
    }

    @Test
    @DisplayName("Un traspàs al mateix compte es rebutja, i un id negatiu vol dir que ja no és traspàs")
    void resolveRejectsTheSameAccount() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(principal));

        assertThatThrownBy(() -> service.resolve(account(1L, null, null), principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Compte Principal");
        assertThat(service.resolve(account(-1L, null, null), principal)).isNull();
        assertThat(service.resolve(null, principal)).isNull();
    }

    @Test
    @DisplayName("Una despesa traspassada suma a l'altre compte, i desfer-la ho resta")
    void transfersMoveTheOtherBalance() {
        Transaction toSavings = movement(principal, "EXPENSE", "300.00", LocalDate.of(2026, 10, 1));
        toSavings.setCounterpartAccount(tradeRepublic);

        service.applyToCounterpart(toSavings);
        verify(accountService).updateAccountBalance(3L, new BigDecimal("300.00"), "ADD");

        service.revertFromCounterpart(toSavings);
        verify(accountService).updateAccountBalance(3L, new BigDecimal("300.00"), "SUBTRACT");
    }

    @Test
    @DisplayName("Un moviment que no és traspàs no toca cap altre compte")
    void plainMovementsTouchNothingElse() {
        service.applyToCounterpart(movement(principal, "EXPENSE", "12.00", LocalDate.of(2026, 10, 1)));
        verifyNoInteractions(accountService);
    }

    @Test
    @DisplayName("A l'extracte de Revolut, l'entrada d'un traspàs ja desat des de Principal es reconeix")
    void theOtherLegIsRecognised() {
        Transaction stored = movement(principal, "EXPENSE", "100.00", LocalDate.of(2026, 10, 1));
        stored.setId(40L);
        stored.setCounterpartAccount(revolut);
        stored.setExcludedFromBudget(true);
        when(transactionRepository.findTransfersWithCounterpart(2L)).thenReturn(List.of(stored));

        // Arriba l'endemà, i al mateix extracte n'hi ha una altra del mateix
        // import però d'un altre dia, que no és aquest traspàs.
        Transaction arrival = movement(null, "INCOME", "100.00", LocalDate.of(2026, 10, 2));
        Transaction unrelated = movement(null, "INCOME", "100.00", LocalDate.of(2026, 10, 20));
        Transaction payment = movement(null, "EXPENSE", "100.00", LocalDate.of(2026, 10, 1));

        service.prepareForReview(List.of(arrival, unrelated, payment), revolut);

        assertThat(arrival.getRegisteredTransferId()).isEqualTo(40L);
        assertThat(arrival.getCounterpartAccount()).isSameAs(principal);
        assertThat(unrelated.getRegisteredTransferId()).isNull();
        // Mateix import i dia, però en el mateix sentit: és un pagament, no l'arribada.
        assertThat(payment.getRegisteredTransferId()).isNull();
    }

    @Test
    @DisplayName("Dos traspassos iguals el mateix dia són dos: cadascun reconeix una sola línia")
    void eachStoredTransferMatchesOneLine() {
        Transaction first = movement(principal, "EXPENSE", "50.00", LocalDate.of(2026, 10, 1));
        first.setId(41L);
        first.setCounterpartAccount(revolut);
        when(transactionRepository.findTransfersWithCounterpart(2L)).thenReturn(List.of(first));

        Transaction arrival = movement(null, "INCOME", "50.00", LocalDate.of(2026, 10, 1));
        Transaction secondArrival = movement(null, "INCOME", "50.00", LocalDate.of(2026, 10, 1));
        service.prepareForReview(List.of(arrival, secondArrival), revolut);

        assertThat(arrival.getRegisteredTransferId()).isEqualTo(41L);
        assertThat(secondArrival.getRegisteredTransferId()).isNull();
    }

    @Test
    @DisplayName("Una regla que diu «traspàs a Revolut» no s'aplica a l'extracte del mateix Revolut")
    void aRuleToTheImportAccountIsDropped() {
        when(accountRepository.findById(2L)).thenReturn(Optional.of(revolut));
        when(transactionRepository.findTransfersWithCounterpart(2L)).thenReturn(List.of());

        Transaction payment = movement(null, "EXPENSE", "20.00", LocalDate.of(2026, 10, 3));
        payment.setCounterpartAccountId(2L);
        service.prepareForReview(List.of(payment), revolut);

        assertThat(payment.getCounterpartAccount()).isNull();
        assertThat(payment.isExcludedFromBudget()).isFalse();
    }
}
