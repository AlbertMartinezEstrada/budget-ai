package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private TransferRepository transferRepository;

    @InjectMocks private AccountService service;

    private Account existing;

    @BeforeEach
    void setUp() {
        existing = new Account();
        existing.setId(1L);
        existing.setName("Compte Principal");
        existing.setType("CORRIENTE");
        existing.setCurrentBalance(new BigDecimal("100.00"));
        existing.setCurrency("EUR");
        existing.setActive(true);
        existing.setColor("#4CAF50");
    }

    @Test
    @DisplayName("Actualitzar sense enviar activa i moneda no les esborra")
    void partialUpdateKeepsUntouchedFields() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        // Això és exactament el que enviava el formulari de comptes.
        Account partial = new Account();
        partial.setName("Nou nom");
        partial.setType("AHORRO");

        Account result = service.updateAccount(1L, partial);

        assertThat(result.getName()).isEqualTo("Nou nom");
        assertThat(result.getType()).isEqualTo("AHORRO");
        // Abans aquests dos quedaven a null: el compte desapareixia del
        // llistat de comptes actius i es quedava sense divisa.
        assertThat(result.getActive()).isTrue();
        assertThat(result.getCurrency()).isEqualTo("EUR");
        assertThat(result.getCurrentBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Sumar i restar del saldo amb precisió decimal exacta")
    void balanceArithmeticIsExact() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);

        service.updateAccountBalance(1L, new BigDecimal("0.10"), "SUBTRACT");
        service.updateAccountBalance(1L, new BigDecimal("0.20"), "SUBTRACT");

        verify(accountRepository, times(2)).save(saved.capture());
        // Amb double, 100 - 0.1 - 0.2 dona 99.69999999999999.
        assertThat(saved.getValue().getCurrentBalance()).isEqualByComparingTo("99.70");
    }

    @Test
    @DisplayName("Un saldo a null es tracta com a zero en comptes de petar")
    void nullBalanceIsTreatedAsZero() {
        existing.setCurrentBalance(null);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArgument(0));

        service.updateAccountBalance(1L, new BigDecimal("25.00"), "ADD");

        assertThat(existing.getCurrentBalance()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Una operació desconeguda es rebutja en comptes d'ignorar-se en silenci")
    void unknownOperationIsRejected() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateAccountBalance(1L, BigDecimal.ONE, "MULTIPLY"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("No es pot esborrar un compte que té moviments")
    void deleteIsBlockedWhenAccountHasTransactions() {
        when(transactionRepository.countByAccountId(1L)).thenReturn(21L);

        assertThatThrownBy(() -> service.deleteAccount(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("21");

        verify(accountRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Un compte sense moviments ni transferències sí que s'esborra")
    void deleteProceedsWhenAccountIsUnused() {
        when(transactionRepository.countByAccountId(2L)).thenReturn(0L);
        when(transferRepository.findByAccountId(2L)).thenReturn(List.of());

        service.deleteAccount(2L);

        verify(accountRepository).deleteById(2L);
    }
}
