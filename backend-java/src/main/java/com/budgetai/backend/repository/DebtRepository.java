package com.budgetai.backend.repository;

import com.budgetai.backend.model.Debt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DebtRepository extends JpaRepository<Debt, Long> {

    List<Debt> findAllByOrderByDateDesc();
}
