package io.corebanking.ledger.store;

import java.util.Map;

/**
 * Serialisation JSON minimale des metadonnees d'ecriture. Volontairement reduite au strict
 * necessaire : le ledger ne depend d'aucune bibliotheque de mapping.
 */
final class Json {

    private Json() {}

    static String of(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, entry.getKey()).append(':');
            if (entry.getValue() == null) {
                sb.append("null");
            } else {
                quote(sb, entry.getValue());
            }
        }
        return sb.append('}').toString();
    }

    private static StringBuilder quote(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
