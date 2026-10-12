package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converteix un extracte bancari en moviments.
 *
 * **Tot el que surti d'aquí cap a l'usuari ha de ser una
 * IllegalArgumentException amb un text que s'entengui**: quin problema té el
 * fitxer, a quina línia i què s'esperava. Qualsevol altra excepció acaba com
 * un "Error intern" a la pantalla i una traça de commons-csv al log, que és
 * el que passava amb l'extracte de Trade Republic: el lector no el reconeixia,
 * el llegia amb punt i coma, i l'única pista era "Invalid character between
 * encapsulated token and delimiter at line: 1, position: 11".
 */
@Service
public class BankReaderService {

    /** Els formats que se saben llegir, per dir-ho quan el fitxer no n'és cap. */
    static final String KNOWN_FORMATS = "el clàssic (Fecha;Concepto;Importe), Revolut i Trade Republic";

    /**
     * La línia de l'error de commons-csv. La "position" que hi surt no es diu:
     * compta caràcters des del principi del fitxer, no dins de la línia, i a
     * l'usuari el despistaria. Es queda a la causa, que va al log.
     */
    private static final Pattern CSV_LINE = Pattern.compile("line: (\\d+)");

    /** Com es llegeix cada fila: null vol dir que no és cap moviment i se salta. */
    @FunctionalInterface
    private interface RowReader {
        Transaction read(CSVRecord csvRecord);
    }

    private final TransactionHasher transactionHasher;

    public BankReaderService(TransactionHasher transactionHasher) {
        this.transactionHasher = transactionHasher;
    }

    /**
     * Llegeix un extracte, sigui del format que sigui.
     *
     * El format es dedueix de la capçalera i no es demana a qui puja el
     * fitxer: les columnes ja diuen de quin banc ve, i un desplegable més
     * només afegiria un pas i una manera d'equivocar-se.
     */
    public List<Transaction> readBankCsv(MultipartFile file) throws IOException {
        String content = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Alguns bancs exporten amb BOM i la primera columna deixa de tenir el
        // nom que diu la capçalera.
        if (content.startsWith("\uFEFF")) content = content.substring(1);

        String header = content.lines().findFirst().orElse("");
        if (header.isBlank()) {
            throw new IllegalArgumentException("El fitxer és buit, o la primera línia no és la capçalera de les columnes.");
        }

        if (isTradeRepublic(header)) {
            return readRows(content, ',', "Trade Republic", this::readTradeRepublicRow,
                    new String[] {"date", "datetime"}, new String[] {"amount"});
        }
        if (isRevolut(header)) {
            return readRows(content, ',', "Revolut", this::readRevolutRow,
                    new String[] {"Data de Conclusão", "Data de Conclusao", "Completed Date"},
                    new String[] {"Montante", "Amount"});
        }
        return readRows(content, delimiterOf(header), "clàssic", this::readClassicRow,
                new String[] {"Fecha"}, new String[] {"Concepto"}, new String[] {"Importe"});
    }

    /**
     * Trade Republic: una columna per a cada cosa, en anglès i en minúscules,
     * amb un identificador per moviment. Cap altre banc porta "transaction_id"
     * i "account_type" alhora.
     */
    private boolean isTradeRepublic(String header) {
        String lower = header.toLowerCase(Locale.ROOT);
        return lower.contains("transaction_id") && lower.contains("account_type");
    }

    /**
     * Revolut porta l'import a "Montante" i la comissió en una columna a part.
     * Cap dels dos noms surt a l'altre format, així que n'hi ha prou de mirar-ho.
     */
    private boolean isRevolut(String header) {
        return header.contains("Montante") || header.contains("Data de Conclus") || header.contains("Completed Date");
    }

    /**
     * El separador del format clàssic, mirant la capçalera: el que hi surt més
     * vegades fora de cometes. Sol ser el punt i coma, però hi ha bancs que
     * exporten les mateixes columnes amb comes o tabuladors, i llegir-los amb
     * el que no toca trencava a la primera línia.
     */
    static char delimiterOf(String header) {
        int semicolons = 0;
        int commas = 0;
        int tabs = 0;
        boolean quoted = false;
        for (char character : header.toCharArray()) {
            if (character == '"') quoted = !quoted;
            if (quoted) continue;
            if (character == ';') semicolons++;
            else if (character == ',') commas++;
            else if (character == '\t') tabs++;
        }
        if (tabs > semicolons && tabs > commas) return '\t';
        return commas > semicolons ? ',' : ';';
    }

