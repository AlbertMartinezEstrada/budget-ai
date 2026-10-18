package com.budgetai.backend.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Els missatges del backend en l'idioma de qui ho demana, com els textos del
 * frontend: una clau per missatge i un fitxer per idioma
 * (i18n/messages_ca.properties, _es, _pt, _en), amb els valors com a {nom}.
 *
 * Abans tots els errors sortien en català, també amb la interfície en anglès.
 * L'idioma el diu la capçalera Accept-Language que envia el frontend; sense
 * capçalera, o amb un idioma que no hi és, el català, que és el de referència.
 *
 * No fa servir MessageSource de Spring: el seu MessageFormat pren l'apòstrof
 * com a caràcter especial, i el català en va ple («L'import {0}» perdia el
 * valor). Així, a més, els fitxers tenen el mateix format que els del frontend.
 */
public final class Messages {

    private static final Logger LOG = LoggerFactory.getLogger(Messages.class);

    /** Els mateixos que el frontend, amb el locale dels números de cadascun. */
    public static final List<String> LANGUAGES = List.of("ca", "es", "pt", "en");
    public static final String REFERENCE = "ca";
    private static final Map<String, Locale> LOCALES = Map.of(
            "ca", Locale.forLanguageTag("ca-ES"),
            "es", Locale.forLanguageTag("es-ES"),
            "pt", Locale.forLanguageTag("pt-PT"),
            "en", Locale.forLanguageTag("en-GB"));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Map<String, Map<String, String>> CATALOGUES = loadAll();

    private Messages() {
    }

    /** El fitxer d'un idioma, llegit en UTF-8. Package-private per als tests. */
    static Map<String, String> catalogue(String language) {
        return CATALOGUES.get(language);
    }

    private static Map<String, Map<String, String>> loadAll() {
        Map<String, Map<String, String>> all = new HashMap<>();
        for (String language : LANGUAGES) all.put(language, load(language));
        return Map.copyOf(all);
    }

    private static Map<String, String> load(String language) {
        String path = "/i18n/messages_" + language + ".properties";
        try (InputStream stream = Messages.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Falta " + path);
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> entries = new HashMap<>();
            properties.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
            return Map.copyOf(entries);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** ca, es, pt o en; qualsevol altra cosa (fr, null), el català. */
    public static String language(Locale locale) {
        // List.of(...).contains(null) llança una excepció: cal mirar-ho abans.
        String code = locale != null ? locale.getLanguage() : null;
        return code != null && LANGUAGES.contains(code) ? code : REFERENCE;
    }

    /**
     * L'idioma de la petició que s'està responent. Fora d'una petició (un test
     * que crida el servei directament, una tasca programada), el català.
     */
    public static String current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        return language(context != null ? context.getLocale() : null);
    }

    /** Un missatge en l'idioma de la petició: per a les respostes que no són errors. */
    public static String get(String key, Object... namesAndValues) {
        return Message.of(key, namesAndValues).in(current());
    }

    /**
     * El text d'un error per a l'usuari, en l'idioma de la petició. Un error
     * que no porta clau (el d'una llibreria) surt tal com és.
     */
    public static String forUser(Throwable exception) {
        Object reason = reasonOf(exception);
        return reason instanceof Message message ? message.in(current()) : (String) reason;
    }

    /**
     * El que diu un error, per posar-lo dins d'un altre missatge: «Línia 4 de
     * l'extracte: {reason}». Si porta clau, es traduirà amb la frase de fora.
     */
    public static Object reasonOf(Throwable exception) {
        return exception instanceof Translatable translatable ? translatable.message() : exception.getMessage();
    }

    /**
     * El text d'un missatge en un idioma, amb els {noms} substituïts.
     *
     * Amb un valor «count» enter es tria el plural: clau.one si és 1 i
     * clau.other si no (en els quatre idiomes, el singular és només l'1).
     * Una clau que falta en un idioma surt en català, i si no hi és en cap,
     * surt la clau: un error del codi que el test de MessagesTest no deixa
     * arribar enlloc.
     */
    static String text(String language, Message message) {
        String code = language != null && LANGUAGES.contains(language) ? language : REFERENCE;
        String key = pluralKey(code, message);
        String template = CATALOGUES.get(code).getOrDefault(key, CATALOGUES.get(REFERENCE).get(key));
        if (template == null) {
            LOG.warn("Falta el missatge «{}» ({})", key, code);
            return message.key();
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = message.values().containsKey(name)
                    ? format(code, message.values().get(name))
                    : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String pluralKey(String language, Message message) {
        Object count = message.values().get("count");
        if (!(count instanceof Number number)) return message.key();
        String plural = message.key() + (number.doubleValue() == 1 ? ".one" : ".other");
        return CATALOGUES.get(language).containsKey(plural) || CATALOGUES.get(REFERENCE).containsKey(plural)
                ? plural
                : message.key();
    }

    /**
     * Un valor dins del text. Els imports (BigDecimal) van amb el format de
     * l'idioma —«1.234,50» en català, «1,234.50» en anglès—; els enters, tal
     * qual, perquè un identificador no porti punts de milers.
     */
    private static String format(String language, Object value) {
        if (value instanceof Message nested) return text(language, nested);
        if (value instanceof BigDecimal amount) {
            NumberFormat format = NumberFormat.getNumberInstance(LOCALES.get(language));
            int decimals = Math.max(0, Math.min(amount.scale(), 2));
            format.setMinimumFractionDigits(decimals);
            format.setMaximumFractionDigits(decimals);
            return format.format(amount);
        }
        return String.valueOf(value);
    }
}
