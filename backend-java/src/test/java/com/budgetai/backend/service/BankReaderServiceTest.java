package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del lector d'extractes bancaris.
 *
 * El cas important és el format dels imports: la versió antiga esborrava tots
 * els punts abans de convertir la coma en separador decimal. Això funciona amb
 * el format europeu però multiplica per 100 qualsevol import en format
 * anglosaxó: "45.30" es convertia en 4530.
 */
class BankReaderServiceTest {

    private final BankReaderService service = new BankReaderService();

    private List<Transaction> parse(String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "extracte.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return service.readBankCsv(file);
    }

    @Test
    @DisplayName("Format anglosaxó: 45.30 són 45,30 i no 4530")
    void parsesAngloSaxonDecimals() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                01/07/2026;COMPRA;-45.30
                """);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.30");
    }

    @Test
    @DisplayName("Format europeu: 1.234,56 són mil dos-cents trenta-quatre amb cinquanta-sis")
    void parsesEuropeanDecimals() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                02/07/2026;COMPRA;-1.234,56
                """);

        assertThat(result.get(0).getAmount()).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("Milers en format anglosaxó: 2,500.75")
    void parsesAngloSaxonThousands() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                03/07/2026;COMPRA;-2,500.75
                """);

        assertThat(result.get(0).getAmount()).isEqualByComparingTo("2500.75");
    }

    @Test
    @DisplayName("Enter sense decimals")
    void parsesInteger() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                04/07/2026;COMPRA;-80
                """);

        assertThat(result.get(0).getAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Es descarta el símbol de la divisa")
    void stripsCurrencySuffix() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                05/07/2026;NOMINA;1.500,00 EUR
                """);

        assertThat(result.get(0).getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("El signe determina el tipus, i l'import es desa en positiu")
    void signDeterminesType() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                01/07/2026;COMPRA;-45,30
                02/07/2026;NOMINA;1500,00
                """);

        assertThat(result.get(0).getType()).isEqualTo("EXPENSE");
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("45.30");
        assertThat(result.get(1).getType()).isEqualTo("INCOME");
        assertThat(result.get(1).getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("La data es llegeix en format DD/MM/YYYY")
    void parsesDate() throws Exception {
        List<Transaction> result = parse("""
                Fecha;Concepto;Importe
                15/02/2026;COMPRA;-10,00
                """);

        assertThat(result.get(0).getDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    @DisplayName("Un import il·legible atura la importació en comptes de desar-se com a zero")
    void rejectsUnparseableAmount() {
        assertThatThrownBy(() -> parse("""
                Fecha;Concepto;Importe
                01/07/2026;COMPRA;no-és-un-número
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Import il·legible");
    }

    @Test
    @DisplayName("El hash és estable per a la mateixa fila i diferent per a files diferents")
    void hashIsStableAndDistinct() throws Exception {
        String csv = """
                Fecha;Concepto;Importe
                01/07/2026;COMPRA A;-10,00
                01/07/2026;COMPRA B;-10,00
                """;

        List<Transaction> first = parse(csv);
        List<Transaction> second = parse(csv);

        // Mateixa entrada, mateix hash: és el que evita duplicats en reimportar.
        assertThat(first.get(0).getVerificationHash())
                .isEqualTo(second.get(0).getVerificationHash());

        // Conceptes diferents han de donar hash diferent.
        assertThat(first.get(0).getVerificationHash())
                .isNotEqualTo(first.get(1).getVerificationHash());
    }

    @Test
    @DisplayName("Un CSV només amb capçalera no dona cap moviment")
    void emptyCsvYieldsNothing() throws Exception {
        assertThat(parse("Fecha;Concepto;Importe\n")).isEmpty();
    }
}
