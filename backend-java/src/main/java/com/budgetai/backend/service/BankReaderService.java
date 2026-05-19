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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class BankReaderService {

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
                double amount = cleanNumber(importeStr);
                Double balance = (saldoStr != null) ? cleanNumber(saldoStr) : null;

                Transaction t = new Transaction();
                t.setOriginalConcept(concepto);
                
                // Parsejar data DD/MM/YYYY
                LocalDate date = LocalDate.parse(fechaStr, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                t.setDate(date);
                
                // Mantenim l'import original (podria ser positiu per ingresos)
                t.setAmount(Math.abs(amount));
                t.setBalance(balance);
                
                // Determinem el tipus
                if (amount < 0) {
                    t.setType("EXPENSE");
                } else {
                    t.setType("INCOME");
                }
                
                // Generem el hash únic per evitar duplicats (data + concepte + import + saldo)
                String rawHashStr = fechaStr + concepto + importeStr + (saldoStr != null ? saldoStr : "");
                t.setVerificationHash(generateHash(rawHashStr));

                transactions.add(t);
            }
        }

        return transactions;
    }

    private double cleanNumber(String val) {
        if (val == null || val.isEmpty()) return 0.0;
        String cleaned = val.replace("EUR", "")
                .replace(".", "")
                .replace(",", ".")
                .trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return input; // Fallback al text original
        }
    }
}
