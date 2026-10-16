package com.budgetai.backend.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les migracions porten les bases de dades que ja existeixen al mateix lloc
 * que init.sql porta una de nova.
 *
 * Els altres tests d'integració fan la base de dades amb init.sql, i és
 * normal: és el que fa una instal·lació nova. Però la base de dades de debò
 * es va crear fa mesos i ha arribat on és amb scripts/migrate.sh. Si una
 * migració i init.sql no diuen el mateix, els tests passen i el backend no
 * arrenca a casa (ddl-auto=validate), o arrenca amb categories diferents.
 *
 * El test fa les dues coses en el mateix PostgreSQL dels altres tests: una
 * base de dades amb l'init.sql d'ara, i una altra amb l'init.sql de l'agost
 * (src/test/resources/migrations) més totes les migracions a partir de la 004.
 * Després compara columnes, restriccions, índexs i categories.
 */
@Tag("integration")
class MigrationsIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = AbstractIntegrationTest.POSTGRES;

    private static final Path INIT_SQL = Path.of("init.sql");
    private static final Path MIGRATIONS_DIR = Path.of("migrations");
    private static final Path BASELINE = Path.of("src/test/resources/migrations/schema_abans_de_004.sql");
    private static final int FIRST_MIGRATION_AFTER_BASELINE = 4;
    private static final Pattern MIGRATION_NAME = Pattern.compile("(\\d{3})_[a-z0-9_]+\\.sql");

    /** Què es compara, amb la consulta que ho descriu línia a línia. */
    private static final Map<String, String> SCHEMA_DESCRIPTION = new LinkedHashMap<>();

    static {
        SCHEMA_DESCRIPTION.put("columnes", """
                SELECT table_name || '.' || column_name || ' ' || data_type
                    || coalesce('(' || character_maximum_length || ')', '')
                    || coalesce(' precisió ' || numeric_precision || ',' || numeric_scale, '')
                    || ' nul=' || is_nullable || ' per defecte=' || coalesce(column_default, '-')
                FROM information_schema.columns WHERE table_schema = 'public' ORDER BY 1""");
        SCHEMA_DESCRIPTION.put("restriccions", """
                SELECT conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
                FROM pg_constraint WHERE connamespace = 'public'::regnamespace ORDER BY 1""");
        SCHEMA_DESCRIPTION.put("índexs", """
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY 1""");
        SCHEMA_DESCRIPTION.put("categories", """
                SELECT coalesce(parent.nom, '') || ' / ' || category.nom || ' / ' || coalesce(category.tipus_cost, '')
                FROM categories category LEFT JOIN categories parent ON parent.id = category.parent_id ORDER BY 1""");
    }

    @Test
    @DisplayName("Una base de dades de l'agost, amb totes les migracions, queda igual que una de nova")
    void migratedDatabaseMatchesAFreshOne() throws Exception {
        createDatabase("nova");
        runScript("nova", INIT_SQL);

        createDatabase("migrada");
        runScript("migrada", BASELINE);
        for (Path migration : migrationsAfterBaseline()) {
            runScript("migrada", migration);
        }

        assertSameDescription("migrada", "nova");
    }

    @Test
    @DisplayName("Cada migració es pot tornar a passar just després sense errors ni canvis")
    void eachMigrationCanRunTwiceInARow() throws Exception {
        // Totes diuen a la capçalera que són idempotents. Fa falta quan una
        // falla a mitges i es torna a llançar migrate.sh, o quan s'aplica però
        // no s'arriba a apuntar a schema_migrations.
        //
        // Just després d'ella mateixa, no al final de tot: una migració antiga
        // que redefineix el CHECK de tipus_cost (006, 014, 016) no admet els
        // valors que les posteriors hi han afegit. migrate.sh no en torna a
        // passar mai una d'antiga: o la té apuntada o la detecta per l'esquema.
        createDatabase("dues_vegades");
        runScript("dues_vegades", BASELINE);
        for (Path migration : migrationsAfterBaseline()) {
            runScript("dues_vegades", migration);
            Map<String, List<String>> afterFirstRun = describe("dues_vegades");

            runScript("dues_vegades", migration);

            assertThat(describe("dues_vegades"))
                    .as("%s aplicada dues vegades seguides", migration.getFileName())
                    .isEqualTo(afterFirstRun);
        }
    }

    @Test
    @DisplayName("Les migracions van numerades de tres en tres xifres, sense salts ni repetits")
    void migrationsAreNumberedInOrder() throws IOException {
        // migrate.sh les aplica en ordre de nom i apunta cada nom com a fet:
        // un número repetit o un nom amb un altre format se saltaria o
        // s'aplicaria en un ordre que no toca.
        List<String> names;
        try (Stream<Path> files = Files.list(MIGRATIONS_DIR)) {
            names = files.map(path -> path.getFileName().toString()).sorted().toList();
        }
        List<Integer> numbers = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = MIGRATION_NAME.matcher(name);
            assertThat(matcher.matches()).as("nom de migració «%s» (ha de ser NNN_nom.sql)", name).isTrue();
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        for (int position = 0; position < numbers.size(); position++) {
            assertThat(numbers.get(position)).as("migració a la posició %d", position + 1).isEqualTo(position + 1);
        }
    }

    // ---- Eines ----

    private static List<Path> migrationsAfterBaseline() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS_DIR)) {
            return files
                    .filter(path -> MIGRATION_NAME.matcher(path.getFileName().toString()).matches())
                    .filter(path -> Integer.parseInt(path.getFileName().toString().substring(0, 3))
                            >= FIRST_MIGRATION_AFTER_BASELINE)
                    .sorted()
                    .toList();
        }
    }

    private static void createDatabase(String database) throws Exception {
        psql("postgres", "-c", "DROP DATABASE IF EXISTS " + database, "-c", "CREATE DATABASE " + database);
    }

    /** Com ho fa migrate.sh: psql amb ON_ERROR_STOP, perquè un error aturi el fitxer i no passi desapercebut. */
    private static void runScript(String database, Path script) throws Exception {
        String inContainer = "/tmp/migracions/" + script.getFileName();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(script), inContainer);
        psql(database, "-f", inContainer);
    }

    private static String psql(String database, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("psql", "-X", "-q", "-tA", "-v", "ON_ERROR_STOP=1",
                "-U", POSTGRES.getUsername(), "-d", database));
        command.addAll(Arrays.asList(arguments));
        Container.ExecResult result = POSTGRES.execInContainer(command.toArray(String[]::new));
        assertThat(result.getExitCode())
                .as("psql a %s %s:%n%s", database, String.join(" ", arguments), result.getStderr())
                .isZero();
        return result.getStdout();
    }

    private static Map<String, List<String>> describe(String database) throws Exception {
        Map<String, List<String>> description = new LinkedHashMap<>();
        for (Map.Entry<String, String> aspect : SCHEMA_DESCRIPTION.entrySet()) {
            String output = psql(database, "-c", aspect.getValue());
            description.put(aspect.getKey(), output.isBlank() ? List.of() : List.of(output.strip().split("\n")));
        }
        return description;
    }

    private static void assertSameDescription(String migrated, String fresh) throws Exception {
        Map<String, List<String>> migratedDescription = describe(migrated);
        Map<String, List<String>> freshDescription = describe(fresh);
        for (String aspect : SCHEMA_DESCRIPTION.keySet()) {
            // Sense ordre: una columna afegida per ALTER TABLE queda al final i
            // a init.sql pot anar al mig, i això no importa a ningú.
            assertThat(migratedDescription.get(aspect))
                    .as("%s: la base de dades migrada (esquerra) contra una de nova feta amb init.sql", aspect)
                    .containsExactlyInAnyOrderElementsOf(freshDescription.get(aspect));
        }
    }
}
