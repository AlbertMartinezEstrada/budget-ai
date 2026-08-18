package com.budgetai.backend.repository;

import com.budgetai.backend.model.ImportRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportRuleRepository extends JpaRepository<ImportRule, Long> {
    List<ImportRule> findByActiveTrue();
}
