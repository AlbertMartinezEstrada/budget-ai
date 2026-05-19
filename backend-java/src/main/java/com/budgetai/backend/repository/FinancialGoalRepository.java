package com.budgetai.backend.repository;

import com.budgetai.backend.model.FinancialGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {
    List<FinancialGoal> findByCompletedFalse();
    List<FinancialGoal> findByCompletedTrue();
    List<FinancialGoal> findAllByOrderByTargetDateAsc();
}
