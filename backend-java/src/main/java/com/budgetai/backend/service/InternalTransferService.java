package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Traspassos entre comptes propis: Principal → Revolut, Principal → Trade
 * Republic.
 *
 * Un traspàs no és ni despesa ni ingrés: els diners continuen sent meus. El que
 * compta és què se'n fa:
 *
 *   ENTRE COMPTES DEL DIA A DIA   no compta. Compta el que es paga des de
 *                                 Revolut, cadascú a la seva categoria.
 *   CAP A UN COMPTE D'ESTALVI     compta com a estalvi, a la seva categoria
 *                                 (Estalvis). I el que en torna, el resta.
 *
 * Abans la regla era la contrària: el diner comptava en sortir del compte
 * principal, i el que es pagava des de Revolut es marcava com a "no compta".
 * Funcionava per a l'estalvi, però tot el que es pagava des de Revolut acabava
 * en un sol moviment sense la seva categoria.
 *
 * Es desa una sola fila per traspàs, al compte de l'extracte d'on surt, i mou
 * el saldo dels dos comptes. La línia de l'altre extracte és el mateix diner:
 * en importar-lo, es reconeix i no es torna a desar.
 *
 * Si l'altra pota ja s'havia desat com un moviment normal —els dos extractes
 * importats abans de marcar el traspàs—, el mateix diner hi és dues vegades:
 * una despesa en un compte i un ingrés en l'altre, que l'anàlisi comptava
 * totes dues. Es poden trobar (unmarkedPairs) i ajuntar en un sol traspàs; i
 * en marcar-ne un, l'altra pota es busca sola (findUnmarkedOtherLeg) perquè
 * el saldo no es mogui dos cops.
 */
@Service
public class InternalTransferService {

    /**
     * Dies de marge entre les dues potes. Un traspàs entre bancs pot sortir un
     * dia i arribar l'endemà o, en cap de setmana, tres dies després.
     */
    private static final long MATCHING_DAYS = 3;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionPartRepository partRepository;
    private final AccountService accountService;

    public InternalTransferService(AccountRepository accountRepository,
                                   TransactionRepository transactionRepository,
                                   TransactionPartRepository partRepository,
                                   AccountService accountService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.partRepository = partRepository;
        this.accountService = accountService;
    }

    /** Les dues potes d'un traspàs desades com a moviments normals. */
    public record Pair(Transaction outgoing, Transaction incoming) {
    }

    /**
     * El compte de l'altre costat, resolt contra la base de dades.
     *
     * @param requested el que arriba de la petició: només en porta l'id, i un
     *                  negatiu vol dir "ja no és un traspàs"
     * @param own       el compte del moviment
     * @return el compte, o null si no és cap traspàs
     */
    public Account resolve(Account requested, Account own) {
        if (requested == null || requested.getId() == null || requested.getId() < 0) return null;
        Account counterpart = accountRepository.findById(requested.getId())
                .orElseThrow(() -> new NotFoundException("el compte", requested.getId()));
        if (own != null && counterpart.getId().equals(own.getId())) {
            throw new IllegalArgumentException("Un traspàs ha d'anar a un altre compte: «"
                    + counterpart.getName() + "» és el compte del mateix moviment.");
        }
        return counterpart;
    }

    /**
     * Si un traspàs aparta o recupera estalvi: un costat és d'estalvi i l'altre
     * no. Entre dos del dia a dia, o entre dos d'estalvi, els diners només
     * canvien de lloc.
     */
    public static boolean movesSavings(Account own, Account counterpart) {
        boolean ownIsSavings = own != null && own.isSavings();
        return counterpart.isSavings() != ownIsSavings;
    }

