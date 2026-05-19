package com.budgetai.backend.repository;

import com.budgetai.backend.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findByActiveTrue();

    @Query("SELECT b FROM Budget b WHERE b.active = true AND " +
           "b.periodStart <= :date AND b.periodEnd >= :date")
    List<Budget> findActiveBudgetsForDate(@Param("date") LocalDate date);

    @Query("SELECT b FROM Budget b WHERE b.active = true AND " +
           "b.category.id = :categoryId AND " +
           "b.periodStart <= :date AND b.periodEnd >= :date")
    List<Budget> findActiveBudgetByCategoryAndDate(@Param("categoryId") Long categoryId,
                                                     @Param("date") LocalDate date);
}
