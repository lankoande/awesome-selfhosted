package io.corebanking.security.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture et ecriture JSON, reduites au strict necessaire.
 *
 * <p>Le module de securite ne depend d'aucune bibliotheque tierce. Ce n'est pas de la coquetterie :
 * une dependance de ce module est une dependance qui s'execute dans le chemin d'etablissement de
 * l'identite et de chargement des habilitations. Cent cinquante lignes lues et testees valent mieux,
 * ici, qu'un analyseur generaliste et sa chaine de transitives.
 *
 * <p>Le sous-ensemble couvre ce qu'un fichier de catalogue emploie : objets, tableaux, chaines,
 * nombres, booleens, {@code null}. Ni commentaires, ni virgule finale, ni references.
 */
public final class Json {

    private Json() {}

    // ================================================================== lecture

    public static Map<String, Object> parseObject(String source) {
        Parser parser = new Parser(source);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        parser.expectEnd();
        if (!(value instanceof Map)) {
            throw new JsonException("Objet JSON attendu a la racine du document");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** Tableau JSON a la racine : forme que renvoient les listes de l'API d'administration. */
    public static List<Object> parseArray(String source) {
        Parser parser = new Parser(source);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        parser.expectEnd();
        if (!(value instanceof List)) {
            throw new JsonException("Tableau JSON attendu a la racine du document");
        }
        @SuppressWarnings("unchecked")
        List<Object> array = (List<Object>) value;
        return array;
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        Object parseValue() {
            skipWhitespace();
            if (index >= source.length()) {
                throw new JsonException("Document JSON tronque");
            }
            char c = source.charAt(index);
            return switch (c) {
                case '{' -> parseObjectValue();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObjectValue() {
            Map<String, Object> object = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw new JsonException("« , » ou « } » attendu a la position " + (index - 1));
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> array = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw new JsonException("« , » ou « ] » attendu a la position " + (index - 1));
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            sb.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                            index += 4;
                        }
                        default -> throw new JsonException(
                            "Echappement inconnu « \\" + escaped + " »");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Boolean parseBoolean() {
            if (source.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (source.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Valeur booleenne attendue a la position " + index);
        }

        private Object parseNull() {
            if (!source.startsWith("null", index)) {
                throw new JsonException("« null » attendu a la position " + index);
            }
            index += 4;
            return null;
        }

        private Object parseNumber() {
            int start = index;
            while (index < source.length() && "-+.eE0123456789".indexOf(source.charAt(index)) >= 0) {
                index++;
            }
            if (start == index) {
                throw new JsonException(
                    "Valeur inattendue « " + source.charAt(index) + " » a la position " + index);
            }
            return new java.math.BigDecimal(source.substring(start, index));
        }

        void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        void expectEnd() {
            if (index < source.length()) {
                throw new JsonException(
                    "Contenu inattendu apres la fin du document, position " + index);
            }
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw new JsonException("« " + expected + " » attendu a la position " + (index - 1));
            }
        }

        private char peek() {
            if (index >= source.length()) {
                throw new JsonException("Document JSON tronque");
            }
            return source.charAt(index);
        }

        private char next() {
            if (index >= source.length()) {
                throw new JsonException("Document JSON tronque");
            }
            return source.charAt(index++);
        }
    }

    // ================================================================== ecriture

    public static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