    private CSVParser parse(String content, char delimiter) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .get();
        return CSVParser.builder()
                .setReader(new BufferedReader(new StringReader(content)))
                .setFormat(format)
                .get();
    }

    /**
     * Llegeix totes les files d'un format i en tradueix els errors.
     *
     * Un error de commons-csv (unes cometes mal tancades, un separador que no
     * és el que s'esperava) es converteix en un missatge en català amb la
     * línia, i un error d'una fila concreta (una data o un import il·legibles)
     * diu de quina línia és. La causa original es conserva: és la que surt al
     * log.
     *
     * @param formatName per al missatge: "Trade Republic", "clàssic"...
     * @param required   per a cada columna imprescindible, els noms que pot
     *                   tenir; n'ha d'haver-hi almenys un de cada grup
     */
    private List<Transaction> readRows(String content, char delimiter, String formatName, RowReader rowReader,
                                       String[]... required) throws IOException {
        try (CSVParser csvParser = parse(content, delimiter)) {
            requireColumns(csvParser, formatName, required);

            List<Transaction> transactions = new ArrayList<>();
            for (CSVRecord csvRecord : csvParser) {
                try {
                    Transaction transaction = rowReader.read(csvRecord);
                    if (transaction != null) transactions.add(transaction);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Línia " + csvParser.getCurrentLineNumber() + " de l'extracte ("
                            + formatName + "): " + exception.getMessage(), exception);
                }
            }
            return transactions;
        } catch (CSVException exception) {
            throw unreadable(exception, delimiter);
        } catch (UncheckedIOException exception) {
            // L'iterador de commons-csv embolcalla així els errors de les files.
            if (exception.getCause() instanceof CSVException csvException) throw unreadable(csvException, delimiter);
            throw exception;
        }
    }

    private static void requireColumns(CSVParser csvParser, String formatName, String[]... required) {
        List<String> present = csvParser.getHeaderNames();
        for (String[] alternatives : required) {
            boolean found = false;
            for (String name : alternatives) {
                found |= present.stream().anyMatch(column -> column.equalsIgnoreCase(name));
            }
            if (!found) {
                throw new IllegalArgumentException("No reconec aquest extracte: per llegir-lo com a " + formatName
                        + " li falta la columna «" + String.join("» o «", alternatives) + "». "
                        + "Sé llegir " + KNOWN_FORMATS + ". Les columnes del fitxer són: "
                        + abbreviate(String.join(", ", present), 200) + ".");
            }
        }
    }

    /** Un error de lectura del CSV, explicat. */
    private static IllegalArgumentException unreadable(CSVException exception, char delimiter) {
        String detail = exception.getMessage() != null ? exception.getMessage() : "";
        Matcher line = CSV_LINE.matcher(detail);
        String where = line.find() ? "a la línia " + line.group(1) : "";

        String what;
        if (detail.contains("Invalid character between encapsulated token and delimiter")) {
            what = "després d'un camp entre cometes hi ha un caràcter que no és el separador «"
                    + describe(delimiter) + "». Sol voler dir que el fitxer separa les columnes amb un altre caràcter";
        } else if (detail.contains("EOF reached before encapsulated token finished")) {
            what = "unes cometes s'obren i no es tanquen fins al final del fitxer";
        } else {
            what = "el fitxer no té un format CSV vàlid";
        }
        return new IllegalArgumentException("No es pot llegir l'extracte" + (where.isEmpty() ? "" : " " + where)
                + ": " + what + ". Sé llegir " + KNOWN_FORMATS + ".", exception);
    }

    private static String describe(char delimiter) {
        return switch (delimiter) {
            case ';' -> "punt i coma";
            case ',' -> "coma";
            case '\t' -> "tabulador";
            default -> String.valueOf(delimiter);
        };
    }

    private static String abbreviate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length) + "…";
    }

    /** El format de sempre: Fecha;Concepto;Importe. */
    private Transaction readClassicRow(CSVRecord csvRecord) {
        String rawBalance = csvRecord.isMapped("Saldo") ? csvRecord.get("Saldo") : null;

        BigDecimal amount = cleanNumber(csvRecord.get("Importe"));
        BigDecimal balance = (rawBalance != null) ? cleanNumber(rawBalance) : null;

        return build(parseDate(csvRecord.get("Fecha"), DateTimeFormatter.ofPattern("dd/MM/yyyy"), "DD/MM/AAAA"),
                csvRecord.get("Concepto"), amount, balance);
    }

    /**
     * Extracte de Trade Republic.
     *
     * Les compres i vendes de valors (categoria TRADING) no s'importen: els
     * diners passen d'efectiu a una inversió dins del mateix compte, i al
     * pressupost no són ni una despesa ni un ingrés. Sí que s'importen els
     * dividends i els interessos, que són diners que entren, i tot el que és
     * efectiu: transferències, pagaments amb targeta.
     *
     * L'import ve en format de màquina (punt decimal, sis decimals, sense
     * milers): "500.000000" són 500 €, i el lector heurístic dels altres bancs
     * el prendria per mig milió. Comissió i impostos van en columnes a part,
     * com la comissió de Revolut, i es resten.
     */
    private Transaction readTradeRepublicRow(CSVRecord csvRecord) {
        if (isTrade(column(csvRecord, "category"), column(csvRecord, "type"))) return null;

        BigDecimal amount = plainNumber(column(csvRecord, "amount"), "amount");
        BigDecimal charges = plainNumber(column(csvRecord, "fee"), "fee").abs()
                .add(plainNumber(column(csvRecord, "tax"), "tax").abs());
        BigDecimal net = amount.subtract(charges);
        // Una fila sense moviment no és res que calgui desar.
        if (net.signum() == 0) return null;

        String rawDate = column(csvRecord, "date");
        if (rawDate == null || rawDate.isBlank()) rawDate = column(csvRecord, "datetime");
        if (rawDate == null || rawDate.isBlank()) throw new IllegalArgumentException("falta la data.");
        // "2026-10-01" o "2026-10-01T03:24:22.922096Z": el dia és el principi.
        LocalDate date = parseDate(rawDate.length() > 10 ? rawDate.substring(0, 10) : rawDate,
                DateTimeFormatter.ISO_LOCAL_DATE, "AAAA-MM-DD");

        String concept = firstNonBlank(column(csvRecord, "description"),
                column(csvRecord, "counterparty_name"), column(csvRecord, "name"), column(csvRecord, "type"));
        return build(date, concept, net, null);
    }

    /**
     * Una compra o venda de valors. Els dividends i els interessos també poden
     * anar a TRADING, però aquests sí que són diners que entren.
     */
    private boolean isTrade(String category, String type) {
        if (category == null || !category.trim().equalsIgnoreCase("TRADING")) return false;
        String normalised = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return !(normalised.contains("DIVIDEND") || normalised.contains("INTEREST")
                || normalised.contains("DISTRIBUTION") || normalised.contains("COUPON"));
    }

    /** Un import en format de màquina ("-553.330000"); buit és zero. */
    private BigDecimal plainNumber(String raw, String columnName) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Import il·legible a la columna «" + columnName + "»: \"" + raw + "\"",
                    exception);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private LocalDate parseDate(String value, DateTimeFormatter formatter, String expected) {
        try {
            return LocalDate.parse(value.trim(), formatter);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Data il·legible al CSV: \"" + value + "\" (s'esperava " + expected + ")",
                    exception);
        }
    }

    /**
     * Extracte de Revolut.
     *
     * Tres coses que no són com semblen:
     *
     * L'IMPORT NO ÉS NOMÉS "Montante". La comissió va a part, i una fila de
     * manteniment porta Montante=0 i Comissão=4.99: llegint només el primer,
     * aquella despesa entrava com a zero euros. El moviment real és la resta
     * dels dos.
     *
     * HI HA DUES DATES. Un pagament pot començar un dia i completar-se un
     * altre —el saldo es mou el segon—, així que mana "Data de Conclusão".
     *
     * NO TOT ESTÀ FET. Revolut també exporta moviments pendents i revertits.
     * Importar-los mouria saldos de diners que no s'han mogut.
     */
    private Transaction readRevolutRow(CSVRecord csvRecord) {
        if (!isCompleted(column(csvRecord, "Estado", "Estat", "State"))) return null;

        BigDecimal amount = cleanNumber(column(csvRecord, "Montante", "Amount"));
        BigDecimal fee = cleanNumber(column(csvRecord, "Comissão", "Comissao", "Fee"));
        BigDecimal net = amount.subtract(fee);

        // Una fila sense moviment no és res que calgui desar.
        if (net.signum() == 0) return null;

        LocalDate date = parseDateTime(column(csvRecord, "Data de Conclusão",
                "Data de Conclusao", "Completed Date"));
        if (date == null) return null;

        String concept = column(csvRecord, "Descrição", "Descricao", "Description");
        if (concept == null || concept.isBlank()) {
            concept = column(csvRecord, "Tipo", "Type");
        }

        return build(date, concept, net, cleanNumber(column(csvRecord, "Saldo", "Balance")));
    }

    /** Els noms de columna canvien amb l'idioma de l'exportació. */
    private String column(CSVRecord record, String... names) {
        for (String name : names) {
            if (record.isMapped(name)) return record.get(name);
        }
        return null;
    }

    private boolean isCompleted(String state) {
        // Sense columna d'estat, s'assumeix que el que hi ha està fet.
        if (state == null || state.isBlank()) return true;

        String normalised = state.trim().toUpperCase();
        return normalised.startsWith("CONCLU") || normalised.equals("COMPLETED");
    }

    /** "2026-08-04 09:13:10" -> 2026-08-04. */
    private LocalDate parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;

        String trimmed = value.trim();
        int space = trimmed.indexOf(' ');
        try {
            return LocalDate.parse(space > 0 ? trimmed.substring(0, space) : trimmed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Data il·legible al CSV: \"" + value + "\"", exception);
        }
    }

    /**
     * El signe del moviment viu al tipus i l'import es desa en positiu, igual
     * que a la resta de l'aplicació.
     */
    private Transaction build(LocalDate date, String concept, BigDecimal signedAmount, BigDecimal balance) {
        Transaction transaction = new Transaction();
        transaction.setOriginalConcept(concept);
        transaction.setDate(date);
        transaction.setAmount(signedAmount.abs());
        transaction.setBalance(balance);
        transaction.setType(signedAmount.signum() < 0 ? "EXPENSE" : "INCOME");

        // El hash surt dels camps ja normalitzats de l'entitat i no de les
        // cadenes crues, perquè s'ha de poder tornar a calcular en confirmar
        // la importació, quan les cadenes ja no existeixen.
        transaction.setVerificationHash(transactionHasher.hash(transaction));
        return transaction;
    }

    /**
     * Converteix un import d'extracte bancari a BigDecimal.
     *
     * L'antiga versió esborrava tots els punts i després canviava la coma pel
     * punt decimal. Això va bé per al format europeu ("1.234,56") però
     * multiplicava per 100 qualsevol import en format anglosaxó ("45.30" es
     * convertia en 4530). Ara es detecta quin és el separador decimal mirant
     * quin dels dos apareix més a la dreta.
     */
    private BigDecimal cleanNumber(String rawAmount) {
        if (rawAmount == null || rawAmount.isBlank()) return BigDecimal.ZERO;

        String cleaned = rawAmount.replaceAll("[^0-9,.\\-]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-")) return BigDecimal.ZERO;

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            // Hi ha els dos: el que va més a la dreta és el decimal.
            if (lastComma > lastDot) {
                cleaned = cleaned.replace(".", "").replace(',', '.');
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // Només comes. Si en queden dues o més, o en separa exactament tres
            // xifres, són separadors de milers ("1,234"); si no, és el decimal.
            long commaCount = cleaned.chars().filter(character -> character == ',').count();
            boolean thousandsGroup = cleaned.length() - lastComma - 1 == 3;
            cleaned = (commaCount > 1 || thousandsGroup)
                    ? cleaned.replace(",", "")
                    : cleaned.replace(',', '.');
        } else if (lastDot >= 0) {
            long dotCount = cleaned.chars().filter(character -> character == '.').count();
            boolean thousandsGroup = cleaned.length() - lastDot - 1 == 3;
            if (dotCount > 1 || thousandsGroup) {
                cleaned = cleaned.replace(".", "");
            }
        }

        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            // Abans es retornava 0.0 en silenci i el moviment es desava amb
            // import zero. Millor avortar la importació que corrompre les dades.
            throw new IllegalArgumentException("Import il·legible al CSV: \"" + rawAmount + "\"", exception);
        }
    }

}
