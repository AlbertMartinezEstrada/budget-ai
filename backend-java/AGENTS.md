# Backend Java - Spring Boot API

## Project Type
Java 17 REST API using Spring Boot

## Tech Stack
- **Language**: Java 17
- **Framework**: Spring Boot 3.2.2
- **Build Tool**: Gradle
- **Database**: PostgreSQL

## SDK Required
- JDK 17 (configure in IntelliJ IDEA)

## Key Components
- **Models**: Transaction, Account, Budget, Category, FinancialGoal, RecurringTransaction, Transfer, Company
- **Controllers**: TransactionController, AccountController, BudgetController, AnalyticsController, etc.
- **Services**: AiEngineService, AnalyticsService, BankReaderService, BudgetService, etc.
- **Repositories**: JPA repositories for each entity

## Run
```bash
./gradlew bootRun
```

## Dependencies
- spring-boot-starter-data-jpa
- spring-boot-starter-web
- commons-csv
- postgresql
- lombok