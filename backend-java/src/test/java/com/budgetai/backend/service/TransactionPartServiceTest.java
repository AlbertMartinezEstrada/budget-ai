package com.budgetai.backend.service;

import com.budgetai.backend.model.Category;
import com.budgetai.backend.model.Debt;
import com.budgetai.backend.model.Transaction;
import com.budgetai.backend.model.TransactionPart;
import com.budgetai.backend.repository.CategoryRepository;
import com.budgetai.backend.repository.TransactionPartRepository;
import com.budgetai.backend.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionPartServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionPartRepository partRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryHierarchyService hierarchyService;
    @Mock private DebtService debtService;
    @InjectMocks private TransactionPartService service;

    private Transaction transfer;
    private Debt selfLoan;

    @BeforeEach
    void setUp() {
        selfLoan = new Debt();
        selfLoan.setId(7L);

        transfer = new Transaction();
        transfer.setId(10L);
        transfer.setType("EXPENSE");
        transfer.setDate(LocalDate.of(2026, 10, 1));
        transfer.setAmount(new BigDecimal("500.00"));
        transfer.setDebt(selfLoan);

        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transfer));
        lenient().when(categoryRepository.findById(any())).thenAnswer(invocation -> {
            Category category = new Category("Categoria " + invocation.getArgument(0));
            category.setId(invocation.getArgument(0));
            return Optional.of(category);
        });
    }

    private static TransactionPart request(String amount, Long categoryId) {
        TransactionPart part = new TransactionPart();
        part.setAmount(new BigDecimal(amount));
        if (categoryId != null) {
            Category reference = new Category();
            reference.setId(categoryId);
            part.setCategory(reference);
        }
        return part;
    }

    @Test
    @DisplayName("Unes parts que no sumen el total es rebutgen sense tocar res, i el missatge diu quant falta")
    void partsMustAddUpToTheTotal() {
        assertThatThrownBy(() -> service.replace(10L, List.of(request("300.00", 1L), request("180.00", 2L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("en falten 20.00 €");

        verify(partRepository, never()).deleteAll(anyList());
        verify(partRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Una sola part no és dividir: per canviar la categoria hi ha l'edició")
    void onePartIsNotASplit() {
        assertThatThrownBy(() -> service.replace(10L, List.of(request("500.00", 1L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("almenys dues parts");
    }

    @Test
    @DisplayName("Una part no pot anar a un grup: es tornaria a sumar pels fills")
    void partCategoryMustBeALeaf() {
        // La primera part és d'una fulla; la segona, del grup.
        when(hierarchyService.isGroup(any())).thenAnswer(invocation -> Long.valueOf(2L).equals(invocation.getArgument(0)));

        assertThatThrownBy(() -> service.replace(10L, List.of(request("300.00", 1L), request("200.00", 2L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("és un grup");
    }

    @Test
    @DisplayName("Un tercer decimal es rebutja: la base de dades l'arrodoniria i les parts deixarien de sumar")
    void atMostTwoDecimals() {
        assertThatThrownBy(() -> service.replace(10L, List.of(request("300.005", 1L), request("199.995", 2L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dos decimals");
    }

    @Test
    @DisplayName("Cada part ha de dir la seva categoria")
    void everyPartNeedsACategory() {
        assertThatThrownBy(() -> service.replace(10L, List.of(request("300.00", 1L), request("200.00", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoria");
    }

    @Test
    @DisplayName("Dividir substitueix les parts d'abans i passa el deute del moviment a les parts")
    void validSplitReplacesPartsAndClearsTransactionDebt() {
        when(debtService.resolveForLink(any())).thenAnswer(invocation -> {
            Debt reference = invocation.getArgument(0);
            return reference != null && reference.getId() != null && reference.getId() > 0 ? selfLoan : null;
        });
        TransactionPart repayment = request("100.00", 2L);
        repayment.setDebtId(7L);

        service.replace(10L, List.of(request("300.00", 1L), repayment, request("60.00", 3L), request("40.00", 1L)));

        verify(partRepository).deleteAll(anyList());
        verify(partRepository).saveAll(anyList());
        // El vincle ara és de la part: deixar-lo al moviment el comptaria dos cops.
        assertThat(transfer.getDebt()).isNull();
    }

    @Test
    @DisplayName("Una llista buida treu la divisió i no toca el moviment")
    void emptyListRemovesTheSplit() {
        service.replace(10L, List.of());

        verify(partRepository).deleteAll(anyList());
        verify(partRepository, never()).saveAll(anyList());
        assertThat(transfer.getDebt()).isSameAs(selfLoan);
    }
}
