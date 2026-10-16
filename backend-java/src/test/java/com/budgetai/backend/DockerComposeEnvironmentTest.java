package com.budgetai.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada variable d'entorn que llegeix el backend arriba al contenidor.
 *
 * Amb Docker, el backend només veu les variables que docker-compose.yml li
 * passa a «environment». Ja ha passat dues vegades que una opció
 * documentada no hi era i posar-la al .env no feia res: primer
 * CORS_ALLOWED_ORIGINS, després les de la sessió (AUTH_SECURE_COOKIE,
 * AUTH_SESSION_DURATION i els bloquejos del login).
 */
class DockerComposeEnvironmentTest {

    private static final Pattern PROPERTY_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
    private static final Pattern COMPOSE_VARIABLE = Pattern.compile("^\\s+-\\s+([A-Z][A-Z0-9_]*)=", Pattern.MULTILINE);

    @Test
    @DisplayName("Cada variable de application.properties es passa al contenidor del backend")
    void everyVariableReachesTheContainer() throws IOException {
        Set<String> readByBackend = matches(PROPERTY_VARIABLE,
                Files.readString(Path.of("src/main/resources/application.properties")));

        String compose = Files.readString(Path.of("../docker-compose.yml"));
        int backendStart = compose.indexOf("\n  backend:");
        int backendEnd = compose.indexOf("\n  frontend:", backendStart);
        assertThat(backendStart).as("servei backend a docker-compose.yml").isPositive();
        Set<String> passedByCompose = matches(COMPOSE_VARIABLE, compose.substring(backendStart, backendEnd));

        List<String> missing = readByBackend.stream()
                .filter(variable -> !passedByCompose.contains(variable))
                .sorted()
                .toList();
        assertThat(missing)
                .as("variables que el backend llegeix però docker-compose.yml no li passa")
                .isEmpty();
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(result -> result.group(1)).collect(Collectors.toSet());
    }
}
