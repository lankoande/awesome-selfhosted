package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.store.StatementLayouts.Kind;
import io.corebanking.ledger.store.StatementLayouts.Layout;
import io.corebanking.ledger.store.StatementLayouts.Line;
import io.corebanking.ledger.store.StatementLayouts.LineKind;
import io.corebanking.ledger.store.StatementLayouts.Rule;
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
 * Production des etats financiers : la maquette active appliquee au journal, en devise de tenue
 * de compte — la contre-valeur de chaque ligne —, a une date.
 *
 * <p>Le bilan et le hors bilan presentent les soldes a la date ; le compte de resultat, les
 * mouvements d'une plage — l'exercice en cours, par defaut —, hors ecritures de cloture, qui
 * soldent les comptes sans etre de l'activite. Chaque compte a solde non nul est
 * affecte a une rubrique par la premiere regle qui le reconnait ; un compte qu'aucune regle ne
 * recoit est une anomalie nommee, jamais un montant perdu en silence. Le bilan presente le
 * resultat de l'exercice en cours dans sa rubrique de resultat, calcule par le socle ; un
 * resultat anterieur non clos est une anomalie. Un etat qui ne s'equilibre pas le dit.
 */
public final class Statements {

    private Statements() {}

    public record LineAmount(int ordinal, String code, String label, int level, LineKind kind,
                             Direction side, Money amount) {}

    /**
     * @param from       premier jour des mouvements presentes ; nul pour un etat de soldes
     * @param to         date de l'etat
     * @param net        credit moins debit des rubriques de detail : le resultat d'un compte de
     *                   resultat, et zero pour un bilan ou un hors bilan qui se tiennent
     * @param consistent aucune anomalie : tout compte affecte, l'etat equilibre s'il doit l'etre
     */
    public record Statement(Kind kind, UUID layoutId, String layoutCode, String layoutLabel,
                            UUID legalEntityId, String currency, LocalDate from, LocalDate to,
                            List<LineAmount> lines, Money totalDebit, Money totalCredit, Money net,
                            boolean consistent, List<String> anomalies) {}

    public static Statement balanceSheet(Connection c, UUID legalEntityId, LocalDate asOf) {
        return produce(c, legalEntityId, Kind.BALANCE_SHEET, null,
                       Objects.requireNonNull(asOf, "asOf"));
    }

    public static Statement offBalanceSheet(Connection c, UUID legalEntityId, LocalDate asOf) {
        return produce(c, legalEntityId, Kind.OFF_BALANCE_SHEET, null,
                       Objects.requireNonNull(asOf, "asOf"));
    }

