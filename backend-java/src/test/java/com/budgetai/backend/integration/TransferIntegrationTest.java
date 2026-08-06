package com.budgetai.backend.integration;

import com.budgetai.backend.controller.TransferController;
import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Transfer;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El comportament transaccional de les transferències contra una base de
 * dades de debò. Fins ara només s'havia verificat a mà.
 */
class TransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TransferController transferController;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransferRepository transferRepository;

    private Long sourceId;
    private Long destinationId;

    @BeforeEach
    void setUp() {
        transferRepository.deleteAll();
        accountRepository.deleteAll();

        sourceId = createAccount("Origen", "100.00").getId();
        destinationId = createAccount("Destí", "0.00").getId();
    }

    private Account createAccount(String name, String balance) {
        Account account = new Account();
        account.setName(name);
        account.setType("CORRIENTE");
        account.setCurrentBalance(new BigDecimal(balance));
        return accountRepository.save(account);
    }

    private BigDecimal balanceOf(Long id) {
        return accountRepository.findById(id).orElseThrow().getCurrentBalance();
    }

    private Transfer transferRequest(Long from, Long to, String amount) {
        Account source = new Account();
        source.setId(from);
        Account destination = new Account();
        destination.setId(to);

        Transfer transfer = new Transfer();
        transfer.setSourceAccount(source);
        transfer.setDestinationAccount(destination);
        transfer.setAmount(new BigDecimal(amount));
        transfer.setDate(LocalDate.of(2026, 8, 6));
        return transfer;
    }

    @Test
    @DisplayName("Una transferència mou els diners d'un compte a l'altre")
    void transferMovesMoney() {
        transferController.createTransfer(transferRequest(sourceId, destinationId, "25.50"));

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("74.50");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("25.50");
        assertThat(transferRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("Esborrar una transferència retorna els diners")
    void deletingATransferRevertsBalances() {
        var created = transferController.createTransfer(transferRequest(sourceId, destinationId, "25.50"));
        Long transferId = ((Transfer) created.getBody()).getId();

        transferController.deleteTransfer(transferId);

        // Abans només s'esborrava la fila: els saldos quedaven descompensats
        // per sempre i sense cap rastre que ho expliqués.
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("0.00");
        assertThat(transferRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Crear i esborrar diverses vegades deixa els saldos com al principi")
    void repeatedCyclesLeaveNoDrift() {
        for (int i = 0; i < 5; i++) {
            var created = transferController.createTransfer(
                    transferRequest(sourceId, destinationId, "33.33"));
            transferController.deleteTransfer(((Transfer) created.getBody()).getId());
        }

        // Amb double, cinc cicles de 33,33 deixarien residus decimals.
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Si el compte de destí no existeix, no es mou ni un cèntim")
    void failureRollsBackTheWholeOperation() {
        // El compte d'origen es valida i té saldo, així que la resta abans de
        // fallar. Sense @Transactional, els diners marxaven de l'origen i no
        // arribaven enlloc.
        assertThatThrownBy(() ->
                transferController.createTransfer(transferRequest(sourceId, 999_999L, "25.50")))
                .isInstanceOf(RuntimeException.class);

        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100.00");
        assertThat(transferRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Sense saldo suficient es rebutja i no es toca res")
    void insufficientBalanceIsRejected() {
        ResponseEntity<?> response =
                transferController.createTransfer(transferRequest(sourceId, destinationId, "9999.00"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(destinationId)).isEqualByComparingTo("0.00");
        assertThat(transferRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("Una transferència al mateix compte es rebutja")
    void sameAccountIsRejected() {
        ResponseEntity<?> response =
                transferController.createTransfer(transferRequest(sourceId, sourceId, "10.00"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(balanceOf(sourceId)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("La transferència desada porta els comptes reals, no els de la petició")
    void savedTransferCarriesRealAccounts() {
        var created = transferController.createTransfer(transferRequest(sourceId, destinationId, "10.00"));
        Transfer saved = transferRepository.findById(((Transfer) created.getBody()).getId()).orElseThrow();

        // El cos de la petició només porta l'id: si no es lligaven els comptes
        // reals, la resposta deia que el compte d'origen es deia null.
        assertThat(saved.getSourceAccount().getName()).isEqualTo("Origen");
        assertThat(saved.getDestinationAccount().getName()).isEqualTo("Destí");
    }
}
