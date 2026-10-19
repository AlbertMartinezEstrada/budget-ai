package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.RecurringTransaction;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.RecurringTransactionRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RecurringTransactionService {

    @Autowired
    private RecurringTransactionRepository recurringTransactionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryHierarchyService hierarchyService;

    private static final Set<String> TYPES = Set.of("EXPENSE", "INCOME");
    private static final Set<String> FREQUENCIES = Set.of("DIARIA", "SETMANAL", "MENSUAL", "TRIMESTRAL", "ANUAL");

    /**
     * Els recurrents que encara compten.
     *
     * Les versions tancades d'un cost fix (el lloguer d'abans de la pujada) es
     * guarden perquè els mesos passats no canviïn, però a la llista sortirien
     * com a duplicats del que hi ha ara.
     */
    public List<RecurringTransaction> getAllRecurringTransactions() {
        LocalDate today = LocalDate.now();
        return recurringTransactionRepository.findAll().stream()
                .filter(recurring -> recurring.getValidUntil() == null || !recurring.getValidUntil().isBefore(today))
                .toList();
    }

    public List<RecurringTransaction> getActiveRecurringTransactions() {
        return recurringTransactionRepository.findByActiveTrue();
    }

    public Optional<RecurringTransaction> getRecurringTransactionById(Long id) {
        return recurringTransactionRepository.findById(id);
    }

    @Transactional
    public RecurringTransaction createRecurringTransaction(RecurringTransaction recurring) {
        if (recurring.getCategory() != null) {
            recurring.setCategory(storedCategory(recurring.getCategory()));
        }
        requireValid(recurring);
        return recurringTransactionRepository.save(recurring);
    }

    /**
     * Actualització parcial: només canvia el que arriba.
     *
     * Abans es copiaven tots els camps, també els que el formulari no envia.
     * "activa" arribava buida i el recurrent deixava de comptar al pressupost
     * —que només llegeix els actius— cada vegada que s'editava; el compte i
     * l'empresa es perdien igual.
     */
    @Transactional
    public RecurringTransaction updateRecurringTransaction(Long id, RecurringTransaction updatedRecurring) {
        return recurringTransactionRepository.findById(id)
                .map(recurring -> {
                    if (updatedRecurring.getName() != null) recurring.setName(updatedRecurring.getName());
                    if (updatedRecurring.getCategory() != null) {
                        recurring.setCategory(storedCategory(updatedRecurring.getCategory()));
                    }
                    if (updatedRecurring.getCompany() != null) recurring.setCompany(updatedRecurring.getCompany());
                    if (updatedRecurring.getAmount() != null) recurring.setAmount(updatedRecurring.getAmount());
                    if (updatedRecurring.getType() != null) recurring.setType(updatedRecurring.getType());
                    if (updatedRecurring.getFrequency() != null) recurring.setFrequency(updatedRecurring.getFrequency());
                    if (updatedRecurring.getNextDate() != null) recurring.setNextDate(updatedRecurring.getNextDate());
                    if (updatedRecurring.getAccount() != null) recurring.setAccount(updatedRecurring.getAccount());
                    if (updatedRecurring.getActive() != null) recurring.setActive(updatedRecurring.getActive());
                    if (updatedRecurring.getDescription() != null) recurring.setDescription(updatedRecurring.getDescription());
                    requireValid(recurring);
                    return recurringTransactionRepository.save(recurring);
                })
                .orElseThrow(() -> new NotFoundException("not-found.recurring", id));
    }

    /** La categoria de debò: de la petició només en arriba l'id. */
    private Category storedCategory(Category requested) {
        if (requested.getId() == null) {
            throw new InvalidInputException("common.category-missing");
        }
        return categoryRepository.findById(requested.getId())
                .orElseThrow(() -> new NotFoundException("not-found.category", requested.getId()));
    }

    /**
     * Un recurrent ha de poder comptar al pressupost.
     *
     * Sense categoria, en un bloc o en una categoria de l'altre sentit no
     * comptava enlloc, i res no ho deia. Els camps obligatoris es comproven
     * aquí perquè, si no, la base de dades els rebutja amb un error que no
     * explica quin falta.
     */
    private void requireValid(RecurringTransaction recurring) {
        if (recurring.getName() == null || recurring.getName().isBlank()) {
            throw new InvalidInputException("common.name-missing");
        }
        if (recurring.getType() == null || !TYPES.contains(recurring.getType())) {
            throw new InvalidInputException("recurring.type-invalid");
        }
        if (recurring.getAmount() == null || recurring.getAmount().signum() <= 0) {
            throw new InvalidInputException("common.amount-positive");
        }
        if (recurring.getFrequency() == null || !FREQUENCIES.contains(recurring.getFrequency())) {
            throw new InvalidInputException("common.frequency-unknown");
        }
        if (recurring.getNextDate() == null) {
            throw new InvalidInputException("recurring.next-date-missing");
        }
        if (recurring.getCategory() == null) {
            throw new InvalidInputException("recurring.category-required");
        }
        hierarchyService.requireRecurringCategory(recurring.getCategory(), recurring.getType());
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
