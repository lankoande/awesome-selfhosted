package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.ValidatedLine;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cours de reference, et controle du cours applique.
 *
 * <h2>Pourquoi ce controle n'est pas dans le ledger</h2>
 *
 * <p>Le ledger verifie que les contre-valeurs d'une ecriture s'equilibrent. Ce controle attrape
 * un cours incoherent <b>entre les lignes</b> d'une meme ecriture ; il n'attrape pas un cours
 * faux applique uniformement, puisque les contre-valeurs se compensent alors deux a deux, quel
 * que soit le cours. Le seul controle qui le voie est la confrontation au cours de reference :
 * il est ici, dans le referentiel, et le service d'imputation l'appelle.
 *
 * <h2>Ce qu'un cours absent vaut</h2>
 *
 * <p>Un refus. Se rabattre sur le cours de la ligne reviendrait a se croire sur parole ; se
 * rabattre sur le dernier cours connu, sans borne, reviendrait a revaloriser un portefeuille au
 * cours d'un mois passe. Le cours du jour manque : le referentiel est en retard, et c'est cela
 * qu'il faut voir.
 */
public final class FxRates {

    /** Un cours vaut jusqu'au suivant : sur un pont de plusieurs jours feries, pas au-dela. */
    private static final int MAX_STALE_DAYS = 7;

    private FxRates() {}

    /**
     * @param rate unites de la devise de tenue pour une unite de {@code currency}
     */
    public record Rate(UUID id, UUID legalEntityId, String currency, LocalDate quotedOn,
                       BigDecimal rate, String source, UUID createdBy, UUID approvedBy) {}

