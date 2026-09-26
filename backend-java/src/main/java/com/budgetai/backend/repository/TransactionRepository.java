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

    List<Transaction> findByDebtIsNotNull();
}