    public static Statement incomeStatement(Connection c, UUID legalEntityId, LocalDate from,
                                            LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Plage de dates inversee : du " + from + " au " + to);
        }
        return produce(c, legalEntityId, Kind.INCOME_STATEMENT, from, to);
    }

    // ------------------------------------------------------------------ production

    /** Un compte et son solde en contre-valeur, positif au debit. */
    private record AccountAmount(UUID id, String code, AccountKind kind, Money debitSigned) {}

    private static Statement produce(Connection c, UUID legalEntityId, Kind kind, LocalDate from,
                                     LocalDate to) {
        Layout layout = StatementLayouts.resolveAt(c, legalEntityId, kind, to);
        CurrencyRef functional = Entities.functionalCurrency(c, legalEntityId);
        Money zero = Money.zero(functional);
        List<String> anomalies = new ArrayList<>();
        Map<String, Money> amounts = new LinkedHashMap<>();
        for (Line line : layout.lines()) {
            amounts.put(line.code(), zero);
        }

        for (AccountAmount account : accountAmounts(c, legalEntityId, kind.nature(), from, to,
                                                   kind == Kind.INCOME_STATEMENT, functional)) {
            Direction side = account.debitSigned().isPositive() ? Direction.DEBIT : Direction.CREDIT;
            Optional<Rule> rule = layout.rules().stream()
                .filter(r -> r.matches(account.code(), account.kind(), side)).findFirst();
            if (rule.isEmpty()) {
                anomalies.add("Compte " + account.code() + " : solde "
                              + account.debitSigned().abs().roundToCurrency()
                              + (side == Direction.DEBIT ? " debiteur" : " crediteur")
                              + " qu'aucune regle n'affecte a une rubrique");
                continue;
            }
            Line line = layout.line(rule.get().lineCode()).orElseThrow();
            Money oriented = line.side() == Direction.DEBIT ? account.debitSigned()
                                                            : account.debitSigned().negate();
            amounts.merge(line.code(), oriented, Money::plus);
        }

        if (kind == Kind.BALANCE_SHEET) {
            presentResult(c, legalEntityId, layout, to, functional, amounts, anomalies);
        }

        for (Line line : layout.lines()) {
            if (line.kind() == LineKind.TOTAL) {
                Money total = zero;
                for (String code : line.plus()) {
                    total = total.plus(amounts.get(code));
                }
                for (String code : line.minus()) {
                    total = total.minus(amounts.get(code));
                }
                amounts.put(line.code(), total);
            }
        }

        Money totalDebit = zero;
        Money totalCredit = zero;
        List<LineAmount> lines = new ArrayList<>();
        for (Line line : layout.lines()) {
            Money amount = amounts.get(line.code());
            lines.add(new LineAmount(line.ordinal(), line.code(), line.label(), line.level(),
                                     line.kind(), line.side(), amount));
            if (line.kind() != LineKind.TOTAL) {
                if (line.side() == Direction.DEBIT) {
                    totalDebit = totalDebit.plus(amount);
                } else {
                    totalCredit = totalCredit.plus(amount);
                }
            }
        }
        Money net = totalCredit.minus(totalDebit);
        if (kind != Kind.INCOME_STATEMENT && !net.isZero()) {
            anomalies.add("L'etat ne s'equilibre pas : " + net.roundToCurrency()
                          + " de plus au credit qu'au debit");
        }
        return new Statement(kind, layout.id(), layout.code(), layout.label(), legalEntityId,
                             functional.code(), from, to, lines, totalDebit, totalCredit, net,
                             anomalies.isEmpty(), List.copyOf(anomalies));
    }

    /**
     * Le resultat de l'exercice en cours, au bilan : les mouvements des comptes de resultat
     * depuis le debut de l'exercice qui couvre la date, au credit — positif pour un benefice.
     * Ce que les comptes de resultat portent au-dela est un resultat anterieur non clos : le
     * bilan ne le presente pas, il le nomme.
     */
    private static void presentResult(Connection c, UUID legalEntityId, Layout layout,
                                      LocalDate to, CurrencyRef functional,
                                      Map<String, Money> amounts, List<String> anomalies) {
        Optional<Line> resultLine = layout.lines().stream()
            .filter(line -> line.kind() == LineKind.PROFIT_OR_LOSS).findFirst();
        Money allTime = profitAndLossNet(c, legalEntityId, null, to, functional);
        Optional<FiscalYears.FiscalYear> year = FiscalYears.covering(c, legalEntityId, to);
        Money current = year.map(y -> profitAndLossNet(c, legalEntityId, y.start(), to, functional))
            .orElse(Money.zero(functional));
        if (year.isEmpty()) {
            if (resultLine.isPresent() || !allTime.isZero()) {
                anomalies.add("Aucun exercice ne couvre le " + to + " : le resultat de l'exercice "
                              + "ne peut pas etre presente (" + allTime.roundToCurrency()
                              + " de resultat non affecte)");
            }
        } else {
            Money prior = allTime.minus(current);
            if (!prior.isZero()) {
                anomalies.add("Resultat anterieur non clos : " + prior.roundToCurrency()
                              + " — l'exercice precedent n'est pas clos, le bilan ne le presente "
                              + "pas");
            }
        }
        if (resultLine.isPresent()) {
            amounts.put(resultLine.get().code(), current);
        } else if (!current.isZero()) {
            anomalies.add("La maquette ne presente pas le resultat de l'exercice : "
                          + current.roundToCurrency());
        }
    }

    // ------------------------------------------------------------------ lecture du journal

    /**
     * Les ecritures de determination du resultat soldent les comptes de resultat a la cloture ;
     * elles ne sont pas de l'activite. Le compte de resultat les ignore : celui d'un exercice
     * clos montre ce que l'exercice a fait, pas sa cloture. Leurs contre-passations — l'annulation
     * d'une cloture — portent le meme type, parce qu'une contre-passation reprend le type de
     * l'ecriture d'origine ({@code Reversals}) : le type suffit a exclure la famille entiere.
     * Le bilan, lui, les lit : apres la cloture, le resultat est au compte de resultat de
     * l'exercice, et la rubrique du resultat en cours retombe a zero.
     */
    private static final String WITHOUT_YEAR_END =
        " AND e.transaction_type <> '" + FiscalYears.YEAR_END_RESULT + "'";

    private static List<AccountAmount> accountAmounts(Connection c, UUID legalEntityId,
                                                      AccountNature nature, LocalDate from,
                                                      LocalDate to, boolean activityOnly,
                                                      CurrencyRef functional) {
        List<AccountAmount> amounts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.code, a.account_kind,"
            + " SUM(CASE WHEN l.direction = 'DEBIT' THEN l.functional_amount"
            + "          ELSE -l.functional_amount END)"
            + " FROM journal_line l JOIN account a ON a.id = l.account_id"
            + (activityOnly ? " JOIN journal_entry e ON e.id = l.entry_id"
                              + " AND e.booking_date = l.booking_date" : "")
            + " WHERE l.legal_entity_id = ? AND a.nature = ? AND l.booking_date <= ?"
            + (from == null ? "" : " AND l.booking_date >= ?")
            + (activityOnly ? WITHOUT_YEAR_END : "")
            + " GROUP BY a.id, a.code, a.account_kind"
            + " HAVING SUM(CASE WHEN l.direction = 'DEBIT' THEN l.functional_amount"
            + "                 ELSE -l.functional_amount END) <> 0"
            + " ORDER BY a.code")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, nature.name());
            ps.setObject(3, to);
            if (from != null) {
                ps.setObject(4, from);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    amounts.add(new AccountAmount(rs.getObject(1, UUID.class), rs.getString(2),
                                                  AccountKind.valueOf(rs.getString(3)),
                                                  Money.of(rs.getBigDecimal(4), functional)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Soldes des comptes de " + nature, e);
        }
        return amounts;
    }

    /** Le net des comptes de resultat, positif au credit, sur une plage — ou depuis toujours. */
    private static Money profitAndLossNet(Connection c, UUID legalEntityId, LocalDate from,
                                          LocalDate to, CurrencyRef functional) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.functional_amount"
            + "                       ELSE -l.functional_amount END), 0)"
            + " FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.legal_entity_id = ? AND a.nature = 'PROFIT_AND_LOSS'"
            + " AND l.booking_date <= ?" + (from == null ? "" : " AND l.booking_date >= ?"))) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, to);
            if (from != null) {
                ps.setObject(3, from);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1), functional);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Net des comptes de resultat", e);
        }
    }
}
