package com.budgetai.backend.repository;

import com.budgetai.backend.model.RecurringTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, Long> {

    List<RecurringTransaction> findByActiveTrue();

    // Una versió tancada no genera càrrecs de després del seu tancament: ja
    // els genera la versió que la substitueix.
    @Query("SELECT recurring FROM RecurringTransaction recurring WHERE recurring.active = true AND recurring.nextDate <= :date " +
           "AND (recurring.validUntil IS NULL OR recurring.nextDate <= recurring.validUntil)")
    List<RecurringTransaction> findDueRecurringTransactions(@Param("date") LocalDate date);
}
