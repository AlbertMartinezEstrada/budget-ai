package com.budgetai.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cap variable del codi es diu amb una sola lletra.
 *
 * `t`, `b` o `e` obliguen a buscar d'on surten per saber què són; el nom ho
 * ha de dir sol. Es comprova aquí perquè una regla que només és escrita
 * s'acaba oblidant.
 */
class NamingConventionTest {

    private static final List<Pattern> SINGLE_LETTER = List.of(
            // t -> ...
            Pattern.compile("(?<![\\w.])[a-z]\\s*->"),
            // (a, b) -> ...
            Pattern.compile("\\(\\s*[a-z](\\s*,\\s*[a-z])*\\s*\\)\\s*->"),
            // catch (Exception e)
            Pattern.compile("catch\\s*\\([\\w.|\\s]+\\s[a-z]\\s*\\)"),
            // Transaction t = ... / for (Transaction t : ...) / int i = 0
            Pattern.compile("\\b[A-Za-z_][\\w<>,?\\[\\] ]*\\s[a-z]\\s*[=:;](?!=)"),
            // void f(Transaction t) / f(String a, int b)
            Pattern.compile("[(,]\\s*(final\\s+)?[A-Z][\\w<>,?\\[\\]]*\\s+[a-z]\\s*[,)]"));

    @Test
    @DisplayName("Cap variable del backend es diu amb una sola lletra")
    void noSingleLetterNames() throws IOException {
        List<String> offenders;
        try (Stream<Path> sources = Files.walk(Path.of("src"))) {
            offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(NamingConventionTest::offendingLines)
                    .toList();
        }

        assertThat(offenders).as("variables d'una lletra").isEmpty();
    }

    private static Stream<String> offendingLines(Path path) {
        String code;
        try {
            code = withoutCommentsAndStrings(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        String[] lines = code.split("\n", -1);
        return IntStream.range(0, lines.length)
                .filter(lineIndex -> SINGLE_LETTER.stream().anyMatch(pattern -> pattern.matcher(lines[lineIndex]).find()))
                .mapToObj(lineIndex -> path + ":" + (lineIndex + 1) + ": " + lines[lineIndex].trim());
    }

    /**
     * Buida comentaris i literals conservant els salts de línia, perquè els
     * números de línia de l'error continuïn apuntant al lloc correcte. Sense
     * això, un comentari en català com "t'ho" o un text com "a" farien saltar
     * la regla.
     */
    private static String withoutCommentsAndStrings(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int position = 0;
        while (position < source.length()) {
            if (source.startsWith("/*", position)) {
                int end = source.indexOf("*/", position + 2);
                end = end < 0 ? source.length() : end + 2;
                keepNewlines(source, position, end, result);
                position = end;
            } else if (source.startsWith("//", position)) {
                int end = source.indexOf('\n', position);
                position = end < 0 ? source.length() : end;
            } else if (source.startsWith("\"\"\"", position)) {
                int end = source.indexOf("\"\"\"", position + 3);
                end = end < 0 ? source.length() : end + 3;
                keepNewlines(source, position, end, result);
                position = end;
            } else if (source.charAt(position) == '"' || source.charAt(position) == '\'') {
                char quote = source.charAt(position);
                int end = position + 1;
                while (end < source.length() && source.charAt(end) != quote && source.charAt(end) != '\n') {
                    if (source.charAt(end) == '\\') end++;
                    end++;
                }
                result.append("\"\"");
                position = Math.min(end + 1, source.length());
            } else {
                result.append(source.charAt(position));
                position++;
            }
        }
        return result.toString();
    }

    private static void keepNewlines(String source, int from, int to, StringBuilder result) {
        for (int index = from; index < to; index++) {
            if (source.charAt(index) == '\n') result.append('\n');
        }
    }
}
