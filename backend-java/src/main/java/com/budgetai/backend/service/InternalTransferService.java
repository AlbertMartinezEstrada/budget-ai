package com.budgetai.backend.service;

import com.budgetai.backend.model.Account;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.repository.AccountRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
    private final AccountService accountService;

    public InternalTransferService(AccountRepository accountRepository,
                                   TransactionRepository transactionRepository,
                                   AccountService accountService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
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

    private static boolean isOtherLeg(Transaction stored, Transaction line, Account importAccount) {
        if (stored.getAccount() == null || stored.getAccount().getId().equals(importAccount.getId())) return false;
        if (stored.getAmount() == null || line.getAmount() == null
                || stored.getAmount().compareTo(line.getAmount()) != 0) return false;
        if (stored.getType() == null || stored.getType().equals(line.getType())) return false;
        if (stored.getDate() == null || line.getDate() == null) return false;
        return Math.abs(ChronoUnit.DAYS.between(stored.getDate(), line.getDate())) <= MATCHING_DAYS;
    }
}
