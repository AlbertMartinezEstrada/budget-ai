# Backend Java (Spring Boot)

This is the migrated backend for Budget AI, using Java 17 and Spring Boot 3.

## Structure

- `src/main/java/com/budgetai/backend`: Source code.
  - `controller`: API Endpoints.
  - `service`: Business logic (CSV processing, Gemini AI integration).
  - `model`: JPA Entities.
  - `repository`: Data access.

## Running with Docker

The project is configured to run with Docker Compose from the root directory:

```bash
docker-compose up --build
```

## Running Locally

You need Java 17 and Gradle installed.

1.  Set environment variables (or rely on defaults in `application.properties`):
    - `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`
    - `GEMINI_API_KEY`

2.  Run the application:

```bash
gradle bootRun
```
