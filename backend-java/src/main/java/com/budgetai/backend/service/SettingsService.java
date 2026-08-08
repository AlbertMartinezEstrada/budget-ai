package com.budgetai.backend.service;

import com.budgetai.backend.model.Settings;
import com.budgetai.backend.repository.SettingsRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SettingsService {

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public Settings getSettings() {
        Optional<Settings> existing = settingsRepository.findFirstBy();
        if (existing.isPresent()) {
            return existing.get();
        }
        Settings settings = new Settings();
        settings.setUserName("Usuario");
        settings.setUserEmail("usuario@ejemplo.com");
        settings.setCurrency("EUR");
        settings.setTheme("light");
        settings.setNotificationsExpenses(true);
        settings.setNotificationsBudget(true);
        settings.setNotificationsMonthly(false);
        return settingsRepository.save(settings);
    }

    public Settings updateSettings(Settings settings) {
        Optional<Settings> existing = settingsRepository.findFirstBy();
        if (existing.isPresent()) {
            Settings s = existing.get();
            if (settings.getUserName() != null) s.setUserName(settings.getUserName());
            if (settings.getUserEmail() != null) s.setUserEmail(settings.getUserEmail());
            if (settings.getCurrency() != null) s.setCurrency(settings.getCurrency());
            if (settings.getTheme() != null) s.setTheme(settings.getTheme());
            if (settings.getNotificationsExpenses() != null) s.setNotificationsExpenses(settings.getNotificationsExpenses());
            if (settings.getNotificationsBudget() != null) s.setNotificationsBudget(settings.getNotificationsBudget());
            if (settings.getNotificationsMonthly() != null) s.setNotificationsMonthly(settings.getNotificationsMonthly());
            // Un import negatiu vol dir "esborra'l", igual que a la resta de
            // l'API: sense això no hi hauria manera de treure el sou un cop posat.
            if (settings.getExpectedMonthlyIncome() != null) {
                s.setExpectedMonthlyIncome(settings.getExpectedMonthlyIncome().signum() < 0
                        ? null : settings.getExpectedMonthlyIncome());
            }
            return settingsRepository.save(s);
        }
        return settingsRepository.save(settings);
    }
}