package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransactionRepository;
import com.budgetai.backend.repository.TransferRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransferRepository transferRepository;

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    public List<Account> getActiveAccounts() {
        return accountRepository.findByActiveTrue();
    }

    public Optional<Account> getAccountById(Long id) {
        return accountRepository.findById(id);
    }

    @Transactional
    public Account createAccount(Account account) {
        return accountRepository.save(account);
    }

    // Actualització parcial: només s'apliquen els camps que arriben informats.
    // Abans es copiaven a cegues, de manera que un formulari que no enviés
    // "activa" i "moneda" deixava el compte desactivat i sense divisa.
    @Transactional
    public Account updateAccount(Long id, Account updatedAccount) {
        return accountRepository.findById(id)
                .map(account -> {
                    if (updatedAccount.getName() != null) account.setName(updatedAccount.getName());
                    if (updatedAccount.getType() != null) account.setType(updatedAccount.getType());
                    if (updatedAccount.getCurrentBalance() != null) account.setCurrentBalance(updatedAccount.getCurrentBalance());
                    if (updatedAccount.getCurrency() != null) account.setCurrency(updatedAccount.getCurrency());
                    if (updatedAccount.getActive() != null) account.setActive(updatedAccount.getActive());
                    if (updatedAccount.getColor() != null) account.setColor(updatedAccount.getColor());
                    return accountRepository.save(account);
                })
                .orElseThrow(() -> new RuntimeException("Account not found with id: " + id));
    }

    // Esborrar un compte amb moviments associats trencava la clau forana i
    // arribava a la interfície com un 500 genèric.
    @Transactional
    public void deleteAccount(Long id) {
        long transactionCount = transactionRepository.countByAccountId(id);
        if (transactionCount > 0) {
            throw new IllegalStateException(
                    "No es pot esborrar el compte: té " + transactionCount + " moviments associats");
        }

        if (!transferRepository.findByAccountId(id).isEmpty()) {
            throw new IllegalStateException(
                    "No es pot esborrar el compte: té transferències associades");
        }

        accountRepository.deleteById(id);
    }

    @Transactional
    public void updateAccountBalance(Long accountId, BigDecimal amount, String operation) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required to update a balance");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        // Un compte creat abans que la columna tingués valor per defecte pot
        // tenir el saldo a null; tractar-lo com a zero evita un NPE.
        BigDecimal balance = account.getCurrentBalance() != null
                ? account.getCurrentBalance()
                : BigDecimal.ZERO;

        if ("ADD".equals(operation)) {
            account.setCurrentBalance(balance.add(amount));
        } else if ("SUBTRACT".equals(operation)) {
            account.setCurrentBalance(balance.subtract(amount));
        } else {
            throw new IllegalArgumentException("Unknown balance operation: " + operation);
        }

        accountRepository.save(account);
    }
}
