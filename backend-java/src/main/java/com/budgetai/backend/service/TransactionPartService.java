package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dividir un moviment en parts, o treure'n la divisió.
 *
 * Les parts no mouen cap saldo: el moviment ja el va moure pel total. Només
 * diuen com es reparteix, i per això l'única condició dura és que sumin
 * exactament l'import del moviment. Amb un cèntim de diferència, el pressupost
 * i el compte deixarien de quadrar.
 */
@Service
public class TransactionPartService {

    private final TransactionRepository transactionRepository;
    private final TransactionPartRepository partRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryHierarchyService hierarchyService;
    private final DebtService debtService;

    public TransactionPartService(TransactionRepository transactionRepository,
                                  TransactionPartRepository partRepository,
                                  CategoryRepository categoryRepository,
                                  CategoryHierarchyService hierarchyService,
                                  DebtService debtService) {
        this.transactionRepository = transactionRepository;
        this.partRepository = partRepository;
        this.categoryRepository = categoryRepository;
        this.hierarchyService = hierarchyService;
        this.debtService = debtService;
    }

    /** Hi posa les parts, per ensenyar-les. Una sola consulta per a tots els moviments. */
    public List<Transaction> withParts(List<Transaction> transactions) {
        List<Long> ids = transactions.stream()
                .map(Transaction::getId)
                .filter(id -> id != null)
                .toList();
        Map<Long, List<TransactionPart>> partsByTransaction = new HashMap<>();
        if (!ids.isEmpty()) {
            for (TransactionPart part : partRepository.findByTransactions(ids)) {
                partsByTransaction.computeIfAbsent(part.getTransaction().getId(), missingTransactionId -> new ArrayList<>())
                        .add(part);
            }
        }
        for (Transaction transaction : transactions) {
            transaction.setParts(partsByTransaction.getOrDefault(transaction.getId(), List.of()));
        }
        return transactions;
    }

    public boolean isSplit(Long transactionId) {
        return !partRepository.findByTransactions(List.of(transactionId)).isEmpty();
    }

    /**
     * Substitueix les parts d'un moviment per les que arriben.
     *
     * Una llista buida treu la divisió: el moviment torna a comptar sencer amb
     * la seva categoria. Una sola part no es pot: seria el moviment sencer amb
     * una altra categoria, i per això ja hi ha l'edició.
     *
     * Quan el moviment queda dividit, el seu deute es buida: el vincle passa a
     * la part que toca, i deixar-lo també al moviment el faria sortir a la
     * fitxa del deute per partida doble.
     */
    @Transactional
    public Transaction replace(Long transactionId, List<TransactionPart> requested) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<TransactionPart> wanted = requested != null ? requested : List.of();
        if (wanted.size() == 1) {
            throw new IllegalArgumentException(
                    "Per dividir-lo calen almenys dues parts. Per canviar-ne la categoria, edita el moviment.");
        }

        List<TransactionPart> parts = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TransactionPart request : wanted) {
            TransactionPart part = new TransactionPart();
            part.setTransaction(transaction);
            part.setAmount(validAmount(request.getAmount()));
            part.setCategory(leafOf(request.getCategory()));
            part.setExcludedFromBudget(Boolean.TRUE.equals(request.getExcludedFromBudget()));
            part.setDebt(debtService.resolveForLink(request.getDebt()));
            part.setDescription(request.getDescription() != null && !request.getDescription().isBlank()
                    ? request.getDescription().trim()
                    : null);
            parts.add(part);
            total = total.add(part.getAmount());
        }
        if (!parts.isEmpty()) {
            requireSameTotal(total, transaction.getAmount());
        }

        partRepository.deleteAll(partRepository.findByTransactions(List.of(transactionId)));
        if (!parts.isEmpty()) {
            transaction.setDebt(null);
            transactionRepository.save(transaction);
            partRepository.saveAll(parts);
        }
        return withParts(new ArrayList<>(List.of(transaction))).get(0);
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Cada part ha de tenir un import més gran que zero.");
        }
        // NUMERIC(15,2) arrodoniria el tercer decimal en silenci, i les parts
        // que han passat la comprovació de la suma deixarien de sumar el total.
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Els imports de les parts porten com a molt dos decimals.");
        }
        return amount;
    }

    private Category leafOf(Category reference) {
        if (reference == null || reference.getId() == null) {
            throw new IllegalArgumentException("Cada part ha de tenir una categoria.");
        }
        Category category = categoryRepository.findById(reference.getId())
                .orElseThrow(() -> new IllegalArgumentException("La categoria triada ja no existeix."));
        if (hierarchyService.isGroup(category.getId())) {
            throw new IllegalArgumentException("La categoria \"" + category.getName()
                    + "\" és un grup: tria'n una de concreta.");
        }
        return category;
    }

    private static void requireSameTotal(BigDecimal partsTotal, BigDecimal transactionAmount) {
        int comparison = partsTotal.compareTo(transactionAmount);
        if (comparison == 0) return;
        BigDecimal difference = partsTotal.subtract(transactionAmount).abs();
        throw new IllegalArgumentException("Les parts sumen " + partsTotal.toPlainString()
                + " € i el moviment és de " + transactionAmount.toPlainString() + " €: "
                + (comparison < 0 ? "en falten " : "en sobren ") + difference.toPlainString() + " €.");
    }

    /** El moviment no hi és. El controlador en fa un 404. */
    public static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(Long id) {
            super("No existeix el moviment " + id);
        }
    }
}
