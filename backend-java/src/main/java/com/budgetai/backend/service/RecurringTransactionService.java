package com.budgetai.backend.service;

import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class RecurringTransactionService {

    @Autowired
    private RecurringTransactionRepository recurringTransactionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountService accountService;

    public List<RecurringTransaction> getAllRecurringTransactions() {
        return recurringTransactionRepository.findAll();
    }

    public List<RecurringTransaction> getActiveRecurringTransactions() {
        return recurringTransactionRepository.findByActiveTrue();
    }

    public Optional<RecurringTransaction> getRecurringTransactionById(Long id) {
        return recurringTransactionRepository.findById(id);
    }

    @Transactional
    public RecurringTransaction createRecurringTransaction(RecurringTransaction recurring) {
        return recurringTransactionRepository.save(recurring);
    }

    @Transactional
    public RecurringTransaction updateRecurringTransaction(Long id, RecurringTransaction updatedRecurring) {
        return recurringTransactionRepository.findById(id)
                .map(recurring -> {
                    recurring.setName(updatedRecurring.getName());
                    recurring.setCategory(updatedRecurring.getCategory());
                    recurring.setCompany(updatedRecurring.getCompany());
                    recurring.setAmount(updatedRecurring.getAmount());
                    recurring.setType(updatedRecurring.getType());
                    recurring.setFrequency(updatedRecurring.getFrequency());
                    recurring.setNextDate(updatedRecurring.getNextDate());
                    recurring.setAccount(updatedRecurring.getAccount());
                    recurring.setActive(updatedRecurring.getActive());
                    recurring.setDescription(updatedRecurring.getDescription());
                    return recurringTransactionRepository.save(recurring);
                })
                .orElseThrow(() -> new RuntimeException("Recurring transaction not found with id: " + id));
    }

    @Transactional
    public void deleteRecurringTransaction(Long id) {
        recurringTransactionRepository.deleteById(id);
    }

    @Transactional
    public void processDueRecurringTransactions() {
        LocalDate today = LocalDate.now();
        List<RecurringTransaction> dueTransactions = recurringTransactionRepository.findDueRecurringTransactions(today);

        for (RecurringTransaction recurring : dueTransactions) {
            // Crear la transacción real
            Transaction transaction = new Transaction();
            transaction.setDate(recurring.getNextDate());
            transaction.setCategory(recurring.getCategory());
            transaction.setCompany(recurring.getCompany());
            transaction.setAmount(recurring.getAmount());
            transaction.setType(recurring.getType());
            transaction.setShortDescription(recurring.getName() + " (recurrente)");
            transaction.setAccount(recurring.getAccount());

            transactionRepository.save(transaction);

            // Actualizar saldo de la cuenta
            if (recurring.getAccount() != null) {
                if ("EXPENSE".equals(recurring.getType())) {
                    accountService.updateAccountBalance(recurring.getAccount().getId(), recurring.getAmount(), "SUBTRACT");
                } else if ("INCOME".equals(recurring.getType())) {
                    accountService.updateAccountBalance(recurring.getAccount().getId(), recurring.getAmount(), "ADD");
                }
            }

            // Actualizar la próxima fecha según la frecuencia
            LocalDate nextDate = calculateNextDate(recurring.getNextDate(), recurring.getFrequency());
            recurring.setNextDate(nextDate);
            recurringTransactionRepository.save(recurring);
        }
    }

    private LocalDate calculateNextDate(LocalDate currentDate, String frequency) {
        return switch (frequency) {
            case "DIARIA" -> currentDate.plusDays(1);
            case "SETMANAL" -> currentDate.plusWeeks(1);
            case "MENSUAL" -> currentDate.plusMonths(1);
            case "TRIMESTRAL" -> currentDate.plusMonths(3);
            case "ANUAL" -> currentDate.plusYears(1);
            default -> currentDate.plusMonths(1);
        };
    }
}
