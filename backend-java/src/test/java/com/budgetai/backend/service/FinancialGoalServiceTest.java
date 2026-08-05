package com.budgetai.backend.service;

import com.budgetai.backend.model.FinancialGoal;
import com.budgetai.backend.repository.FinancialGoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialGoalServiceTest {

    @Mock private FinancialGoalRepository repository;
    @InjectMocks private FinancialGoalService service;

    private FinancialGoal goal;

    @BeforeEach
    void setUp() {
        goal = new FinancialGoal();
        goal.setId(1L);
        goal.setName("Viatge");
        goal.setTargetAmount(new BigDecimal("1500.00"));
        goal.setCurrentAmount(new BigDecimal("250.00"));
        goal.setTargetDate(LocalDate.of(2026, 12, 31));
        goal.setCompleted(false);
    }

    @Test
    @DisplayName("El percentatge de progrés es calcula amb dos decimals")
    void progressPercentage() {
        assertThat(goal.getProgressPercentage()).isEqualByComparingTo("16.67");
    }

    @Test
    @DisplayName("Un objectiu de zero no provoca una divisió per zero")
    void zeroTargetIsSafe() {
        goal.setTargetAmount(BigDecimal.ZERO);
        assertThat(goal.getProgressPercentage()).isEqualByComparingTo("0");

        goal.setTargetAmount(null);
        assertThat(goal.getProgressPercentage()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Afegir una quantitat suma sobre el que ja hi havia")
    void addAmountAccumulates() {
        when(repository.findById(1L)).thenReturn(Optional.of(goal));
        when(repository.save(any(FinancialGoal.class))).thenAnswer(i -> i.getArgument(0));

        FinancialGoal result = service.addToGoal(1L, new BigDecimal("250.00"));

        assertThat(result.getCurrentAmount()).isEqualByComparingTo("500.00");
        assertThat(result.getCompleted()).isFalse();
    }

    @Test
    @DisplayName("En arribar a l'objectiu es marca com a completat")
    void reachingTargetCompletesGoal() {
        when(repository.findById(1L)).thenReturn(Optional.of(goal));
        when(repository.save(any(FinancialGoal.class))).thenAnswer(i -> i.getArgument(0));

        FinancialGoal result = service.addToGoal(1L, new BigDecimal("1250.00"));

        assertThat(result.getCurrentAmount()).isEqualByComparingTo("1500.00");
        assertThat(result.getCompleted()).isTrue();
    }

    @Test
    @DisplayName("Actualitzar sense enviar quantitat_actual no esborra els diners estalviats")
    void partialUpdateKeepsSavedAmount() {
        when(repository.findById(1L)).thenReturn(Optional.of(goal));
        when(repository.save(any(FinancialGoal.class))).thenAnswer(i -> i.getArgument(0));

        // El formulari només envia nom, objectiu i data.
        FinancialGoal partial = new FinancialGoal();
        partial.setName("Viatge al Japó");
        partial.setTargetAmount(new BigDecimal("2000.00"));

        FinancialGoal result = service.updateGoal(1L, partial);

        assertThat(result.getName()).isEqualTo("Viatge al Japó");
        assertThat(result.getTargetAmount()).isEqualByComparingTo("2000.00");
        // Abans això quedava a null i es perdien els 250 € ja estalviats.
        assertThat(result.getCurrentAmount()).isEqualByComparingTo("250.00");
        assertThat(result.getTargetDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }
}
