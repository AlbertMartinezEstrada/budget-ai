package com.budgetai.backend.controller;

import com.budgetai.backend.model.Transfer;
import com.budgetai.backend.repository.TransferRepository;
import com.budgetai.backend.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transfers")
@CrossOrigin(origins = "*")
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

    @PostMapping
    public ResponseEntity<?> createTransfer(@RequestBody Transfer transfer) {
        try {
            // Validaciones
            if (transfer.getSourceAccount() == null || transfer.getDestinationAccount() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source and destination accounts are required"));
            }

            if (transfer.getSourceAccount().getId().equals(transfer.getDestinationAccount().getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Source and destination accounts must be different"));
            }

            if (transfer.getAmount() == null || transfer.getAmount() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Amount must be positive"));
            }

            // Verificar que la cuenta origen tenga saldo suficiente
            var sourceAccount = accountService.getAccountById(transfer.getSourceAccount().getId())
                    .orElseThrow(() -> new RuntimeException("Source account not found"));

            if (sourceAccount.getCurrentBalance() < transfer.getAmount()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Insufficient balance in source account"));
            }

            // Actualizar saldos
            accountService.updateAccountBalance(transfer.getSourceAccount().getId(), transfer.getAmount(), "SUBTRACT");
            accountService.updateAccountBalance(transfer.getDestinationAccount().getId(), transfer.getAmount(), "ADD");

            // Guardar transferencia
            Transfer saved = transferRepository.save(transfer);

            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTransfer(@PathVariable Long id) {
        transferRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Transfer deleted successfully"));
    }
}
