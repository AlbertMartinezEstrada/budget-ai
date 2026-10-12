package com.budgetai.backend.repository;

import com.budgetai.backend.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findAllByOrderByDateDesc();

    // Per comprovar duplicats per hash
    Optional<Transaction> findByVerificationHash(String hash);

    List<Transaction> findByVerificationHashIn(Collection<String> hashes);

    long countByAccountId(Long accountId);

    long countByCategoryId(Long categoryId);

    /**
     * Moviments d'un deute, del més antic al més recent.
     *
     * Consulta escrita i no derivada del nom: amb findByDebtId, Spring Data
     * troba el getter getDebtId() —el que exposa "deute_id" al JSON—, el pren
     * per un camp i Hibernate no arrenca perquè la columna no existeix.
     */
    @Query("SELECT transaction FROM Transaction transaction WHERE transaction.debt.id = :debtId ORDER BY transaction.date ASC")
    List<Transaction> findByDebtOrderedByDate(@Param("debtId") Long debtId);

    /**
     * Moviments d'un compte que podrien ser l'altra pota d'un traspàs: el
     * mateix import, entre dues dates, que no són cap traspàs ni van a cap
     * deute. El sentit i si estan dividits ho mira qui crida.
     */
    @Query("SELECT transaction FROM Transaction transaction WHERE transaction.account.id = :accountId"
            + " AND transaction.amount = :amount AND transaction.date BETWEEN :fromDate AND :toDate"
            + " AND transaction.counterpartAccount IS NULL AND transaction.debt IS NULL")
    List<Transaction> findPossibleOtherLegs(@Param("accountId") Long accountId,
                                            @Param("amount") java.math.BigDecimal amount,
                                            @Param("fromDate") java.time.LocalDate fromDate,
                                            @Param("toDate") java.time.LocalDate toDate);

    /** Els traspassos que tenen aquest compte a l'altre costat. Escrita per la mateixa raó. */
    @Query("SELECT transaction FROM Transaction transaction WHERE transaction.counterpartAccount.id = :accountId")
    List<Transaction> findTransfersWithCounterpart(@Param("accountId") Long accountId);
}
