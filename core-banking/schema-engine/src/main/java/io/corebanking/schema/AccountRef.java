package io.corebanking.schema;

import java.util.Objects;

/**
 * Reference a un compte dans un schema comptable.
 *
 * <p>Un schema ne designe jamais un compte par son identifiant technique : il le designe par son
 * role. C'est ce qui permet au meme schema de fonctionner dans une filiale ivoirienne et dans une
 * filiale europeenne, dont les plans comptables n'ont ni les memes codes ni la meme profondeur.
 *
 * <table>
 *   <caption>Formes acceptees</caption>
 *   <tr><td>{@code GL:70611}</td><td>compte general par code interne, resolu dans le plan de l'entite</td></tr>
 *   <tr><td>{@code CONTRACT}</td><td>compte du contrat concerne par l'evenement</td></tr>
 *   <tr><td>{@code RESOLVE:cash}</td><td>resolveur nomme : caisse de l'agence, nostro, suspens</td></tr>
 *   <tr><td>{@code PARAM:fee_income}</td><td>compte designe par un parametre du produit</td></tr>
 * </table>
 */
public record AccountRef(Kind kind, String value) {

    public enum Kind { GL, CONTRACT, RESOLVER, PARAMETER }

    public AccountRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    public static AccountRef parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.trim();
        if (trimmed.equals("CONTRACT")) {
            return new AccountRef(Kind.CONTRACT, "");
        }
        int separator = trimmed.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException(
                "Reference de compte « " + raw + " » non reconnue. Formes attendues : CONTRACT, "
                + "GL:<code>, RESOLVE:<nom>, PARAM:<nom>.");
        }
        String prefix = trimmed.substring(0, separator);
        String suffix = trimmed.substring(separator + 1).trim();
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("Reference de compte « " + raw + " » sans valeur");
        }
        return switch (prefix) {
            case "GL"      -> new AccountRef(Kind.GL, suffix);
            case "RESOLVE" -> new AccountRef(Kind.RESOLVER, suffix);
            case "PARAM"   -> new AccountRef(Kind.PARAMETER, suffix);
            default -> throw new IllegalArgumentException(
                "Prefixe de reference inconnu « " + prefix + " » dans « " + raw + " »");
        };
    }

    @Override
    public String toString() {
        return kind == Kind.CONTRACT ? "CONTRACT" : switch (kind) {
            case GL -> "GL:" + value;
            case RESOLVER -> "RESOLVE:" + value;
            case PARAMETER -> "PARAM:" + value;
            default -> value;
        };
    }
}
