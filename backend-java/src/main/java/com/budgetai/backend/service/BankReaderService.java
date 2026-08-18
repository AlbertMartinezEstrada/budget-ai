package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class BankReaderService {

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
        return isRevolut(header) ? readRevolut(content) : readClassic(content);
    }

    /**
     * Revolut porta l'import a "Montante" i la comissió en una columna a part.
     * Cap dels dos noms surt a l'altre format, així que n'hi ha prou de mirar-ho.
     */
    private boolean isRevolut(String header) {
        return header.contains("Montante") || header.contains("Data de Conclus");
    }

    private CSVParser parse(String content, char delimiter) throws IOException {
        return new CSVParser(new BufferedReader(new StringReader(content)), CSVFormat.DEFAULT
                .withDelimiter(delimiter)
                .withFirstRecordAsHeader()
                .withIgnoreHeaderCase()
                .withTrim());
    }

    /** El format de sempre: Fecha;Concepto;Importe. */
    private List<Transaction> readClassic(String content) throws IOException {
        List<Transaction> transactions = new ArrayList<>();

        try (CSVParser csvParser = parse(content, ';')) {
            for (CSVRecord csvRecord : csvParser) {
                String saldoStr = csvRecord.isMapped("Saldo") ? csvRecord.get("Saldo") : null;

                BigDecimal amount = cleanNumber(csvRecord.get("Importe"));
                BigDecimal balance = (saldoStr != null) ? cleanNumber(saldoStr) : null;

                transactions.add(build(
                        LocalDate.parse(csvRecord.get("Fecha"), DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        csvRecord.get("Concepto"), amount, balance));
            }
        }
        return transactions;
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
    private List<Transaction> readRevolut(String content) throws IOException {
        List<Transaction> transactions = new ArrayList<>();

        try (CSVParser csvParser = parse(content, ',')) {
            for (CSVRecord csvRecord : csvParser) {
                if (!isCompleted(column(csvRecord, "Estado", "Estat", "State"))) continue;

                BigDecimal amount = cleanNumber(column(csvRecord, "Montante", "Amount"));
                BigDecimal fee = cleanNumber(column(csvRecord, "Comissão", "Comissao", "Fee"));
                BigDecimal net = amount.subtract(fee);

                // Una fila sense moviment no és res que calgui desar.
                if (net.signum() == 0) continue;

                LocalDate date = parseDateTime(column(csvRecord, "Data de Conclusão",
                        "Data de Conclusao", "Completed Date"));
                if (date == null) continue;

                String concept = column(csvRecord, "Descrição", "Descricao", "Description");
                if (concept == null || concept.isBlank()) {
                    concept = column(csvRecord, "Tipo", "Type");
                }

                transactions.add(build(date, concept, net,
                        cleanNumber(column(csvRecord, "Saldo", "Balance"))));
            }
        }
        return transactions;
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
        } catch (Exception e) {
            throw new IllegalArgumentException("Data il·legible al CSV: \"" + value + "\"", e);
        }
    }

    /**
     * El signe del moviment viu al tipus i l'import es desa en positiu, igual
     * que a la resta de l'aplicació.
     */
    private Transaction build(LocalDate date, String concept, BigDecimal signedAmount, BigDecimal balance) {
        Transaction t = new Transaction();
        t.setOriginalConcept(concept);
        t.setDate(date);
        t.setAmount(signedAmount.abs());
        t.setBalance(balance);
        t.setType(signedAmount.signum() < 0 ? "EXPENSE" : "INCOME");

        // El hash surt dels camps ja normalitzats de l'entitat i no de les
        // cadenes crues, perquè s'ha de poder tornar a calcular en confirmar
        // la importació, quan les cadenes ja no existeixen.
        t.setVerificationHash(transactionHasher.hash(t));
        return t;
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
    private BigDecimal cleanNumber(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;

        String cleaned = val.replaceAll("[^0-9,.\\-]", "").trim();
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
            long commaCount = cleaned.chars().filter(c -> c == ',').count();
            boolean thousandsGroup = cleaned.length() - lastComma - 1 == 3;
            cleaned = (commaCount > 1 || thousandsGroup)
                    ? cleaned.replace(",", "")
                    : cleaned.replace(',', '.');
        } else if (lastDot >= 0) {
            long dotCount = cleaned.chars().filter(c -> c == '.').count();
            boolean thousandsGroup = cleaned.length() - lastDot - 1 == 3;
            if (dotCount > 1 || thousandsGroup) {
                cleaned = cleaned.replace(".", "");
            }
        }

        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            // Abans es retornava 0.0 en silenci i el moviment es desava amb
            // import zero. Millor avortar la importació que corrompre les dades.
            throw new IllegalArgumentException("Import il·legible al CSV: \"" + val + "\"", e);
        }
    }

}
