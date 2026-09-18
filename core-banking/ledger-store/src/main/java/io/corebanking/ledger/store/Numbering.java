package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Numerotation : comment la banque compose un numero de client, de compte, de dossier.
 *
 * <h2>Pourquoi c'est du parametrage</h2>
 *
 * <p>Un numero de compte de la zone UEMOA est un RIB : code banque, code guichet, numero,
 * cle de controle. Un numero de dossier de credit n'obeit a personne — chaque banque a le
 * sien, et il change. Coder l'un ou l'autre obligerait a livrer pour ajouter un chiffre,
 * et une banque qui attend la prochaine version pour ouvrir une agence est une banque
 * arretee.
 *
 * <p>Une regle est donc une suite de <b>segments</b> : du texte fixe, le code banque, le
 * code de l'agence, la date, un compteur, une cle de controle. Elle se redige, puis
 * s'active a deux — c'est elle qui decide de l'identite des comptes ouverts demain.
 *
 * <h2>Ce que le moteur ne fait pas</h2>
 *
 * <p>Il ne remplace jamais un numero fourni. Une reprise d'existant porte les numeros de
 * l'ancien systeme : les recomposer serait perdre le lien avec les archives, les cheques
 * en circulation et la memoire des clients. {@link #orCompose} dit exactement cela — le
 * numero de l'appelant d'abord, la regle a defaut.
 *
 * <h2>Le compteur</h2>
 *
 * <p>C'est une ligne de table verrouillee, pas une sequence PostgreSQL. Une sequence ne
 * revient pas en arriere : une ouverture annulee laisserait un trou dans la serie, et un
 * trou dans une serie de numeros de compte est une question d'inspection. Le verrou
 * serialise les ouvertures d'une meme agence le temps d'une transaction — c'est le prix
 * d'une serie sans trou, et une ouverture de compte n'est pas une operation de masse.
 */
public final class Numbering {

    private Numbering() {}

    /** Ce qui se numerote. Ajouter un domaine est une livraison, pas un parametrage. */
    public enum Domain { PARTY, ACCOUNT, LOAN_APPLICATION, LOAN_CONTRACT, TERM_DEPOSIT,
                         STANDING_ORDER }

    /** Le perimetre du compteur : une serie pour la banque, ou une par agence. */
    public enum Scope { ENTITY, BRANCH }

    /** Quand la serie repart a son premier numero. */
    public enum Reset { NEVER, YEAR, MONTH }

    /** La nature d'un segment. */
    public enum SegmentKind { LITERAL, BANK_CODE, BRANCH_CODE, DATE, SEQUENCE, CHECK_DIGITS }

    /**
     * Les cles de controle connues.
     *
     * <p>{@code RIB_97} est la cle du releve d'identite bancaire : deux chiffres tels que le
     * numero entier, lu comme un nombre, soit divisible par 97. Les lettres sont transcodees
     * selon la table usuelle. {@code LUHN} est la cle modulo 10 des numeros de carte.
     */
    public enum CheckAlgorithm { RIB_97, LUHN }

    private static final int MAX_SEQUENCE_LENGTH = 18;

    /**
     * Un segment du gabarit.
     *
     * @param kind sa nature
     * @param literalValue le texte fixe, pour {@link SegmentKind#LITERAL}
     * @param length le cadrage, pour le compteur, les codes et la cle
     * @param padChar le caractere de remplissage a gauche ; {@code '0'} par defaut
     * @param datePattern le format de la date comptable, pour {@link SegmentKind#DATE}
     * @param algorithm l'algorithme, pour {@link SegmentKind#CHECK_DIGITS}
     */
    public record Segment(SegmentKind kind, String literalValue, Integer length, Character padChar,
                          String datePattern, CheckAlgorithm algorithm) {

        public Segment {
            Objects.requireNonNull(kind, "kind");
        }

        public static Segment literal(String value) {
            return new Segment(SegmentKind.LITERAL, value, null, null, null, null);
        }

        public static Segment bankCode(int length) {
            return new Segment(SegmentKind.BANK_CODE, null, length, '0', null, null);
        }

        public static Segment branchCode(int length) {
            return new Segment(SegmentKind.BRANCH_CODE, null, length, '0', null, null);
        }

        public static Segment date(String pattern) {
            return new Segment(SegmentKind.DATE, null, null, null, pattern, null);
        }

        public static Segment sequence(int length) {
            return new Segment(SegmentKind.SEQUENCE, null, length, '0', null, null);
        }

        public static Segment checkDigits(CheckAlgorithm algorithm) {
            return new Segment(SegmentKind.CHECK_DIGITS, null,
                               algorithm == CheckAlgorithm.LUHN ? 1 : 2, '0', null, algorithm);
        }

        char pad() {
            return padChar == null ? '0' : padChar;
        }
    }

    /** Une regle en base. */
    public record Rule(UUID id, UUID legalEntityId, Domain domain, String label,
                       List<Segment> segments, Scope scope, Reset reset, long sequenceStart,
                       String status, UUID createdBy, UUID approvedBy) {

        public Rule {
            segments = List.copyOf(segments == null ? List.of() : segments);
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** Une regle a rediger. */
    public record Draft(UUID legalEntityId, Domain domain, String label, List<Segment> segments,
                        Scope scope, Reset reset, long sequenceStart, UUID createdBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(createdBy, "createdBy");
            scope = scope == null ? Scope.ENTITY : scope;
            reset = reset == null ? Reset.NEVER : reset;
            segments = List.copyOf(segments == null ? List.of() : segments);
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Une regle de numerotation porte un libelle");
            }
            if (sequenceStart < 0) {
                throw new IllegalArgumentException("Le premier numero de la serie n'est pas "
                                                   + "negatif : " + sequenceStart);
            }
            validate(segments);
        }
    }

    /**
     * Ce que le moteur doit savoir pour composer : l'entite, l'agence qui ouvre — quand le
     * gabarit ou le perimetre en depend —, et la date comptable.
     */
    public record Context(UUID legalEntityId, UUID branchId, LocalDate on) {

        public Context {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(on, "on");
        }
    }

    /** Aucune regle active pour ce domaine, et aucun numero fourni. */
    public static final class NoRuleException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        NoRuleException(Domain domain) {
            super("Aucune regle de numerotation active pour " + domain + " : le numero doit etre "
                  + "fourni, ou une regle redigee et activee. C'est un parametrage manquant, pas "
                  + "une erreur de saisie.");
        }
    }

    // ------------------------------------------------------------------ validation du gabarit

    /**
     * Refuse un gabarit qui ne peut pas tenir sa promesse. Ces controles sont a la redaction :
     * un gabarit sans compteur composerait deux fois le meme numero, et on le decouvrirait au
     * deuxieme client.
     */
    static void validate(List<Segment> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Un gabarit de numerotation porte au moins un "
                                               + "segment");
        }
        long sequences = segments.stream().filter(s -> s.kind() == SegmentKind.SEQUENCE).count();
        if (sequences != 1) {
            throw new IllegalArgumentException(
                "Un gabarit porte exactement un segment de sequence — il en porte " + sequences
                + ". Sans compteur, tous les numeros composes seraient identiques ; avec deux, "
                + "aucun ne serait lisible.");
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            validateSegment(segment);
            if (segment.kind() == SegmentKind.CHECK_DIGITS && i != segments.size() - 1) {
                throw new IllegalArgumentException(
                    "La cle de controle se calcule sur ce qui la precede : elle est le dernier "
                    + "segment du gabarit, jamais au milieu.");
            }
        }
    }

    private static void validateSegment(Segment segment) {
        switch (segment.kind()) {
            case LITERAL -> {
                if (segment.literalValue() == null || segment.literalValue().isEmpty()) {
                    throw new IllegalArgumentException("Un segment fixe porte son texte");
                }
            }
            case DATE -> {
                if (segment.datePattern() == null || segment.datePattern().isBlank()) {
                    throw new IllegalArgumentException("Un segment de date porte son format");
                }
                try {
                    DateTimeFormatter.ofPattern(segment.datePattern())
                        .format(LocalDate.of(2000, 1, 1));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                        "Format de date inconnu : " + segment.datePattern() + " (" + e.getMessage()
                        + ")");
                }
            }
            case SEQUENCE -> {
                requireLength(segment, "Un compteur porte son cadrage");
                if (segment.length() > MAX_SEQUENCE_LENGTH) {
                    throw new IllegalArgumentException(
                        "Un compteur tient sur " + MAX_SEQUENCE_LENGTH + " chiffres au plus : "
                        + segment.length());
                }
            }
            case BANK_CODE, BRANCH_CODE -> requireLength(segment, "Un code porte son cadrage");
            case CHECK_DIGITS -> {
                if (segment.algorithm() == null) {
                    throw new IllegalArgumentException("Une cle de controle porte son algorithme");
                }
                requireLength(segment, "Une cle de controle porte sa longueur");
                int expected = segment.algorithm() == CheckAlgorithm.LUHN ? 1 : 2;
                if (segment.length() != expected) {
                    throw new IllegalArgumentException(
                        "La cle " + segment.algorithm() + " tient sur " + expected
                        + " caractere(s), pas " + segment.length());
                }
            }
            default -> throw new IllegalArgumentException("Segment inconnu : " + segment.kind());
        }
    }

    private static void requireLength(Segment segment, String message) {
        if (segment.length() == null || segment.length() < 1) {
            throw new IllegalArgumentException(message + " : " + segment.kind());
        }
    }

    // ------------------------------------------------------------------ composition

    /**
     * Le numero de l'appelant s'il en fournit un, celui de la regle sinon.
     *
     * <p>C'est la seule porte que les services metier franchissent. L'ordre n'est pas
     * negociable : une reprise d'existant depend de la premiere branche.
     */
    public static String orCompose(Connection c, String supplied, Domain domain, Context context) {
        if (supplied != null && !supplied.isBlank()) {
            return supplied.trim();
        }
        return next(c, domain, context);
    }

    /**
     * Compose le prochain numero du domaine et consomme le compteur.
     *
     * <p>Le compteur est pris dans la transaction de l'appelant : si l'ouverture echoue, le
     * numero n'a pas ete consomme.
     */
    public static String next(Connection c, Domain domain, Context context) {
        Rule rule = active(c, context.legalEntityId(), domain).orElseThrow(
            () -> new NoRuleException(domain));
        String branchCode = rule.scope() == Scope.BRANCH
                            || rule.segments().stream()
                                   .anyMatch(s -> s.kind() == SegmentKind.BRANCH_CODE)
            ? branchCode(c, context, domain) : null;
        String scopeKey = scopeKey(rule, branchCode, context.on());
        long value = takeNext(c, rule, scopeKey, context.legalEntityId());
        String bankCode = rule.segments().stream()
            .anyMatch(s -> s.kind() == SegmentKind.BANK_CODE)
            ? bankCode(c, context.legalEntityId()) : null;
        String composed = compose(rule, value, bankCode, branchCode, context.on());
        record(c, rule, context, composed, scopeKey, value);
        return composed;
    }

    /**
     * Ce que la regle composerait maintenant, sans consommer le compteur.
     *
     * <p>Un ecran de parametrage qui montrerait un gabarit sans montrer le numero qu'il produit
     * demanderait a son lecteur de faire le calcul de tete.
     */
    public static String preview(Connection c, UUID legalEntityId, UUID ruleId, UUID branchId,
                                 LocalDate on) {
        Rule rule = require(c, legalEntityId, ruleId);
        String branchCode = rule.segments().stream()
                                .anyMatch(s -> s.kind() == SegmentKind.BRANCH_CODE)
                            || rule.scope() == Scope.BRANCH
            ? branchId == null ? "?" : Branches.require(c, branchId).code() : null;
        String scopeKey = scopeKey(rule, branchCode, on);
        String bankCode = rule.segments().stream()
            .anyMatch(s -> s.kind() == SegmentKind.BANK_CODE)
            ? Optional.ofNullable(bankCodeOrNull(c, legalEntityId)).orElse("?") : null;
        return compose(rule, peek(c, rule, scopeKey), bankCode, branchCode, on);
    }

    /** La composition pure : c'est ici que le gabarit devient un numero. */
    static String compose(Rule rule, long sequence, String bankCode, String branchCode,
                          LocalDate on) {
        StringBuilder out = new StringBuilder();
        for (Segment segment : rule.segments()) {
            switch (segment.kind()) {
                case LITERAL -> out.append(segment.literalValue());
                case BANK_CODE -> out.append(fit(bankCode, segment, "code banque"));
                case BRANCH_CODE -> out.append(fit(branchCode, segment, "code agence"));
                case DATE -> out.append(DateTimeFormatter.ofPattern(segment.datePattern())
                                            .format(on));
                case SEQUENCE -> out.append(fit(Long.toString(sequence), segment, "compteur"));
                case CHECK_DIGITS -> out.append(
                    fit(checkDigits(segment.algorithm(), out.toString()), segment, "cle"));
                default -> throw new IllegalStateException("Segment inconnu : " + segment.kind());
            }
        }
        return out.toString();
    }

    /**
     * Cadre a gauche, ou refuse.
     *
     * <p>Tronquer serait pire que refuser : deux agences dont les codes ne different qu'au-dela
     * du cadrage donneraient le meme numero de compte, et la collision n'apparaitrait qu'a
     * l'insertion, des mois plus tard.
     */
    private static String fit(String value, Segment segment, String what) {
        if (value == null) {
            throw new IllegalStateException(
                "Le gabarit demande le " + what + ", qui n'est pas connu ici.");
        }
        int length = segment.length() == null ? value.length() : segment.length();
        if (value.length() > length) {
            throw new IllegalArgumentException(
                "Le " + what + " « " + value + " » ne tient pas sur " + length + " caractere(s) : "
                + "le gabarit tronquerait, et deux numeros differents deviendraient le meme.");
        }
        return String.valueOf(segment.pad()).repeat(length - value.length()) + value;
    }

    // ------------------------------------------------------------------ cles de controle

    /** Table de transcodage des lettres du RIB : A,J -> 1, B,K,S -> 2, etc. */
    private static int ribDigit(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        char upper = Character.toUpperCase(ch);
        if (upper < 'A' || upper > 'Z') {
            return -1;
        }
        return switch (upper) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'O', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Q', 'Y' -> 8;
            default -> 9;
        };
    }

    /**
     * La cle de controle de {@code prefix}.
     *
     * <p>Pour {@code RIB_97}, la cle est le complement a 97 du reste de la division du numero
     * suivi de deux zeros : le numero cle comprise est alors divisible par 97. Le calcul porte
     * sur la chaine entiere plutot que sur la formule a trois poids du RIB francais, qui suppose
     * des longueurs fixes — la BCEAO n'a pas les memes.
     */
    public static String checkDigits(CheckAlgorithm algorithm, String prefix) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < prefix.length(); i++) {
            int digit = ribDigit(prefix.charAt(i));
            if (digit >= 0) {
                digits.append(digit);
            }
        }
        if (digits.isEmpty()) {
            throw new IllegalArgumentException(
                "La cle de controle se calcule sur des chiffres : le gabarit n'en produit aucun "
                + "avant elle.");
        }
        return switch (algorithm) {
            case RIB_97 -> {
                BigInteger n = new BigInteger(digits.toString());
                int rest = n.multiply(BigInteger.valueOf(100))
                            .mod(BigInteger.valueOf(97)).intValueExact();
                yield String.format("%02d", 97 - rest);
            }
            case LUHN -> {
                int sum = 0;
                boolean doubling = true;
                for (int i = digits.length() - 1; i >= 0; i--) {
                    int digit = digits.charAt(i) - '0';
                    if (doubling) {
                        digit *= 2;
                        if (digit > 9) {
                            digit -= 9;
                        }
                    }
                    doubling = !doubling;
                    sum += digit;
                }
                yield Integer.toString((10 - sum % 10) % 10);
            }
        };
    }

    // ------------------------------------------------------------------ compteur

    private static String scopeKey(Rule rule, String branchCode, LocalDate on) {
        String branch = rule.scope() == Scope.BRANCH
            ? Objects.requireNonNull(branchCode, "branchCode") : "";
        String period = switch (rule.reset()) {
            case NEVER -> "";
            case YEAR -> String.format("%04d", on.getYear());
            case MONTH -> String.format("%04d-%02d", on.getYear(), on.getMonthValue());
        };
        return branch + "|" + period;
    }

    private static long takeNext(Connection c, Rule rule, String scopeKey, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO numbering_sequence(rule_id, scope_key, legal_entity_id, next_value)"
            + " VALUES (?,?,?,?) ON CONFLICT (rule_id, scope_key) DO NOTHING")) {
            ps.setObject(1, rule.id());
            ps.setString(2, scopeKey);
            ps.setObject(3, legalEntityId);
            ps.setLong(4, rule.sequenceStart());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Amorcage du compteur de " + rule.domain(), e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT next_value FROM numbering_sequence WHERE rule_id = ? AND scope_key = ?"
            + " FOR UPDATE")) {
            ps.setObject(1, rule.id());
            ps.setString(2, scopeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Compteur de " + rule.domain()
                                                   + " introuvable");
                }
                long value = rs.getLong(1);
                bump(c, rule, scopeKey, value + 1);
                return value;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du compteur de " + rule.domain(), e);
        }
    }

    private static void bump(Connection c, Rule rule, String scopeKey, long next) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE numbering_sequence SET next_value = ?, updated_at = now()"
            + " WHERE rule_id = ? AND scope_key = ?")) {
            ps.setLong(1, next);
            ps.setObject(2, rule.id());
            ps.setString(3, scopeKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Avancee du compteur de " + rule.domain(), e);
        }
    }

    private static long peek(Connection c, Rule rule, String scopeKey) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT next_value FROM numbering_sequence WHERE rule_id = ? AND scope_key = ?")) {
            ps.setObject(1, rule.id());
            ps.setString(2, scopeKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : rule.sequenceStart();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du compteur de " + rule.domain(), e);
        }
    }

    private static void record(Connection c, Rule rule, Context context, String value,
                               String scopeKey, long sequence) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO numbering_issue(id, legal_entity_id, rule_id, domain, value, scope_key,"
            + " sequence_value, issued_on) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, context.legalEntityId());
            ps.setObject(3, rule.id());
            ps.setString(4, rule.domain().name());
            ps.setString(5, value);
            ps.setString(6, scopeKey);
            ps.setLong(7, sequence);
            ps.setObject(8, context.on());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Le numero " + value + " a deja ete compose pour " + rule.domain()
                + " : le gabarit ne distingue pas deux appels. " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ entite et agence

    private static String bankCode(Connection c, UUID legalEntityId) {
        String code = bankCodeOrNull(c, legalEntityId);
        if (code == null || code.isBlank()) {
            throw new IllegalStateException(
                "Le gabarit porte le code banque, que l'etablissement ne declare pas. Il "
                + "s'attribue par la banque centrale et se pose sur l'etablissement avant "
                + "d'ouvrir le premier compte.");
        }
        return code;
    }

    private static String bankCodeOrNull(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT bank_code FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du code banque", e);
        }
    }

    private static String branchCode(Connection c, Context context, Domain domain) {
        if (context.branchId() == null) {
            throw new IllegalStateException(
                "La numerotation de " + domain + " depend de l'agence, que l'appel ne porte pas.");
        }
        return Branches.require(c, context.branchId()).code();
    }

    // ------------------------------------------------------------------ propositions

    /**
     * Le gabarit que le socle propose pour ce domaine.
     *
     * <p>Une proposition, pas un defaut impose : rien n'est seme a la creation d'un
     * etablissement, et aucune regle ne numerote tant qu'une personne ne l'a pas activee avec une
     * autre. La banque choisit son plan de numerotation — c'est elle qui vivra avec pendant vingt
     * ans, et c'est elle que la banque centrale interrogera.
     *
     * <p>Pour le compte, c'est le releve d'identite bancaire de la zone UEMOA : code banque sur
     * cinq, code guichet sur cinq, numero sur douze, cle de controle sur deux.
     */
    public static Draft proposal(UUID legalEntityId, Domain domain, UUID author) {
        return switch (domain) {
            case PARTY -> new Draft(legalEntityId, domain, "Reference client",
                                    List.of(Segment.literal("CLI-"), Segment.sequence(6)),
                                    Scope.ENTITY, Reset.NEVER, 1, author);
            case ACCOUNT -> new Draft(legalEntityId, domain, "Numero de compte (RIB)",
                                      List.of(Segment.bankCode(5), Segment.branchCode(5),
                                              Segment.sequence(12),
                                              Segment.checkDigits(CheckAlgorithm.RIB_97)),
                                      Scope.BRANCH, Reset.NEVER, 1, author);
            case LOAN_APPLICATION -> new Draft(
                legalEntityId, domain, "Reference de demande de credit",
                List.of(Segment.literal("DC-"), Segment.date("yyyy"), Segment.literal("-"),
                        Segment.sequence(4)),
                Scope.ENTITY, Reset.YEAR, 1, author);
            case LOAN_CONTRACT -> new Draft(
                legalEntityId, domain, "Reference de contrat de credit",
                List.of(Segment.literal("CRD-"), Segment.date("yyyy"), Segment.literal("-"),
                        Segment.sequence(5)),
                Scope.ENTITY, Reset.YEAR, 1, author);
            case TERM_DEPOSIT -> new Draft(
                legalEntityId, domain, "Reference de depot a terme",
                List.of(Segment.literal("DAT-"), Segment.date("yyyy"), Segment.literal("-"),
                        Segment.sequence(5)),
                Scope.ENTITY, Reset.YEAR, 1, author);
            case STANDING_ORDER -> new Draft(
                legalEntityId, domain, "Reference d'ordre permanent",
                List.of(Segment.literal("OP-"), Segment.sequence(7)),
                Scope.ENTITY, Reset.NEVER, 1, author);
        };
    }

    // ------------------------------------------------------------------ parametrage

    private static final String SELECT =
        "SELECT id, legal_entity_id, domain, label, sequence_scope, sequence_reset,"
        + " sequence_start, status, created_by, approved_by FROM numbering_rule";

    /** Redige une regle. Elle ne numerote rien tant qu'elle n'est pas activee. */
    public static UUID draft(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO numbering_rule(id, legal_entity_id, domain, label, sequence_scope,"
            + " sequence_reset, sequence_start, status, created_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.domain().name());
            ps.setString(4, draft.label());
            ps.setString(5, draft.scope().name());
            ps.setString(6, draft.reset().name());
            ps.setLong(7, draft.sequenceStart());
            ps.setString(8, "DRAFT");
            ps.setObject(9, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Redaction de la regle de " + draft.domain(), e);
        }
        insertSegments(c, id, draft.segments());
        return id;
    }

    private static void insertSegments(Connection c, UUID ruleId, List<Segment> segments) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO numbering_segment(rule_id, position, kind, literal_value, length,"
            + " pad_char, date_pattern, check_algorithm) VALUES (?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                ps.setObject(1, ruleId);
                ps.setInt(2, i);
                ps.setString(3, segment.kind().name());
                ps.setString(4, segment.literalValue());
                if (segment.length() == null) {
                    ps.setNull(5, Types.INTEGER);
                } else {
                    ps.setInt(5, segment.length());
                }
                ps.setString(6, String.valueOf(segment.pad()));
                ps.setString(7, segment.datePattern());
                ps.setString(8, segment.algorithm() == null ? null : segment.algorithm().name());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Segments de la regle " + ruleId, e);
        }
    }

    /**
     * Active la regle, retire celle qui numerotait jusqu'ici.
     *
     * <p>L'ancienne n'est pas supprimee : les numeros qu'elle a composes restent explicables,
     * et {@code numbering_issue} pointe encore sur elle.
     */
    public static void activate(Connection c, UUID legalEntityId, UUID ruleId, UUID approver) {
        Rule rule = require(c, legalEntityId, ruleId);
        if (rule.active()) {
            return;
        }
        if (!"DRAFT".equals(rule.status())) {
            throw new IllegalStateException(
                "La regle " + rule.label() + " est " + rule.status() + " : elle ne s'active plus.");
        }
        if (approver == null || approver.equals(rule.createdBy())) {
            throw new IllegalStateException(
                "Une regle de numerotation s'active a deux : elle decide de l'identite des "
                + "comptes ouverts demain.");
        }
        withdrawActive(c, legalEntityId, rule.domain());
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE numbering_rule SET status = 'ACTIVE', approved_by = ?, approved_at = now()"
            + " WHERE id = ?")) {
            ps.setObject(1, approver);
            ps.setObject(2, ruleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation de la regle " + ruleId, e);
        }
    }

    private static void withdrawActive(Connection c, UUID legalEntityId, Domain domain) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE numbering_rule SET status = 'WITHDRAWN' WHERE legal_entity_id = ?"
            + " AND domain = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, domain.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Retrait de la regle active de " + domain, e);
        }
    }

    /** La regle qui numerote ce domaine aujourd'hui. */
    public static Optional<Rule> active(Connection c, UUID legalEntityId, Domain domain) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND domain = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, domain.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(c, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la regle de " + domain, e);
        }
    }

    /** Une regle par son identifiant, ou un refus qui la nomme. */
    public static Rule require(Connection c, UUID legalEntityId, UUID ruleId) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE id = ? AND legal_entity_id = ?")) {
            ps.setObject(1, ruleId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Regle de numerotation inconnue : "
                                                       + ruleId);
                }
                return read(c, rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la regle " + ruleId, e);
        }
    }

    /** Les regles de l'entite, du domaine le plus recent au plus ancien. */
    public static List<Rule> list(Connection c, UUID legalEntityId, Domain domain) {
        String where = " WHERE legal_entity_id = ?" + (domain == null ? "" : " AND domain = ?")
                       + " ORDER BY domain, status, created_at DESC";
        try (PreparedStatement ps = c.prepareStatement(SELECT + where)) {
            ps.setObject(1, legalEntityId);
            if (domain != null) {
                ps.setString(2, domain.name());
            }
            List<Rule> rules = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(read(c, rs));
                }
            }
            return List.copyOf(rules);
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des regles de numerotation", e);
        }
    }

    private static Rule read(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        return new Rule(id, rs.getObject(2, UUID.class), Domain.valueOf(rs.getString(3)),
                        rs.getString(4), segments(c, id), Scope.valueOf(rs.getString(5)),
                        Reset.valueOf(rs.getString(6)), rs.getLong(7), rs.getString(8),
                        rs.getObject(9, UUID.class), rs.getObject(10, UUID.class));
    }

    private static List<Segment> segments(Connection c, UUID ruleId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind, literal_value, length, pad_char, date_pattern, check_algorithm"
            + " FROM numbering_segment WHERE rule_id = ? ORDER BY position")) {
            ps.setObject(1, ruleId);
            List<Segment> segments = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SegmentKind kind = SegmentKind.valueOf(rs.getString(1));
                    String literal = rs.getString(2);
                    int rawLength = rs.getInt(3);
                    Integer length = rs.wasNull() ? null : rawLength;
                    String pad = rs.getString(4);
                    String pattern = rs.getString(5);
                    String algorithm = rs.getString(6);
                    segments.add(new Segment(kind, literal, length,
                                             pad == null || pad.isEmpty() ? null : pad.charAt(0),
                                             pattern,
                                             algorithm == null ? null
                                                 : CheckAlgorithm.valueOf(algorithm)));
                }
            }
            return List.copyOf(segments);
        } catch (SQLException e) {
            throw new LedgerStoreException("Segments de la regle " + ruleId, e);
        }
    }
}
