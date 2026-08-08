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
    private String currency = "EUR";

    @Column(name = "theme")
    private String theme = "light";

    @Column(name = "notifications_expenses")
    private Boolean notificationsExpenses = true;

    @Column(name = "notifications_budget")
    private Boolean notificationsBudget = true;

    @Column(name = "notifications_monthly")
    private Boolean notificationsMonthly = false;

    /**
     * Sou mensual de referència sobre el qual s'apliquen els percentatges
     * dels pressupostos. És el valor per defecte; un mes concret es pot
     * sobreescriure a la taula monthly_income.
     */
    @Column(name = "expected_monthly_income", precision = 15, scale = 2)
    private BigDecimal expectedMonthlyIncome;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
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

    public BigDecimal getExpectedMonthlyIncome() { return expectedMonthlyIncome; }
    public void setExpectedMonthlyIncome(BigDecimal expectedMonthlyIncome) { this.expectedMonthlyIncome = expectedMonthlyIncome; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}