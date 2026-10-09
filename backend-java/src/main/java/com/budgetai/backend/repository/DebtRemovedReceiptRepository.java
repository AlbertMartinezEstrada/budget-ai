package com.budgetai.backend.repository;

import com.budgetai.backend.model.DebtRemovedReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DebtRemovedReceiptRepository extends JpaRepository<DebtRemovedReceipt, Long> {

    @Query("SELECT removal FROM DebtRemovedReceipt removal WHERE removal.debt.id = :debtId ORDER BY removal.date")
    List<DebtRemovedReceipt> findByDebt(@Param("debtId") Long debtId);
}
