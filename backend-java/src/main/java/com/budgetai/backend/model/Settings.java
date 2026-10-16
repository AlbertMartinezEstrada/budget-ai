package com.budgetai.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settings")
public class Settings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "currency")
    private String currency;

    @Column(name = "theme")
    private String theme;

    @Column(name = "notifications_expenses")
    private Boolean notificationsExpenses;

    @Column(name = "notifications_budget")
    private Boolean notificationsBudget;

    @Column(name = "notifications_monthly")
    private Boolean notificationsMonthly;

    /**
     * Sou mensual de referència sobre el qual s'apliquen els percentatges
     * dels pressupostos. És el valor per defecte; un mes concret es pot
     * sobreescriure a la taula monthly_income.
     */
    @Column(name = "expected_monthly_income", precision = 15, scale = 2)
    private BigDecimal expectedMonthlyIncome;

    /**
     * L'idioma de la interfície: ca, es, pt o en. Buit vol dir el del
     * navegador, que és com queda una instal·lació que ja existia fins que no
     * se'n tria un. Es desa aquí, com el tema, perquè el PC i el mòbil facin
     * servir el mateix.
     */
    @Column(name = "language", length = 5)
    private String language;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Els valors per defecte, aquí i no a la declaració dels camps (regla 3
     * d'AGENTS.md). Amb currency = "EUR" i theme = "light" als camps, el cos
     * d'un PUT que només portava el nom arribava amb el tema clar i les
     * notificacions per defecte: desar el perfil, o l'idioma, tornava el tema
     * fosc a clar.
     */
    @PrePersist
    protected void onCreate() {
        if (currency == null) currency = "EUR";
        if (theme == null) theme = "light";
        if (notificationsExpenses == null) notificationsExpenses = true;
        if (notificationsBudget == null) notificationsBudget = true;
        if (notificationsMonthly == null) notificationsMonthly = false;
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public Boolean getNotificationsExpenses() { return notificationsExpenses; }
    public void setNotificationsExpenses(Boolean notificationsExpenses) { this.notificationsExpenses = notificationsExpenses; }

    public Boolean getNotificationsBudget() { return notificationsBudget; }
    public void setNotificationsBudget(Boolean notificationsBudget) { this.notificationsBudget = notificationsBudget; }

    public Boolean getNotificationsMonthly() { return notificationsMonthly; }
    public void setNotificationsMonthly(Boolean notificationsMonthly) { this.notificationsMonthly = notificationsMonthly; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public BigDecimal getExpectedMonthlyIncome() { return expectedMonthlyIncome; }
    public void setExpectedMonthlyIncome(BigDecimal expectedMonthlyIncome) { this.expectedMonthlyIncome = expectedMonthlyIncome; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}