    public record Quote(UUID legalEntityId, String currency, LocalDate quotedOn, BigDecimal rate,
                        String source, UUID createdBy, UUID approvedBy) {
        public Quote {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(quotedOn, "quotedOn");
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("Devise cotee obligatoire");
            }
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("Cours non strictement positif : " + rate);
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                    "La source du cours est obligatoire : un cours sans origine ne se justifie pas");
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException(
                    "Un cours se cote a deux : le coteur ne peut pas etre le valideur");
            }
        }
    }

    public static Rate quote(Connection c, Quote quote) {
        CurrencyRef functional = Entities.functionalCurrency(c, quote.legalEntityId());
        if (functional.code().equals(quote.currency())) {
            throw new IllegalArgumentException(
                "La devise de tenue " + functional.code() + " ne se cote pas contre elle-meme");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO fx_rate(id, legal_entity_id, currency, quoted_on, rate, source,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, quote.legalEntityId());
            ps.setString(3, quote.currency());
            ps.setObject(4, quote.quotedOn());
            ps.setBigDecimal(5, quote.rate());
            ps.setString(6, quote.source().trim());
            ps.setObject(7, quote.createdBy());
            ps.setObject(8, quote.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Le cours du " + quote.quotedOn() + " en "
                    + quote.currency() + " est deja cote : un cours cote ne se reecrit pas, il se "
                    + "corrige par une cotation du jour suivant", e);
            }
            if ("23503".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Devise inconnue : " + quote.currency(), e);
            }
            throw new LedgerStoreException("Cotation du cours", e);
        }
        return require(c, id);
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, currency, quoted_on, rate, source, created_by, approved_by"
        + " FROM fx_rate";

    static Rate require(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Cours introuvable : " + id);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du cours", e);
        }
    }

    /** Le cours cote exactement a cette date, s'il y en a un. */
    public static Optional<Rate> quotedOn(Connection c, UUID legalEntityId, String currency,
                                          LocalDate on) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND currency = ? AND quoted_on = ?",
                   legalEntityId, currency, on);
    }

    /** Le dernier cours cote a cette date ou avant : un cours vaut jusqu'au suivant. */
    public static Optional<Rate> latestOn(Connection c, UUID legalEntityId, String currency,
                                          LocalDate on) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND currency = ? AND quoted_on <= ?"
                   + " ORDER BY quoted_on DESC LIMIT 1", legalEntityId, currency, on);
    }

    /** Les cours d'une entite, du plus recent au plus ancien ; d'une devise si elle est donnee. */
    public static List<Rate> rates(Connection c, UUID legalEntityId, String currency, int limit) {
        List<Rate> rates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND (?::text IS NULL OR currency = ?)"
            + " ORDER BY quoted_on DESC, currency LIMIT ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, currency);
            ps.setString(3, currency);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rates.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des cours", e);
        }
        return rates;
    }

    private static Optional<Rate> one(Connection c, String sql, UUID legalEntityId, String currency,
                                      LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, currency);
            ps.setObject(3, on);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du cours", e);
        }
    }

    private static Rate read(ResultSet rs) throws SQLException {
        return new Rate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getString(6),
                        rs.getObject(7, UUID.class), rs.getObject(8, UUID.class));
    }

    // ------------------------------------------------------------------ controle

    /**
     * Confronte les cours appliques par une ecriture aux cours de reference.
     *
     * <p>Chaque devise autre que celle de tenue doit avoir sa position declaree — une devise
     * traitee sans position est une exposition que la banque ne mesure pas — et son cours de
     * reference a la date comptable ; l'ecart tolere est la marge declaree avec la position.
     */
    public static void requireAppliedRates(Connection c, UUID legalEntityId, LocalDate bookingDate,
                                           List<ValidatedLine> lines, CurrencyRef functional) {
        // Tous les cours distincts, pas seulement le premier de chaque devise : deux lignes
        // d'une meme devise peuvent porter deux cours, et leurs contre-valeurs se compenser.
        Map<String, java.util.Set<BigDecimal>> applied = new LinkedHashMap<>();
        for (ValidatedLine line : lines) {
            String currency = line.account().currency().code();
            if (currency.equals(functional.code()) || line.line().fxRate() == null) {
                continue;
            }
            applied.computeIfAbsent(currency, k -> new java.util.TreeSet<>())
                .add(line.line().fxRate().stripTrailingZeros());
        }
        for (Map.Entry<String, java.util.Set<BigDecimal>> entry : applied.entrySet()) {
            for (BigDecimal rate : entry.getValue()) {
                check(c, legalEntityId, bookingDate, entry.getKey(), rate, functional);
            }
        }
    }

    private static void check(Connection c, UUID legalEntityId, LocalDate bookingDate,
                              String currency, BigDecimal appliedRate, CurrencyRef functional) {
        FxPositions.Position position = FxPositions.find(c, legalEntityId, currency)
            .orElseThrow(() -> new InvalidPostingException(
                "Aucune position de change declaree en " + currency + " : une devise traitee sans "
                + "position est une exposition que la banque ne mesure pas, et qu'aucun arrete ne "
                + "revalorise."));
        Rate reference = latestOn(c, legalEntityId, currency, bookingDate)
            .filter(rate -> !rate.quotedOn().isBefore(bookingDate.minusDays(MAX_STALE_DAYS)))
            .orElseThrow(() -> new InvalidPostingException(
                "Aucun cours de reference en " + currency + " au " + bookingDate
                + " (ni dans les " + MAX_STALE_DAYS + " jours precedents) : un cours applique sans "
                + "reference ne se controle pas, et le ledger ne voit pas un cours faux applique "
                + "uniformement — ses contre-valeurs se compensent deux a deux."));
        BigDecimal gap = appliedRate.subtract(reference.rate()).abs();
        BigDecimal tolerated = reference.rate()
            .multiply(BigDecimal.valueOf(position.toleranceBps()))
            .divide(BigDecimal.valueOf(10_000), MathContext.DECIMAL64);
        if (gap.compareTo(tolerated) > 0) {
            throw new InvalidPostingException(
                "Cours applique " + appliedRate.stripTrailingZeros().toPlainString() + " en "
                + currency + " contre " + functional.code() + ", cours de reference du "
                + reference.quotedOn() + " " + reference.rate().stripTrailingZeros().toPlainString()
                + " : l'ecart depasse la marge de " + position.toleranceBps()
                + " points de base declaree avec la position.");
        }
    }
}
