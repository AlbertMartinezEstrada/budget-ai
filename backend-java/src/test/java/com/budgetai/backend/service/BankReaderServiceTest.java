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

    private final BankReaderService service = new BankReaderService(new TransactionHasher());

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

    // ============ FORMAT DE REVOLUT ============

    private static final String REVOLUT_HEADER =
            "Tipo,Produto,Data de início,Data de Conclusão,Descrição,Montante,Comissão,Moeda,Estado,Saldo\n";

    private List<Transaction> readRevolut(String... rows) throws Exception {
        String content = REVOLUT_HEADER + String.join("\n", rows) + "\n";
        return service.readBankCsv(new MockMultipartFile(
                "file", "revolut.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("El format de Revolut es reconeix per la capçalera, sense preguntar")
    void revolutIsDetectedByItsHeader() throws Exception {
        List<Transaction> read = readRevolut(
                "Pagamento com cartão,Atual,2026-08-01 10:59:05,2026-08-04 09:13:10,WOO,-7.00,0.00,EUR,CONCLUÍDA,0.00");

        assertThat(read).hasSize(1);
        assertThat(read.get(0).getOriginalConcept()).isEqualTo("WOO");
        assertThat(read.get(0).getType()).isEqualTo("EXPENSE");
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("Mana la data de conclusió, que és quan es mou el saldo")
    void theCompletionDateWins() throws Exception {
        List<Transaction> read = readRevolut(
                "Pagamento com cartão,Atual,2026-08-01 10:59:05,2026-08-04 09:13:10,WOO,-7.00,0.00,EUR,CONCLUÍDA,0.00");

        // Un pagament pot començar un dia i completar-se un altre.
        assertThat(read.get(0).getDate()).isEqualTo(LocalDate.of(2026, 8, 4));
    }

    @Test
    @DisplayName("La comissió compta encara que l'import sigui zero")
    void theFeeIsPartOfTheMovement() throws Exception {
        List<Transaction> read = readRevolut(
                "Cobrança,Atual,2026-08-01 01:49:44,2026-08-01 01:49:44,"
                        + "Comissão de manutenção de conta pacote Premium,0.00,4.99,EUR,CONCLUÍDA,-1.89");

        // Llegint només "Montante", aquesta despesa entrava com a zero euros.
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("4.99");
        assertThat(read.get(0).getType()).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("Els moviments que no estan conclosos no s'importen")
    void pendingMovementsAreSkipped() throws Exception {
        List<Transaction> read = readRevolut(
                "Pagamento com cartão,Atual,2026-08-01 10:00:00,2026-08-01 10:00:00,Pendent,-9.99,0.00,EUR,PENDENTE,0.00",
                "Pagamento com cartão,Atual,2026-08-02 10:00:00,2026-08-02 10:00:00,Fet,-5.00,0.00,EUR,CONCLUÍDA,0.00");

        // Importar-los mouria el saldo de diners que no s'han mogut.
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getOriginalConcept()).isEqualTo("Fet");
    }

    @Test
    @DisplayName("Una entrada de diners és un ingrés i en desa el saldo")
    void topUpsAreIncome() throws Exception {
        List<Transaction> read = readRevolut(
                "Carregamento,Atual,2026-08-05 09:24:18,2026-08-05 09:24:20,"
                        + "Carregamento com Apple Pay através de *9469,10.00,0.00,EUR,CONCLUÍDA,10.00");

        assertThat(read.get(0).getType()).isEqualTo("INCOME");
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("10.00");
        assertThat(read.get(0).getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Una fila que no mou res no s'importa")
    void zeroMovementsAreSkipped() throws Exception {
        List<Transaction> read = readRevolut(
                "Cobrança,Atual,2026-08-01 01:00:00,2026-08-01 01:00:00,Res,0.00,0.00,EUR,CONCLUÍDA,0.00");

        assertThat(read).isEmpty();
    }

    @Test
    @DisplayName("El format de sempre segueix funcionant")
    void theClassicFormatStillWorks() throws Exception {
        String content = "Fecha;Concepto;Importe\n15/02/2026;MERCADONA;-45,30 EUR\n";
        List<Transaction> read = service.readBankCsv(new MockMultipartFile(
                "file", "classic.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)));

        assertThat(read).hasSize(1);
        assertThat(read.get(0).getDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("45.30");
    }
}
