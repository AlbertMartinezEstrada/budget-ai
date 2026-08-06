package com.budgetai.backend.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base dels tests d'integració.
 *
 * Aixequen un PostgreSQL de debò amb Testcontainers, no una base de dades en
 * memòria: l'esquema fa servir NUMERIC amb escala fixa, SERIAL i claus
 * foranes amb el comportament de PostgreSQL, i amb H2 aquests tests
 * comprovarien una cosa diferent de la que s'executa en producció.
 *
 * L'esquema surt del mateix init.sql que fa servir docker-compose. Això vol
 * dir que aquests tests també comproven que init.sql segueixi quadrant amb
 * les entitats: amb ddl-auto=validate, qualsevol columna que hi falti fa que
 * el context de Spring no arrenqui i tots els tests fallin. Precisament així
 * es va detectar que hi faltava la taula settings.
 *
 * El contenidor és estàtic i es comparteix entre totes les classes, perquè
 * arrencar-ne un per classe multiplicaria el temps per res.
 */
@Tag("integration")
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("budget_test")
            .withUsername("test")
            .withPassword("test")
            // Igual que a docker-compose: l'script s'executa en inicialitzar.
            .withCopyFileToContainer(
                    MountableFile.forHostPath("init.sql"),
                    "/docker-entrypoint-initdb.d/init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // validate, no update: si l'esquema d'init.sql i les entitats no
        // quadren, volem que salti aquí i no en producció.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Credencials de prova. En producció no tenen valor per defecte i la
        // seva absència impedeix arrencar.
        registry.add("app.auth.username", () -> "test-user");
        registry.add("app.auth.password", () -> "test-password");
        registry.add("app.auth.jwt-secret", () -> "clau-de-proves-prou-llarga-per-a-hmac-sha256");
        registry.add("gemini.api.key", () -> "");
    }
}