    /**
     * En quin sentit mou estalvi un moviment.
     *
     * +1 si els diners passen d'un compte del dia a dia a un d'estalvi o
     * d'inversió: s'han apartat. −1 si en tornen. 0 si no és cap traspàs, o
     * si els dos comptes són del mateix tipus.
     *
     * Es mira on entren i d'on surten els diners, no el tipus del moviment:
     * el traspàs es desa al compte del primer extracte que s'importa, i
     * 1.276 € cap a Trade Republic poden ser una despesa a Principal o un
     * ingrés a Trade Republic. Tots dos són estalvi.
     */
    public static int savingsDirection(Transaction transaction) {
        if (!transaction.isTransfer() || transaction.getAccount() == null) return 0;
        Account from;
        Account to;
        if ("EXPENSE".equals(transaction.getType())) {
            from = transaction.getAccount();
            to = transaction.getCounterpartAccount();
        } else if ("INCOME".equals(transaction.getType())) {
            from = transaction.getCounterpartAccount();
            to = transaction.getAccount();
        } else {
            return 0;
        }
        if (to.isSavings() && !from.isSavings()) return 1;
        if (from.isSavings() && !to.isSavings()) return -1;
        return 0;
    }

    /**
     * Fixa el compte de l'altre costat i, si el traspàs no compta, el marca
     * com a exclòs. Es desa marcat, i no es calcula en llegir, perquè així el
     * pressupost, les deudes, l'anàlisi i el tauler el respecten sense saber
     * res de traspassos.
     *
     * Un traspàs d'estalvi no es toca: si l'usuari l'ha marcat com a exclòs,
     * és decisió seva.
     *
     * @param own el compte del moviment, sencer: del tipus depèn si compta
     */
    public void link(Transaction transaction, Account own, Account counterpart) {
        transaction.setCounterpartAccount(counterpart);
        if (counterpart != null && !movesSavings(own, counterpart)) {
            transaction.setExcludedFromBudget(true);
        }
    }

    /** Suma o resta al compte de l'altre costat el que el moviment treu o posa al seu. */
    public void applyToCounterpart(Transaction transaction) {
        moveCounterpart(transaction, false);
    }

    /** El contrari: per esborrar el moviment o abans de tornar-lo a aplicar en editar-lo. */
    public void revertFromCounterpart(Transaction transaction) {
        moveCounterpart(transaction, true);
    }

    private void moveCounterpart(Transaction transaction, boolean revert) {
        Account counterpart = transaction.getCounterpartAccount();
        if (counterpart == null || counterpart.getId() == null || transaction.getAmount() == null) return;

        // Una despesa surt del compte del moviment i entra a l'altre; un
        // ingrés hi entra des de l'altre.
        boolean entersCounterpart;
        if ("EXPENSE".equals(transaction.getType())) entersCounterpart = true;
        else if ("INCOME".equals(transaction.getType())) entersCounterpart = false;
        else return;

        boolean add = entersCounterpart != revert;
        accountService.updateAccountBalance(counterpart.getId(), transaction.getAmount(), add ? "ADD" : "SUBTRACT");
    }

    /**
     * Prepara per a la revisió les línies d'un extracte que són traspassos.
     *
     * Primer, les que una regla ha marcat com a traspàs: si apunten al mateix
     * compte de l'extracte no ho són (la regla «REVOLUT» → Revolut, llegint
     * l'extracte de Revolut), i si són entre comptes del dia a dia es marquen
     * com a excloses perquè la pantalla ensenyi que no comptaran.
     *
     * Després, les que són l'altra pota d'un traspàs ja desat: mateix import,
     * sentit contrari i dates a menys de tres dies. Es diu quin és perquè la
     * pantalla les desmarqui: importar-les mouria el saldo una altra vegada.
     * Cada traspàs desat es fa servir com a molt per una línia, perquè dos
     * traspassos iguals el mateix dia són dos moviments.
     */
    public void prepareForReview(List<Transaction> incoming, Account importAccount) {
        for (Transaction line : incoming) {
            Account requested = line.getCounterpartAccount();
            if (requested == null) continue;
            Optional<Account> counterpart = Optional.ofNullable(requested.getId())
                    .flatMap(accountRepository::findById)
                    .filter(candidate -> importAccount == null || !candidate.getId().equals(importAccount.getId()));
            link(line, importAccount, counterpart.orElse(null));
        }

        if (importAccount == null) return;
        List<Transaction> unmatched = new ArrayList<>(transactionRepository.findTransfersWithCounterpart(importAccount.getId()));
        for (Transaction line : incoming) {
            Optional<Transaction> stored = unmatched.stream()
                    .filter(candidate -> isOtherLeg(candidate, line, importAccount))
                    .min(Comparator.comparingLong(candidate ->
                            Math.abs(ChronoUnit.DAYS.between(candidate.getDate(), line.getDate()))));
            if (stored.isEmpty()) continue;

            unmatched.remove(stored.get());
            line.setRegisteredTransferId(stored.get().getId());
            // Per dir des d'on: és el mateix traspàs vist des de l'altre compte.
            line.setCounterpartAccount(stored.get().getAccount());
        }
    }

