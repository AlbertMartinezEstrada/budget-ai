package com.budgetai.backend.controller;

import com.budgetai.backend.model.Transfer;
import com.budgetai.backend.repository.TransferRepository;
import com.budgetai.backend.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private AccountService accountService;

    @GetMapping
    public List<Transfer> getAllTransfers(@RequestParam(required = false) Long accountId) {
        if (accountId != null) {
            return transferRepository.findByAccountId(accountId);
        }
        return transferRepository.findAllByOrderByDateDesc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transfer> getTransferById(@PathVariable Long id) {
        return transferRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // @Transactional: els dos moviments de saldo i el desat de la
    // transferència han de quedar tots fets o cap. Sense això, un error a
    // mig camí deixava diners moguts sense cap transferència que ho expliqui.
    @PostMapping
    @Transactional
    public ResponseEntity<?> createTransfer(@RequestBody Transfer transfer) {
        try {
            // Validaciones
            if (transfer.getSourceAccount() == null || transfer.getDestinationAccount() == null
                    || transfer.getSourceAccount().getId() == null
                    || transfer.getDestinationAccount().getId() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source and destination accounts are required"));
            }

            if (transfer.getSourceAccount().getId().equals(transfer.getDestinationAccount().getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source and destination accounts must be different"));
            }

            if (transfer.getAmount() == null || transfer.getAmount().signum() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Amount must be positive"));
            }

            if (transfer.getDate() == null) {
                transfer.setDate(LocalDate.now());
            }

            // Verificar que la cuenta origen tenga saldo suficiente
            var sourceAccount = accountService.getAccountById(transfer.getSourceAccount().getId())
                    .orElseThrow(() -> new RuntimeException("Source account not found"));

            BigDecimal sourceBalance = sourceAccount.getCurrentBalance() != null
                    ? sourceAccount.getCurrentBalance()
                    : BigDecimal.ZERO;

            if (sourceBalance.compareTo(transfer.getAmount()) < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Insufficient balance in source account"));
            }

            var destinationAccount = accountService.getAccountById(transfer.getDestinationAccount().getId())
                    .orElseThrow(() -> new RuntimeException("Destination account not found"));

            // Es lliguen els comptes reals: el cos de la petició només porta
            // l'id, i sense això la resposta tornava els comptes amb el nom a
            // null i el saldo a zero, com si fossin les dades bones.
            transfer.setSourceAccount(sourceAccount);
            transfer.setDestinationAccount(destinationAccount);

            // Actualizar saldos
            accountService.updateAccountBalance(sourceAccount.getId(), transfer.getAmount(), "SUBTRACT");
            accountService.updateAccountBalance(destinationAccount.getId(), transfer.getAmount(), "ADD");

            // Guardar transferencia
            Transfer saved = transferRepository.save(transfer);

            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            throw new TransferFailedException(e.getMessage());
        }
    }

    // Esborrar una transferència ha de desfer el moviment de diners. Abans
    // només s'esborrava la fila i els saldos quedaven descompensats per sempre.
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteTransfer(@PathVariable Long id) {
        Transfer transfer = transferRepository.findById(id).orElse(null);
        if (transfer == null) {
            return ResponseEntity.notFound().build();
        }

        if (transfer.getAmount() != null
                && transfer.getSourceAccount() != null
                && transfer.getDestinationAccount() != null) {
            // Es reverteix: es torna l'import a l'origen i es treu del destí.
            accountService.updateAccountBalance(transfer.getSourceAccount().getId(), transfer.getAmount(), "ADD");
            accountService.updateAccountBalance(transfer.getDestinationAccount().getId(), transfer.getAmount(), "SUBTRACT");
        }

        transferRepository.delete(transfer);
        return ResponseEntity.ok(Map.of("message", "Transfer deleted successfully"));
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    private static class TransferFailedException extends RuntimeException {
        TransferFailedException(String message) {
            super(message);
        }
    }
}
