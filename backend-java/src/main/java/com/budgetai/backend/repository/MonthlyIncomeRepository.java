package com.budgetai.backend.repository;

import com.budgetai.backend.model.MonthlyIncome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonthlyIncomeRepository extends JpaRepository<MonthlyIncome, Long> {

    Optional<MonthlyIncome> findByPeriod(String period);

    List<MonthlyIncome> findAllByOrderByPeriodDesc();
}