    /**
     * Els traspassos que no estan marcats: una despesa en un compte i un
     * ingrés del mateix import en un altre, a menys de tres dies.
     *
     * És una proposta, no una certesa: 50 € pagats en una botiga i 50 € que
     * un amic envia a Revolut l'endemà també hi encaixen. Per això només es
     * llisten, i ajuntar-los ho decideix l'usuari.
     *
     * No n'hi entren els moviments dividits ni els vinculats a un deute: ja
     * diuen d'una altra manera què són. Cada moviment va com a molt a un
     * parell, el de la data més propera.
     *
     * @return els més recents primer
     */
    public List<Pair> unmarkedPairs() {
        Set<Long> split = partRepository.findAll().stream()
                .map(TransactionPart::getTransaction)
                .filter(Objects::nonNull)
                .map(Transaction::getId)
                .collect(Collectors.toSet());
        List<Transaction> legs = transactionRepository.findAll().stream()
                .filter(transaction -> isPlainLeg(transaction, split))
                .toList();

        Map<BigDecimal, List<Transaction>> incomesByAmount = new HashMap<>();
        for (Transaction leg : legs) {
            if ("INCOME".equals(leg.getType())) {
                incomesByAmount.computeIfAbsent(amountKey(leg), missingAmount -> new ArrayList<>()).add(leg);
            }
        }

        Set<Long> used = new HashSet<>();
        List<Pair> pairs = new ArrayList<>();
        legs.stream()
                .filter(leg -> "EXPENSE".equals(leg.getType()))
                .sorted(Comparator.comparing(Transaction::getDate).thenComparing(Transaction::getId))
                .forEach(outgoing -> incomesByAmount.getOrDefault(amountKey(outgoing), List.of()).stream()
                        .filter(incoming -> !used.contains(incoming.getId()))
                        .filter(incoming -> !incoming.getAccount().getId().equals(outgoing.getAccount().getId()))
                        .filter(incoming -> daysBetween(outgoing, incoming) <= MATCHING_DAYS)
                        .min(Comparator.comparingLong((Transaction incoming) -> daysBetween(outgoing, incoming))
                                .thenComparing(Transaction::getId))
                        .ifPresent(incoming -> {
                            used.add(incoming.getId());
                            pairs.add(new Pair(outgoing, incoming));
                        }));

        pairs.sort(Comparator.comparing((Pair pair) -> pair.outgoing().getDate()).reversed());
        return pairs;
    }

