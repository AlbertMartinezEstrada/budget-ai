package com.budgetai.backend.i18n;

import com.budgetai.backend.service.InvalidInputException;
import com.budgetai.backend.service.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Els missatges del backend en els quatre idiomes.
 *
 * Com i18n.test.js al frontend, vigila el que no dona cap error i es veu
 * malament: una clau que falta en un idioma (surt en català), un {valor} que
 * una traducció no porta (surt «{name}» a la pantalla) i una clau que el codi
 * fa servir i no existeix (surt la clau).
 */
class MessagesTest {

    private static final Path RESOURCES = Path.of("src/main/resources/i18n");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern PLURAL = Pattern.compile("\\.(one|other)$");

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    @Test
    @DisplayName("Els quatre idiomes tenen exactament les mateixes claus que el català")
    void sameKeysInEveryLanguage() {
        Set<String> reference = Messages.catalogue(Messages.REFERENCE).keySet();
        assertThat(reference).isNotEmpty();
        for (String language : Messages.LANGUAGES) {
            assertThat(Messages.catalogue(language).keySet()).as(language).isEqualTo(reference);
        }
    }

    @Test
    @DisplayName("Cada traducció porta els mateixos {valors} que la de referència")
    void samePlaceholdersInEveryLanguage() {
        List<String> problems = new ArrayList<>();
        Messages.catalogue(Messages.REFERENCE).forEach((key, text) -> {
            for (String language : Messages.LANGUAGES) {
                String translation = Messages.catalogue(language).get(key);
                if (translation != null && !placeholders(translation).equals(placeholders(text))) {
                    problems.add(language + " " + key);
                }
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("Cap clau surt dues vegades en un mateix fitxer")
    void noDuplicatedKeys() throws IOException {
        // Properties es queda l'última sense dir res: cal llegir les línies.
        for (String language : Messages.LANGUAGES) {
            List<String> keys = Files.readAllLines(RESOURCES.resolve("messages_" + language + ".properties"),
                            StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.substring(0, line.indexOf('=')).trim())
                    .toList();
            assertThat(keys).as(language).doesNotHaveDuplicates();
        }
    }

    /** Les cadenes del codi amb forma de clau d'un dels espais de noms dels fitxers. */
    private static Map<String, Set<Path>> keysUsedInCode() throws IOException {
        Set<String> namespaces = Messages.catalogue(Messages.REFERENCE).keySet().stream()
                .map(key -> key.substring(0, key.indexOf('.')))
                .collect(Collectors.toSet());
        Pattern literal = Pattern.compile("\"([a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"");
        Map<String, Set<Path>> used = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (namespaces.contains(key.substring(0, key.indexOf('.')))) {
                        used.computeIfAbsent(key, unused -> new HashSet<>()).add(file.getFileName());
                    }
                }
            }
        }
        return used;
    }

    @Test
    @DisplayName("Cada clau que fa servir el codi existeix")
    void everyKeyUsedExists() throws IOException {
        Map<String, String> reference = Messages.catalogue(Messages.REFERENCE);
        List<String> missing = keysUsedInCode().entrySet().stream()
                .filter(entry -> !reference.containsKey(entry.getKey())
                        && !reference.containsKey(entry.getKey() + ".other"))
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .toList();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("Cap clau dels fitxers queda sense fer servir")
    void noUnusedKeys() throws IOException {
        Set<String> used = keysUsedInCode().keySet();
        List<String> unused = Messages.catalogue(Messages.REFERENCE).keySet().stream()
                .map(key -> used.contains(key) ? key : PLURAL.matcher(key).replaceAll(""))
                .filter(key -> !used.contains(key))
                .distinct()
                .sorted()
                .toList();
        assertThat(unused).isEmpty();
    }

    @Test
    @DisplayName("Els valors se substitueixen, i un missatge dins d'un altre va en el mateix idioma")
    void valuesAndNestedMessages() {
        Message reason = Message.of("upload.date-missing");
        Message line = Message.of("upload.line-error", "line", 4, "format", Message.of("upload.format-revolut"),
                "reason", reason);

        assertThat(line.in("ca")).isEqualTo("Línia 4 de l'extracte (Revolut): falta la data.");
        assertThat(line.in("es")).isEqualTo("Línea 4 del extracto (Revolut): falta la fecha.");
        assertThat(line.in("en")).isEqualTo("Line 4 of the statement (Revolut): the date is missing.");
        // Un valor que no es dona es queda com a {nom}: es veu, i el test de dalt l'evita.
        assertThat(Message.of("months.reversed", "from", "2026-10").in("ca")).contains("({to})");
    }

    @Test
    @DisplayName("El plural es tria amb {count}, i els imports van amb el format de cada idioma")
    void pluralsAndAmounts() {
        assertThat(Message.of("upload.saved", "count", 1).in("ca")).isEqualTo("1 moviment desat correctament.");
        assertThat(Message.of("upload.saved", "count", 3).in("ca")).isEqualTo("3 moviments desats correctament.");
        assertThat(Message.of("upload.saved", "count", 0).in("pt")).isEqualTo("0 movimentos guardados com sucesso.");

        Message missing = Message.of("split.total-short", "parts", new BigDecimal("1234.50"),
                "total", new BigDecimal("1284.50"), "difference", new BigDecimal("50.00"));
        assertThat(missing.in("ca")).isEqualTo("Les parts sumen 1.234,50 € i el moviment és de 1.284,50 €: en falten 50,00 €.");
        assertThat(missing.in("en")).isEqualTo("The parts add up to €1,234.50 and the transaction is €1,284.50: €50.00 short.");
        // Un identificador no porta punts de milers.
        assertThat(Message.of("not-found.account", "id", 12345L).in("es")).startsWith("No existe la cuenta 12345.");
    }

    @Test
    @DisplayName("L'idioma és el de la petició; sense petició, o amb un que no hi és, el català")
    void languageOfTheRequest() {
        InvalidInputException error = new InvalidInputException("debts.name-missing");
        // getMessage() és el del log: sempre en català.
        assertThat(error.getMessage()).isEqualTo("Posa-li un nom: a qui o per a què.");
        assertThat(Messages.forUser(error)).isEqualTo("Posa-li un nom: a qui o per a què.");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("pt-BR"));
        assertThat(Messages.current()).isEqualTo("pt");
        assertThat(Messages.forUser(error)).isEqualTo("Dê-lhe um nome: a quem ou para quê.");
        assertThat(Messages.forUser(new NotFoundException("not-found.goal", 3))).startsWith("O objetivo 3 não existe.");

        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(Messages.current()).isEqualTo("ca");
        // Un error d'una llibreria, sense clau, surt tal com és.
        assertThat(Messages.forUser(new IllegalArgumentException("text de la llibreria"))).isEqualTo("text de la llibreria");
    }
}
