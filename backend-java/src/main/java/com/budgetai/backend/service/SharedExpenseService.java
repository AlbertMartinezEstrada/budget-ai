package com.budgetai.backend.service;

import com.budgetai.backend.i18n.Message;
import com.budgetai.backend.i18n.Messages;
import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.SharedExpense;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Una despesa compartida: un pagament que era de diverses persones i els
 * Bizums que me'n tornen la part.
 *
 * Pago 40 € d'un sopar entre quatre i en rebo tres Bizums de 10 €. Sense res
 * més, el pressupost hi veia 40 € de despesa a Bars i restaurants i 30 €
 * d'ingressos que no són cap sou. Del sopar, de meu en són 10, i els Bizums
 * només em tornen el que vaig avançar.
 *
 * Queda així:
 *
 *   10 € Bars i restaurants   compta              la meva part
 *   30 € Bars i restaurants   no compta → deute   el que vaig avançar
 *   els Bizums                no compten → deute  em tornen el que vaig avançar
 *
 * El deute és dels que em deuen: diu quant vaig avançar, quant m'han tornat i
 * quant en falta. És la regla dels préstecs (AGENTS.md, 11) amb l'opció de no
 * comptar ni el que avanço ni el que em tornen: no és despesa meva ni ingrés.
 *
 * No es mou cap saldo: ni les parts ni la marca d'exclòs mouen diners, i el
 * pagament i els Bizums ja els van moure en desar-se.
 */