    /**
     * L'altra pota d'un traspàs, si s'havia desat com un moviment normal: al
     * compte de l'altre costat, en sentit contrari, pel mateix import i a
     * menys de tres dies. La de la data més propera.
     *
     * El traspàs ja mou el saldo d'aquell compte; si l'altra pota es quedés,
     * el mouria una segona vegada i a l'anàlisi hi hauria un ingrés o una
     * despesa que no han existit.
     */
    public Optional<Transaction> findUnmarkedOtherLeg(Transaction transfer) {
        Account counterpart = transfer.getCounterpartAccount();
        if (counterpart == null || counterpart.getId() == null || transfer.getAmount() == null
                || transfer.getDate() == null || transfer.getType() == null) {
            return Optional.empty();
        }
        String otherType = "EXPENSE".equals(transfer.getType()) ? "INCOME" : "EXPENSE";
        List<Transaction> candidates = transactionRepository.findPossibleOtherLegs(counterpart.getId(),
                        transfer.getAmount(), transfer.getDate().minusDays(MATCHING_DAYS),
                        transfer.getDate().plusDays(MATCHING_DAYS)).stream()
                .filter(candidate -> !Objects.equals(candidate.getId(), transfer.getId()))
                .filter(candidate -> otherType.equals(candidate.getType()))
                .toList();
        if (candidates.isEmpty()) return Optional.empty();

        Set<Long> split = partRepository.findByTransactions(candidates.stream().map(Transaction::getId).toList())
                .stream()
                .map(part -> part.getTransaction().getId())
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(candidate -> !split.contains(candidate.getId()))
                .min(Comparator.comparingLong((Transaction candidate) -> daysBetween(transfer, candidate))
                        .thenComparing(Transaction::getId));
    }

    /**
     * Comprova que dos moviments es poden ajuntar en un traspàs: una despesa
     * i un ingrés del mateix import, en comptes diferents, que encara no són
     * cap traspàs i no estan dividits ni vinculats a cap deute.
     */
    public void requireJoinable(Transaction outgoing, Transaction incoming) {
        if (!"EXPENSE".equals(outgoing.getType()) || !"INCOME".equals(incoming.getType())) {
            throw new IllegalArgumentException("Un traspàs surt d'un compte i entra a un altre: "
                    + "cal una despesa i un ingrés.");
        }
        if (outgoing.getAccount() == null || incoming.getAccount() == null
                || outgoing.getAccount().getId().equals(incoming.getAccount().getId())) {
            throw new IllegalArgumentException("Els dos moviments han de ser de comptes diferents.");
        }
        if (outgoing.getAmount() == null || incoming.getAmount() == null
                || outgoing.getAmount().compareTo(incoming.getAmount()) != 0) {
            throw new IllegalArgumentException("Els dos moviments no són del mateix import.");
        }
        if (outgoing.isTransfer() || incoming.isTransfer()) {
            throw new IllegalArgumentException("Un dels dos ja és un traspàs.");
        }
        if (outgoing.getDebt() != null || incoming.getDebt() != null) {
            throw new IllegalArgumentException("Un dels dos està vinculat a un deute: desvincula'l abans.");
        }
        if (!partRepository.findByTransactions(List.of(outgoing.getId(), incoming.getId())).isEmpty()) {
            throw new IllegalArgumentException("Un dels dos està dividit en parts: treu la divisió abans.");
        }
    }

    private static boolean isPlainLeg(Transaction transaction, Set<Long> split) {
        return transaction.getId() != null
                && transaction.getAccount() != null && transaction.getAccount().getId() != null
                && !transaction.isTransfer()
                && transaction.getDebt() == null
                && transaction.getDate() != null
                && transaction.getAmount() != null && transaction.getAmount().signum() > 0
                && ("INCOME".equals(transaction.getType()) || "EXPENSE".equals(transaction.getType()))
                && !split.contains(transaction.getId());
    }

    private static BigDecimal amountKey(Transaction transaction) {
        return transaction.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private static long daysBetween(Transaction first, Transaction second) {
        return Math.abs(ChronoUnit.DAYS.between(first.getDate(), second.getDate()));
    }

    private static boolean isOtherLeg(Transaction stored, Transaction line, Account importAccount) {
        if (stored.getAccount() == null || stored.getAccount().getId().equals(importAccount.getId())) return false;
        if (stored.getAmount() == null || line.getAmount() == null
                || stored.getAmount().compareTo(line.getAmount()) != 0) return false;
        if (stored.getType() == null || stored.getType().equals(line.getType())) return false;
        if (stored.getDate() == null || line.getDate() == null) return false;
        return Math.abs(ChronoUnit.DAYS.between(stored.getDate(), line.getDate())) <= MATCHING_DAYS;
    }
}
