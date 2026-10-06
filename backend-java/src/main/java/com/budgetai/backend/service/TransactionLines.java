package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Els diners dels moviments, tal com compten.
 *
 * Un moviment sense parts és una sola línia, amb la seva categoria, la seva
 * marca d'exclòs i el seu deute. Un moviment dividit en dona una per part, i
 * el que digui el moviment deixa de comptar: si no, la transferència a Trade
 * Republic sortiria sencera a "Trade Republic" i, a més, repartida.
 *
 * **Tot el que sumi moviments per categoria o per deute ha de passar per
 * aquí.** Sumar directament els moviments funciona fins al dia que se'n divideix
 * un, i llavors aquell compta sencer a la categoria del moviment, sense cap
 * error que ho avisi.
 */
@Service
public class TransactionLines {

    private final TransactionRepository transactionRepository;
    private final TransactionPartRepository partRepository;

    public TransactionLines(TransactionRepository transactionRepository,
                            TransactionPartRepository partRepository) {
        this.transactionRepository = transactionRepository;
        this.partRepository = partRepository;
    }

    /**
     * Uns diners que compten pel seu compte: un moviment sencer o una part.
     *
     * La data i el tipus són sempre els del moviment: una part no pot passar
     * un altre dia ni anar en un altre sentit que la línia de l'extracte.
     *
     * @param part null si la línia és el moviment sencer
     */
    public record Line(Transaction transaction, TransactionPart part, BigDecimal amount,
                       Category category, boolean excludedFromBudget, Debt debt) {

        public LocalDate date() {
            return transaction.getDate();
        }

        public String type() {
            return transaction.getType();
        }

        public boolean isPart() {
            return part != null;
        }

        /** La de la part si en té; si no, la del moviment. */
        public String description() {
            if (part != null && part.getDescription() != null && !part.getDescription().isBlank()) {
                return part.getDescription();
            }
            return transaction.getShortDescription();
        }

        public boolean isBetween(LocalDate from, LocalDate to) {
            LocalDate date = date();
            return date != null && !date.isBefore(from) && !date.isAfter(to);
        }

        public boolean belongsTo(Debt candidate) {
            return debt != null && candidate.getId() != null && candidate.getId().equals(debt.getId());
        }
    }

    /** Totes les línies de tots els moviments. */
    public List<Line> all() {
        return expand(transactionRepository.findAll(), partRepository.findAll());
    }

    /**
     * Converteix moviments en línies.
     *
     * @param parts poden ser de qualsevol moviment: cadascuna es posa amb el seu
     */
    public static List<Line> expand(List<Transaction> transactions, List<TransactionPart> parts) {
        Map<Long, List<TransactionPart>> partsByTransaction = new HashMap<>();
        parts.stream()
                .sorted(Comparator.comparing(TransactionPart::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(part -> partsByTransaction
                        .computeIfAbsent(part.getTransaction().getId(), missingTransactionId -> new ArrayList<>())
                        .add(part));

        List<Line> lines = new ArrayList<>();
        for (Transaction transaction : transactions) {
            List<TransactionPart> own = transaction.getId() != null
                    ? partsByTransaction.getOrDefault(transaction.getId(), List.of())
                    : List.of();
            if (own.isEmpty()) {
                lines.add(new Line(transaction, null,
                        transaction.getAmount() != null ? transaction.getAmount() : BigDecimal.ZERO,
                        transaction.getCategory(), transaction.isExcludedFromBudget(), transaction.getDebt()));
                continue;
            }
            for (TransactionPart part : own) {
                lines.add(new Line(transaction, part, part.getAmount(),
                        part.getCategory(), part.isExcludedFromBudget(), part.getDebt()));
            }
        }
        return lines;
    }
}