@Service
public class SharedExpenseService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryHierarchyService hierarchyService;
    private final TransactionPartService partService;
    private final DebtService debtService;

    public SharedExpenseService(TransactionRepository transactionRepository,
                                CategoryRepository categoryRepository,
                                CategoryHierarchyService hierarchyService,
                                TransactionPartService partService,
                                DebtService debtService) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.hierarchyService = hierarchyService;
        this.partService = partService;
        this.debtService = debtService;
    }

    /**
     * Comparteix un pagament ja desat i hi vincula els Bizums ja desats que el
     * tornen.
     *
     * @param myShare      la meva part; zero si el vaig pagar sencer per uns altres
     * @param name         el nom del deute; buit, el de l'empresa del pagament
     * @param repaymentIds els ingressos que el tornen; poden ser cap, si encara no han arribat
     * @return el deute nou, amb el que s'ha cobrat i el que en falta
     */
    @Transactional
    public Debt share(Long paymentId, BigDecimal myShare, String name, List<Long> repaymentIds) {
        Transaction payment = transactionRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("not-found.transaction", paymentId));
        if (partService.isSplit(payment.getId())) throw rowError(payment, "shared.payment-split");
        List<Transaction> repayments = savedRepayments(repaymentIds, paymentId);

        Debt debt = open(payment, myShare, name, repayments);
        List<TransactionPart> parts = partsOf(payment, payment.getCategory(), myShare, debt);
        if (parts.isEmpty()) {
            transactionRepository.save(payment);
        } else {
            partService.store(payment, partService.validate(payment, parts));
        }
        transactionRepository.saveAll(repayments);
        return debtService.get(debt.getId());
    }

    /**
     * Les despeses compartides d'un extracte que s'està important.
     *
     * Es crida dins de la mateixa transacció que la importació, abans de
     * validar les parts i de moure cap saldo: crea els deutes, posa les parts
     * al pagament (les validarà i desarà la importació, com les de qualsevol
     * fila dividida) i vincula els Bizums. Si alguna cosa no quadra, no
     * s'importa res.
     *
     * @param rows les files que es desaran, encara sense desar
     * @return quantes despeses compartides s'han creat
     */
    public int applyToImport(List<Transaction> rows) {
        Map<String, Transaction> payments = new LinkedHashMap<>();
        for (Transaction row : rows) {
            SharedExpense shared = row.getSharedExpense();
            if (shared == null) continue;
            if (shared.key() == null || shared.key().isBlank() || payments.putIfAbsent(shared.key(), row) != null) {
                throw rowError(row, "shared.import-key");
            }
        }

        Map<String, List<Transaction>> repaymentsByKey = new LinkedHashMap<>();
        for (Transaction row : rows) {
            String key = row.getRepaysSharedExpense();
            if (key == null || key.isBlank()) continue;
            // El pagament no s'importa (l'han desmarcat, o ja hi era): el
            // Bizum no tindria res a tornar.
            if (!payments.containsKey(key)) throw rowError(row, "shared.import-payment-missing");
            repaymentsByKey.computeIfAbsent(key, missingKey -> new ArrayList<>()).add(row);
        }

        payments.forEach((key, payment) -> {
            if (payment.getParts() != null && !payment.getParts().isEmpty()) {
                throw rowError(payment, "shared.payment-split");
            }
            List<Transaction> repayments = repaymentsByKey.getOrDefault(key, List.of());
            repayments.forEach(this::requireRepayable);

            SharedExpense shared = payment.getSharedExpense();
            Debt debt = open(payment, shared.myShare(), shared.name(), repayments);
            List<TransactionPart> parts = partsOf(payment, categoryOfImportRow(payment), shared.myShare(), debt);
            if (!parts.isEmpty()) payment.setParts(parts);
        });
        return payments.size();
    }

    /**
     * Comprova el pagament i els Bizums, crea el deute i els hi vincula.
     *
     * Els Bizums queden exclosos i vinculats; el pagament, si la meva part és
     * zero, també: no hi ha res a dividir (una sola part seria el moviment
     * sencer). Si n'hi ha, el divideix qui crida, amb partsOf.
     */
    private Debt open(Transaction payment, BigDecimal myShare, String name, List<Transaction> repayments) {
        requireShareable(payment);
        BigDecimal share = validShare(payment, myShare);
        BigDecimal owed = payment.getAmount().subtract(share);

        BigDecimal repaid = repayments.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (repaid.compareTo(owed) > 0) {
            throw new InvalidInputException("shared.repaid-too-much", "repaid", repaid, "owed", owed);
        }

        Debt request = new Debt();
        request.setName(name != null && !name.isBlank() ? name.trim() : payment.getEmpresa());
        request.setDirection(Debt.OWED_TO_ME);
        request.setAmount(owed);
        request.setDate(payment.getDate());
        request.setRepaymentPlan(Debt.PLAN_FREE);
        Debt debt = debtService.create(request);

        if (share.signum() == 0) {
            payment.setExcludedFromBudget(true);
            payment.setDebt(debt);
            payment.setDebtReceipt(null);
        }
        for (Transaction repayment : repayments) {
            repayment.setExcludedFromBudget(true);
            repayment.setDebt(debt);
            repayment.setDebtReceipt(null);
        }
        return debt;
    }

    /**
     * Les dues parts del pagament: la meva, que compta a la seva categoria, i
     * la que em deuen, a la mateixa categoria però sense comptar i vinculada al
     * deute. Cap, si la meva part és zero.
     */
    private List<TransactionPart> partsOf(Transaction payment, Category category, BigDecimal myShare, Debt debt) {
        BigDecimal share = validShare(payment, myShare);
        if (share.signum() == 0) return List.of();

        TransactionPart mine = new TransactionPart();
        mine.setAmount(share);
        mine.setCategory(category);
        mine.setExcludedFromBudget(false);
        mine.setDescription(Messages.get("shared.part-mine"));

        TransactionPart owed = new TransactionPart();
        owed.setAmount(payment.getAmount().subtract(share));
        owed.setCategory(category);
        owed.setExcludedFromBudget(true);
        owed.setDebtId(debt.getId());
        owed.setDescription(Messages.get("shared.part-owed"));
        return List.of(mine, owed);
    }

    /** Un pagament: una despesa, sense deute ni traspàs. */
    private static void requireShareable(Transaction payment) {
        if (!"EXPENSE".equals(payment.getType())) throw rowError(payment, "shared.payment-not-expense");
        if (isTransfer(payment)) throw rowError(payment, "shared.payment-transfer");
        if (payment.getDebt() != null && payment.getDebt().getId() != null && payment.getDebt().getId() > 0) {
            throw rowError(payment, "shared.payment-has-debt");
        }
    }

    /** Un Bizum que torna una part: un ingrés, sense dividir, sense deute ni traspàs. */
    private void requireRepayable(Transaction repayment) {
        if (!"INCOME".equals(repayment.getType())) throw rowError(repayment, "shared.repayment-not-income");
        if (isTransfer(repayment)) throw rowError(repayment, "shared.repayment-transfer");
        boolean split = repayment.getId() != null
                ? partService.isSplit(repayment.getId())
                : repayment.getParts() != null && !repayment.getParts().isEmpty();
        if (split) throw rowError(repayment, "shared.repayment-split");
        if (repayment.getDebt() != null && repayment.getDebt().getId() != null && repayment.getDebt().getId() > 0) {
            throw rowError(repayment, "shared.repayment-has-debt");
        }
    }

    /** Un -1 a una fila importada vol dir «ja no és un traspàs», no un compte. */
    private static boolean isTransfer(Transaction row) {
        return row.getCounterpartAccount() != null && row.getCounterpartAccount().getId() != null
                && row.getCounterpartAccount().getId() > 0;
    }

    private List<Transaction> savedRepayments(List<Long> ids, Long paymentId) {
        List<Transaction> repayments = new ArrayList<>();
        // Un mateix id dues vegades és un clic de més, no dos Bizums.
        for (Long id : new LinkedHashSet<>(ids != null ? ids : List.<Long>of())) {
            if (Objects.equals(id, paymentId)) continue;
            Transaction repayment = transactionRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("not-found.transaction", id));
            requireRepayable(repayment);
            repayments.add(repayment);
        }
        return repayments;
    }

    /** Entre zero i l'import del pagament, sense arribar-hi: si fos tot meu, no hi hauria res a compartir. */
    private static BigDecimal validShare(Transaction payment, BigDecimal myShare) {
        BigDecimal total = payment.getAmount();
        if (myShare == null || myShare.signum() < 0 || myShare.compareTo(total) >= 0) {
            throw new InvalidInputException("shared.share-out-of-range", "total", total.setScale(2, RoundingMode.HALF_UP));
        }
        if (myShare.stripTrailingZeros().scale() > 2) throw new InvalidInputException("split.two-decimals");
        return myShare.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * La categoria d'una fila que encara no s'ha desat, com la resoldrà la
     * importació: pel nom, i «Altres» si no en porta. Ha de ser una fulla.
     */
    private Category categoryOfImportRow(Transaction row) {
        String name = row.getCategoryName() != null && !row.getCategoryName().isBlank() ? row.getCategoryName() : "Altres";
        Category category = categoryRepository.findByName(name)
                .orElseThrow(() -> new InvalidInputException("common.category-gone"));
        if (hierarchyService.isGroup(category.getId())) {
            throw new InvalidInputException("common.category-is-group", "name", category.getName());
        }
        return category;
    }

    /** «Moviment del 2026-10-01, Bar Pepe, 40,00 €: ...», per dir quina fila falla. */
    private static InvalidInputException rowError(Transaction row, String key) {
        Message describedRow = Message.of("upload.row", "date", row.getDate(), "company", row.getEmpresa(),
                "amount", row.getAmount() != null ? row.getAmount() : "?");
        return new InvalidInputException(Message.of(key, "row", describedRow));
    }
}
