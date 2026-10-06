package com.budgetai.backend.repository;

import com.budgetai.backend.model.TransactionPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Les consultes que passen pel deute van escrites: amb un nom derivat com
 * findByDebtId, Spring Data trobaria el getter getDebtId() —el que exposa
 * "deute_id" al JSON—, el prendria per un camp i Hibernate no arrencaria.
 */
@Repository
public interface TransactionPartRepository extends JpaRepository<TransactionPart, Long> {

    @Query("SELECT part FROM TransactionPart part WHERE part.transaction.id IN :transactionIds ORDER BY part.id")
    List<TransactionPart> findByTransactions(@Param("transactionIds") Collection<Long> transactionIds);

    @Query("SELECT part FROM TransactionPart part WHERE part.debt.id = :debtId")
    List<TransactionPart> findByDebt(@Param("debtId") Long debtId);

    @Query("SELECT COUNT(part) FROM TransactionPart part WHERE part.category.id = :categoryId")
    long countByCategory(@Param("categoryId") Long categoryId);
}
