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

    // ============ TRADE REPUBLIC ============

    private static final String TRADE_REPUBLIC_HEADER = "\"datetime\",\"date\",\"account_type\",\"category\","
            + "\"type\",\"asset_class\",\"name\",\"symbol\",\"shares\",\"price\",\"amount\",\"fee\",\"tax\","
            + "\"currency\",\"original_amount\",\"original_currency\",\"fx_rate\",\"description\","
            + "\"transaction_id\",\"counterparty_name\",\"counterparty_iban\",\"payment_reference\",\"mcc_code\"";

    /** Una fila de l'extracte de Trade Republic, amb tots els camps entre cometes com l'original. */
    private static String tradeRepublicRow(String date, String category, String type, String name,
                                           String amount, String fee, String tax, String description) {
        String[] fields = {date + "T10:00:00.000000Z", date, "DEFAULT", category, type, "", name, "", "", "",
                amount, fee, tax, "EUR", "", "", "", description, "id-" + description.hashCode(), name, "", "", ""};
        StringBuilder row = new StringBuilder();
        for (String field : fields) {
            if (!row.isEmpty()) row.append(',');
            row.append('"').append(field).append('"');
        }
        return row.toString();
    }

    private List<Transaction> readTradeRepublic(String... rows) throws Exception {
        String content = TRADE_REPUBLIC_HEADER + "\n" + String.join("\n", rows) + "\n";
        return service.readBankCsv(new MockMultipartFile(
                "file", "extractoTradeRepublic.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Trade Republic: es reconeix per la capçalera i es llegeix amb comes i cometes")
    void tradeRepublicIsRead() throws Exception {
        // L'extracte real: abans es llegia amb punt i coma i petava a la
        // primera coma de la capçalera ("line: 1, position: 11").
        List<Transaction> read = readTradeRepublic(
                tradeRepublicRow("2026-10-01", "CASH", "INTEREST_PAYMENT", "", "6.520000", "", "",
                        "Interest payment for payout collection 01a0"),
                tradeRepublicRow("2026-10-01", "CASH", "TRANSFER_INSTANT_OUTBOUND", "IMOBILIÁRIA EXEMPLE, LDA",
                        "-553.330000", "", "", "Outgoing transfer for IMOBILIÁRIA EXEMPLE, LDA (LT000000000000000000)"),
                tradeRepublicRow("2026-10-06", "CASH", "TRANSFER_INSTANT_INBOUND", "TITULAR DE PROVA",
                        "1276.000000", "", "", "Incoming transfer from TITULAR DE PROVA (ES0000000000000000000000)"));

        assertThat(read).hasSize(3);
        assertThat(read.get(0).getType()).isEqualTo("INCOME");
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("6.52");
        assertThat(read.get(0).getDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(read.get(1).getType()).isEqualTo("EXPENSE");
        assertThat(read.get(1).getAmount()).isEqualByComparingTo("553.33");
        // Una coma dins d'un camp entre cometes és part del nom, no un separador.
        assertThat(read.get(1).getOriginalConcept()).isEqualTo(
                "Outgoing transfer for IMOBILIÁRIA EXEMPLE, LDA (LT000000000000000000)");
        assertThat(read.get(2).getAmount()).isEqualByComparingTo("1276.00");
        // Sense saldo a l'extracte: el saldo el porta l'aplicació.
        assertThat(read.get(2).getBalance()).isNull();
        assertThat(read.get(2).getVerificationHash()).isNotBlank();
    }

    @Test
    @DisplayName("Trade Republic: l'import és en format de màquina, \"500.000\" són 500 € i no mig milió")
    void tradeRepublicAmountsAreMachineFormat() throws Exception {
        List<Transaction> read = readTradeRepublic(
                tradeRepublicRow("2026-10-02", "CASH", "CARD_TRANSACTION", "Botiga", "-500.000", "", "", "Botiga"));

        assertThat(read.get(0).getAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Trade Republic: les compres i vendes de valors no s'importen; els dividends sí, sense l'impost")
    void tradeRepublicSkipsTradesButKeepsDividends() throws Exception {
        List<Transaction> read = readTradeRepublic(
                tradeRepublicRow("2026-10-03", "TRADING", "BUY", "MSCI World", "-300.000000", "-1.000000", "",
                        "Buy MSCI World"),
                tradeRepublicRow("2026-10-04", "TRADING", "SELL", "MSCI World", "120.000000", "-1.000000", "",
                        "Sell MSCI World"),
                tradeRepublicRow("2026-10-05", "TRADING", "DIVIDEND", "MSCI World", "10.000000", "", "-1.500000",
                        "Dividend MSCI World"));

        // Comprar un ETF mou diners d'efectiu a inversió dins del mateix
        // compte: al pressupost no és cap despesa.
        assertThat(read).hasSize(1);
        assertThat(read.get(0).getOriginalConcept()).isEqualTo("Dividend MSCI World");
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("8.50");
        assertThat(read.get(0).getType()).isEqualTo("INCOME");
    }

    @Test
    @DisplayName("Trade Republic: la comissió d'un pagament es resta, sigui quin sigui el seu signe")
    void tradeRepublicFeesAreCharges() throws Exception {
        List<Transaction> read = readTradeRepublic(
                tradeRepublicRow("2026-10-06", "CASH", "CARD_TRANSACTION", "Caixer", "-50.000000", "1.000000", "",
                        "Caixer"));

        assertThat(read.get(0).getAmount()).isEqualByComparingTo("51.00");
        assertThat(read.get(0).getType()).isEqualTo("EXPENSE");
    }

    @Test
    @DisplayName("Trade Republic: un import il·legible diu la línia i la columna")
    void tradeRepublicBadAmountSaysWhere() {
        assertThatThrownBy(() -> readTradeRepublic(
                tradeRepublicRow("2026-10-01", "CASH", "CARD_TRANSACTION", "Bé", "-1.000000", "", "", "Bé"),
                tradeRepublicRow("2026-10-02", "CASH", "CARD_TRANSACTION", "Malament", "abc", "", "", "Malament")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Línia 3")
                .hasMessageContaining("Trade Republic")
                .hasMessageContaining("«amount»")
                .hasMessageContaining("\"abc\"");
    }

    // ============ ERRORS DE LECTURA ============

    @Test
    @DisplayName("Un CSV que commons-csv no pot llegir dona un missatge en català amb la línia, i no un error intern")
    void unreadableCsvIsExplained() {
        // Les mateixes columnes que el format clàssic, entre cometes i amb
        // un text enganxat després de tancar-les.
        assertThatThrownBy(() -> parse("""
                Fecha;Concepto;Importe
                01/07/2026;"COMPRA"x;-45.30
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a la línia 2")
                .hasMessageContaining("punt i coma")
                .hasMessageContaining("Trade Republic")
                .hasCauseInstanceOf(org.apache.commons.csv.CSVException.class);
    }

    @Test
    @DisplayName("Unes cometes que no es tanquen es diuen per nom")
    void unclosedQuotesAreExplained() {
        assertThatThrownBy(() -> parse("""
                Fecha;Concepto;Importe
                01/07/2026;"COMPRA;-45.30
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es tanquen");
    }

    @Test
    @DisplayName("Un extracte que no és de cap format conegut diu quins se saben llegir i quines columnes té")
    void unknownFormatListsTheKnownOnes() {
        assertThatThrownBy(() -> parse("""
                Dia;Text;Quantitat
                01/07/2026;COMPRA;-45.30
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No reconec aquest extracte")
                .hasMessageContaining("«Fecha»")
                .hasMessageContaining("Revolut")
                .hasMessageContaining("Dia, Text, Quantitat");
    }

    @Test
    @DisplayName("El format clàssic separat per comes també es llegeix: el separador surt de la capçalera")
    void classicWithCommasIsRead() throws Exception {
        List<Transaction> read = parse("""
                "Fecha","Concepto","Importe"
                "01/07/2026","COMPRA, BOTIGA","-45.30"
                """);

        assertThat(read).hasSize(1);
        assertThat(read.get(0).getOriginalConcept()).isEqualTo("COMPRA, BOTIGA");
        assertThat(read.get(0).getAmount()).isEqualByComparingTo("45.30");
    }

    @Test
    @DisplayName("Una data il·legible diu la línia i el format que s'esperava")
    void badDateSaysWhereAndWhat() {
        assertThatThrownBy(() -> parse("""
                Fecha;Concepto;Importe
                2026-07-01;COMPRA;-45.30
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Línia 2")
                .hasMessageContaining("DD/MM/AAAA");
    }

    @Test
    @DisplayName("Un fitxer buit es diu com a tal")
    void emptyFileIsExplained() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buit");
    }

    @Test
    @DisplayName("El separador es dedueix de la capçalera, sense comptar el que hi ha dins de cometes")
    void delimiterIsDetected() {
        assertThat(BankReaderService.delimiterOf("Fecha;Concepto;Importe")).isEqualTo(';');
        assertThat(BankReaderService.delimiterOf("\"Fecha\",\"Concepto\",\"Importe\"")).isEqualTo(',');
        assertThat(BankReaderService.delimiterOf("\"Data; hora\",\"Concepte\",\"Import\"")).isEqualTo(',');
        assertThat(BankReaderService.delimiterOf("Fecha\tConcepto\tImporte")).isEqualTo('\t');
    }
}
