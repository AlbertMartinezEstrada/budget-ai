package com.budgetai.backend.service;

import com.budgetai.backend.model.Transaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

    public List<Transaction> readBankCsv(MultipartFile file) throws IOException {
        List<Transaction> transactions = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withDelimiter(';')
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {

            for (CSVRecord csvRecord : csvParser) {
                String importeStr = csvRecord.get("Importe");
                String saldoStr = csvRecord.isMapped("Saldo") ? csvRecord.get("Saldo") : null;
                String concepto = csvRecord.get("Concepto");
                String fechaStr = csvRecord.get("Fecha");

                // Netegem els valors
                BigDecimal amount = cleanNumber(importeStr);
                BigDecimal balance = (saldoStr != null) ? cleanNumber(saldoStr) : null;

                Transaction t = new Transaction();
                t.setOriginalConcept(concepto);

                // Parsejar data DD/MM/YYYY
                LocalDate date = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                t.setDate(date);

                // Mantenim l'import original (podria ser positiu per ingresos)
                t.setAmount(amount.abs());
                t.setBalance(balance);

                // Determinem el tipus
                if (amount.signum() < 0) {
                    t.setType("EXPENSE");
                } else {
                    t.setType("INCOME");
                }
                
                // El hash surt dels camps ja normalitzats de l'entitat i no de
                // les cadenes crues, perquè s'ha de poder tornar a calcular en
                // confirmar la importació, quan les cadenes ja no existeixen.
                t.setVerificationHash(transactionHasher.hash(t));

                transactions.add(t);
            }
        }

        return transactions;
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
