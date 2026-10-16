package com.budgetai.backend.repository;

import com.budgetai.backend.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findAllByOrderByDateDesc();

    @Query("SELECT transfer FROM Transfer transfer WHERE transfer.sourceAccount.id = :accountId " +
           "OR transfer.destinationAccount.id = :accountId ORDER BY transfer.date DESC")
    List<Transfer> findByAccountId(@Param("accountId") Long accountId);
}
