package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Positions de change.
 *
 * <p>Une position apparie, pour une devise, deux comptes : le <b>compte de position</b>, tenu
 * dans la devise, qui mesure l'exposition ; et son <b>compte de contre-valeur</b>, tenu dans la
 * devise de l'entite, qui porte ce que cette exposition a coute. L'arrete revalorise le second au
 * cours du jour et porte l'ecart au resultat de change.
 *
 * <h2>Le sens des deux comptes est impose</h2>
 *
 * <p>Position creditrice, contre-valeur debitrice : la banque qui achete 1 000 EUR contre
 * 655 957 XOF credite sa position de 1 000 EUR et debite sa contre-valeur de 655 957 XOF. Le
 * couple inverse donnerait, a chaque arrete, un gain la ou il y a une perte — et l'ecriture
 * serait equilibree, la comptabilite juste, le resultat de change faux. Le sens se declare donc
 * une fois, ici, et le systeme le verifie.
 */
public final class FxPositions {

    private FxPositions() {}

    public record Position(UUID id, UUID legalEntityId, String currency, UUID positionAccountId,
                           UUID counterValueAccountId, UUID gainAccountId, UUID lossAccountId,
                           int toleranceBps, UUID createdBy, UUID approvedBy) {}

    public record Draft(UUID legalEntityId, String currency, UUID positionAccountId,
                        UUID counterValueAccountId, UUID gainAccountId, UUID lossAccountId,
                        int toleranceBps, UUID createdBy, UUID approvedBy) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(positionAccountId, "positionAccountId");
            Objects.requireNonNull(counterValueAccountId, "counterValueAccountId");
            Objects.requireNonNull(gainAccountId, "gainAccountId");
            Objects.requireNonNull(lossAccountId, "lossAccountId");
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("Devise de la position obligatoire");
            }
            if (toleranceBps < 0 || toleranceBps > 10_000) {
                throw new IllegalArgumentException(
                    "La marge toleree est de 0 a 10 000 points de base : " + toleranceBps);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException(
                    "Une position se declare a deux : le demandeur ne peut pas etre le valideur");
            }
        }
    }

    /** L'exposition d'une position a une date : ce qu'elle vaut, ce qu'elle a coute, l'ecart. */
    public record Exposure(Position position, Money balance, Money carriedValue, BigDecimal rate,
                           LocalDate rateQuotedOn, Money revaluedValue, Money unrealised) {}

    public static Position declare(Connection c, Draft draft) {
        CurrencyRef functional = Entities.functionalCurrency(c, draft.legalEntityId());
        if (functional.code().equals(draft.currency())) {
            throw new IllegalArgumentException("La devise de tenue " + functional.code()
                + " n'est pas une position de change : elle est l'etalon");
        }
        Account position = require(c, draft.legalEntityId(), draft.positionAccountId(), "position");
        Account counterValue = require(c, draft.legalEntityId(), draft.counterValueAccountId(),
                                       "contre-valeur");
        if (position.kind() != AccountKind.POSITION || counterValue.kind() != AccountKind.POSITION) {
            throw new IllegalArgumentException("Les deux comptes d'une position de change sont de "
                + "nature POSITION : ils ne se confondent avec aucun compte general");
        }
        if (!position.currency().code().equals(draft.currency())) {
            throw new IllegalArgumentException("Le compte de position " + position.code()
                + " est tenu en " + position.currency().code() + ", la position en "
                + draft.currency());
        }
        if (!counterValue.currency().equals(functional)) {
            throw new IllegalArgumentException("Le compte de contre-valeur " + counterValue.code()
                + " est tenu en " + counterValue.currency().code() + ", la devise de l'entite est "
                + functional.code());
        }
        if (position.normalBalance() != NormalBalance.CREDIT
            || counterValue.normalBalance() != NormalBalance.DEBIT) {
            throw new IllegalArgumentException("Position creditrice, contre-valeur debitrice : le "
                + "couple inverse rendrait un gain la ou il y a une perte, sans qu'aucune ecriture "
                + "ne soit desequilibree");
        }
        for (UUID resultAccount : List.of(draft.gainAccountId(), draft.lossAccountId())) {
            Account account = require(c, draft.legalEntityId(), resultAccount, "resultat de change");
            if (account.nature() != AccountNature.PROFIT_AND_LOSS) {
                throw new IllegalArgumentException("Le compte " + account.code() + " n'est pas un "
                    + "compte de resultat : l'ecart de change est un produit ou une charge");
            }
            if (!account.currency().equals(functional)) {
                throw new IllegalArgumentException("Le resultat de change se tient en "
                    + functional.code() + ", pas en " + account.currency().code());
            }
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO fx_position(id, legal_entity_id, currency, position_account_id,"
            + " counter_value_account_id, gain_account_id, loss_account_id, tolerance_bps,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.currency());
            ps.setObject(4, draft.positionAccountId());
            ps.setObject(5, draft.counterValueAccountId());
            ps.setObject(6, draft.gainAccountId());
            ps.setObject(7, draft.lossAccountId());
            ps.setInt(8, draft.toleranceBps());
            ps.setObject(9, draft.createdBy());
            ps.setObject(10, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException(
                    "Une position de change est deja declaree en " + draft.currency(), e);
            }
            throw new LedgerStoreException("Declaration de la position de change", e);
        }
        return find(c, draft.legalEntityId(), draft.currency()).orElseThrow();
    }

    private static Account require(Connection c, UUID legalEntityId, UUID accountId, String role) {
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null || !account.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException(
                "Compte de " + role + " inconnu dans l'entite : " + accountId);
        }
        return account;
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, currency, position_account_id, counter_value_account_id,"
        + " gain_account_id, loss_account_id, tolerance_bps, created_by, approved_by"
        + " FROM fx_position";

    public static Optional<Position> find(Connection c, UUID legalEntityId, String currency) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND currency = ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la position de change", e);
        }
    }

    public static List<Position> all(Connection c, UUID legalEntityId) {
        List<Position> positions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY currency")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    positions.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des positions de change", e);
        }
        return positions;
    }

    private static Position read(ResultSet rs) throws SQLException {
        return new Position(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                            rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getInt(8),
                            rs.getObject(9, UUID.class), rs.getObject(10, UUID.class));
    }

    /**
     * L'exposition d'une position a une date : le solde en devise, la contre-valeur portee, le
     * cours du jour, la contre-valeur revalorisee et l'ecart latent. Sans cours, l'ecart n'est
     * pas calcule — il n'est pas repute nul.
     *
     * <p>La lecture se contente du dernier cours connu, et dit lequel ({@code rateQuotedOn}) : une
     * consultation de milieu de journee vaut mieux qu'un refus. La revalorisation, elle, exige le
     * cours du jour : elle comptabilise.
     */
    public static Exposure exposure(Connection c, Position position, LocalDate on) {
        Money balance = Balances.current(c, position.positionAccountId());
        Money carried = Balances.current(c, position.counterValueAccountId());
        Optional<FxRates.Rate> rate = FxRates.latestOn(c, position.legalEntityId(),
                                                       position.currency(), on);
        if (rate.isEmpty()) {
            return new Exposure(position, balance, carried, null, null, null, null);
        }
        Money revalued = revaluedValue(balance, rate.get().rate(), carried.currency());
        return new Exposure(position, balance, carried, rate.get().rate(), rate.get().quotedOn(),
                            revalued, revalued.minus(carried));
    }

    /**
     * La contre-valeur d'un solde au cours donne, arrondie a la devise de tenue.
     *
     * <p>L'arrondi est ici, une fois, sur le total : arrondir ligne a ligne ferait diverger la
     * contre-valeur revalorisee de la somme de ses parts.
     */
    public static Money revaluedValue(Money balance, BigDecimal rate, CurrencyRef functional) {
        return Money.of(balance.amount(), functional).times(rate).roundToCurrency();
    }
}
