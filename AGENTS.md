# Budget AI - Common Configuration

## Project Overview
Multi-component budget management application with:
- **frontend**: Node.js/Express web client
- **backend-java**: Java 17/Spring Boot API (main backend)
- **backend-python-legacy**: Python API (deprecated)

## Common Rules
- Each component should be opened as a separate project in IDE
- Use environment variables from `.env` for configuration
- Database: PostgreSQL (configured in docker-compose.yml)

## SDK Requirements
- Java 17 for backend-java
- Node.js for frontend
- Python for backend-python-legacy

## Commands
- Backend: `./gradlew bootRun` (in backend-java directory)
- Frontend: `pnpm start` (in frontend directory)