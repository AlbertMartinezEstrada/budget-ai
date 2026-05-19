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

    @Query("SELECT rt FROM RecurringTransaction rt WHERE rt.active = true AND rt.nextDate <= :date")
    List<RecurringTransaction> findDueRecurringTransactions(@Param("date") LocalDate date);
}
