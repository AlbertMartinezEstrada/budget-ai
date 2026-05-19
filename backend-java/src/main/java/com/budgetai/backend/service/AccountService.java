package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;

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

    @Transactional
    public Account updateAccount(Long id, Account updatedAccount) {
        return accountRepository.findById(id)
                .map(account -> {
                    account.setName(updatedAccount.getName());
                    account.setType(updatedAccount.getType());
                    account.setCurrentBalance(updatedAccount.getCurrentBalance());
                    account.setCurrency(updatedAccount.getCurrency());
                    account.setActive(updatedAccount.getActive());
                    account.setColor(updatedAccount.getColor());
                    return accountRepository.save(account);
                })
                .orElseThrow(() -> new RuntimeException("Account not found with id: " + id));
    }

    @Transactional
    public void deleteAccount(Long id) {
        accountRepository.deleteById(id);
    }

    @Transactional
    public void updateAccountBalance(Long accountId, Double amount, String operation) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if ("ADD".equals(operation)) {
            account.setCurrentBalance(account.getCurrentBalance() + amount);
        } else if ("SUBTRACT".equals(operation)) {
            account.setCurrentBalance(account.getCurrentBalance() - amount);
        }

        accountRepository.save(account);
    }
}
