package com.budgetai.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// La programació esborra cada hora les sessions caducades (SessionService).
@SpringBootApplication
@EnableScheduling
public class BudgetAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgetAiApplication.class, args);
    }

}
