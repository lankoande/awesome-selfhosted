package io.corebanking.regulatory;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.StatementLayouts;
import io.corebanking.ledger.store.Statements;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Liasse et consolidation : les deux productions qui regardent plus d'un etat a la fois.
 *
 * <h2>La liasse</h2>
 *
 * <p>Elle produit les etats qu'elle cite et <b>les confronte entre eux</b>. Le controle qui la
 * justifie est le rapprochement du resultat : celui que porte le compte de resultat est celui
 * qu'annonce le bilan. S'ils different, ce n'est pas une presentation a corriger — c'est une
 * comptabilite a reprendre, et l'ecart est nomme avant la transmission.
 *
 * <h2>La consolidation</h2>
 *
 * <p>Elle bute sur une contrainte du socle qui est une protection : <b>aucune requete ne lit deux
 * entites a la fois</b>. L'etat consolide se produit donc entite par entite, chacune dans sa
 * propre portee, et l'agregation se fait en memoire. C'est aussi la bonne facon de le faire,
 * puisque chaque entite tient ses comptes dans sa devise et que la conversion doit etre explicite.
 *
 * <p>Ce qui se fait face s'elimine : la creance d'une entite du groupe sur une autre est la dette
 * de celle-ci. Les comptes qui se repondent sont declares par paires ; l'agregation verifie
 * qu'ils se repondent effectivement, et <b>nomme l'ecart au lieu de l'absorber</b> — un ecart
 * d'elimination est une operation intra-groupe comptabilisee d'un seul cote, et c'est exactement
 * ce qu'un commissaire aux comptes cherche.
 */
public final class GroupReporting {

    private final Database database;

    public GroupReporting(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /** Les lignes produites et les anomalies constatees. */
    public record Result(List<ReportFilings.Line> lines, List<String> anomalies) {

        public Result {
            lines = List.copyOf(lines == null ? List.of() : lines);
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    // ------------------------------------------------------------------ liasse

    /**
     * Produit la liasse en vigueur a la date, etat par etat, et confronte ses etats.
     *
     * <p>Les lignes portent le code de leur etat en prefixe : une liasse est un document, et une
     * rubrique « TOTAL » du bilan n'est pas celle du compte de resultat.
     */
    public Result statementPack(java.sql.Connection c, UUID legalEntityId, String packCode,
                                LocalDate periodStart, LocalDate periodEnd, CurrencyRef currency) {
        StatementPacks.Pack pack = StatementPacks.activeOn(c, legalEntityId, packCode, periodEnd)
            .orElseThrow(() -> new ReportFilings.FilingRefusedException(
                "Aucune liasse " + packCode + " n'est en vigueur au " + periodEnd));
        List<ReportFilings.Line> lines = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        Money resultatDuCompteDeResultat = null;
        Money resultatPorteAuBilan = null;

        // Le compte de resultat d'une liasse court depuis l'ouverture de l'exercice, pas depuis
        // le debut de la periode declaree : c'est la convention comptable, et c'est ce qui rend
        // le rapprochement possible — le bilan presente le resultat depuis l'ouverture, et
        // comparer deux fenetres differentes ne voudrait rien dire. Sans exercice ouvert, le
        // bilan le dit deja ; on se rabat alors sur la periode.
        LocalDate depuis = io.corebanking.ledger.store.FiscalYears.covering(c, legalEntityId,
                periodEnd)
            .map(io.corebanking.ledger.store.FiscalYears.FiscalYear::start)
            .orElse(periodStart);

        for (StatementLayouts.Kind kind : pack.items()) {
            Statements.Statement statement = switch (kind) {
                case BALANCE_SHEET -> Statements.balanceSheet(c, legalEntityId, periodEnd);
                case OFF_BALANCE_SHEET -> Statements.offBalanceSheet(c, legalEntityId, periodEnd);
                case INCOME_STATEMENT ->
                    Statements.incomeStatement(c, legalEntityId, depuis, periodEnd);
            };
            ajouterAnomalies(anomalies, kind, statement);
            for (Statements.LineAmount line : statement.lines()) {
                lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.GL_ACCOUNT,
                    pack.id(), kind.name() + "/" + line.code(), line.label(), line.amount(),
                    null, kind.name(), null, null, "niveau " + line.level()));
                if (line.kind() == StatementLayouts.LineKind.PROFIT_OR_LOSS) {
                    if (kind == StatementLayouts.Kind.BALANCE_SHEET) {
                        resultatPorteAuBilan = line.amount();
                    }
                }
            }
            if (kind == StatementLayouts.Kind.INCOME_STATEMENT) {
                resultatDuCompteDeResultat = statement.net();
            }
        }

        // Le controle qui fait la liasse : le resultat du compte de resultat est celui porte au
        // bilan. Un ecart n'est pas une question de presentation.
        if (resultatDuCompteDeResultat != null && resultatPorteAuBilan != null
            && !resultatDuCompteDeResultat.equals(resultatPorteAuBilan)) {
            anomalies.add("Le compte de resultat degage " + resultatDuCompteDeResultat
                + " quand le bilan porte " + resultatPorteAuBilan
                + " : ce n'est pas une presentation a corriger, c'est une comptabilite a "
                + "reprendre");
        }
        return new Result(lines, anomalies);
    }

    // ------------------------------------------------------------------ consolidation

    /**
     * Agrege les comptes du groupe, entite par entite, en devise de presentation.
     *
     * <p>Chaque entite est lue <b>dans sa propre portee</b> : le cloisonnement n'est pas leve
     * pour consolider, il est traverse une entite a la fois. L'agregation additionne les postes
     * de meme code apres application de la quote-part, puis retranche ce que les eliminations
     * declarees font disparaitre.
     */
    public Result consolidated(UUID consolidatingEntityId, String scopeCode, LocalDate periodStart,
                               LocalDate periodEnd, CurrencyRef presentation) {
        ConsolidationScopes.Scope scope = database.inEntity(consolidatingEntityId,
            c -> ConsolidationScopes.activeOn(c, consolidatingEntityId, scopeCode, periodEnd)
                .orElseThrow(() -> new ReportFilings.FilingRefusedException(
                    "Aucun perimetre " + scopeCode + " n'est en vigueur au " + periodEnd)));
        if (!scope.presentationCurrency().equals(presentation.code())) {
            throw new ReportFilings.FilingRefusedException("Le perimetre " + scopeCode
                + " presente en " + scope.presentationCurrency() + ", l'etat est demande en "
                + presentation.code() + " : une consolidation ne change pas de devise en cours "
                + "de route");
        }
        Map<String, Money> postes = new LinkedHashMap<>();
        // Combien de membres agreges portent chaque rubrique. Deux entites dont les maquettes ne
        // nomment pas les memes rubriques ne s'additionnent pas : elles se juxtaposent, et le
        // total du groupe est faux sans que rien ne le dise. On compte, et on nomme l'ecart.
        Map<String, Integer> porteurs = new LinkedHashMap<>();
        int agreges = 0;
        List<String> anomalies = new ArrayList<>();
        Money zero = Money.zero(presentation);

        for (ConsolidationScopes.Member member : scope.members()) {
            if (member.method() == ConsolidationScopes.Method.EQUITY) {
                // Mise en equivalence : rien n'est agrege. Le dire est plus honnete que de
                // presenter une quote-part de situation nette que le socle ne calcule pas.
                anomalies.add("Entite " + member.entityId() + " mise en equivalence a "
                    + member.interestPercent() + " % : sa quote-part de situation nette n'est pas "
                    + "encore calculee, elle ne figure pas a l'agregation");
                continue;
            }
            BigDecimal quotePart = member.interestPercent()
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            database.inEntity(member.entityId(), c -> {
                CurrencyRef locale = io.corebanking.ledger.store.Entities.functionalCurrency(
                    c, member.entityId());
                if (!locale.code().equals(presentation.code())) {
                    // Convertir suppose un cours de cloture : tant que le socle ne le pose pas
                    // pour la consolidation, il refuse de convertir au hasard.
                    anomalies.add("Entite " + member.entityId() + " tient ses comptes en "
                        + locale.code() + ", le groupe presente en " + presentation.code()
                        + " : la conversion de consolidation n'est pas parametree, l'entite ne "
                        + "figure pas a l'agregation");
                    return null;
                }
                // Meme convention qu'en liasse : le resultat consolide court depuis l'ouverture
                // de l'exercice de chaque entite, pas depuis le debut de la periode declaree.
                LocalDate depuis = io.corebanking.ledger.store.FiscalYears.covering(
                        c, member.entityId(), periodEnd)
                    .map(io.corebanking.ledger.store.FiscalYears.FiscalYear::start)
                    .orElse(periodStart);
                for (StatementLayouts.Kind kind : List.of(StatementLayouts.Kind.BALANCE_SHEET,
                                                          StatementLayouts.Kind.INCOME_STATEMENT)) {
                    Statements.Statement statement = kind == StatementLayouts.Kind.BALANCE_SHEET
                        ? Statements.balanceSheet(c, member.entityId(), periodEnd)
                        : Statements.incomeStatement(c, member.entityId(), depuis, periodEnd);
                    ajouterAnomalies(anomalies, kind, statement);
                    for (Statements.LineAmount line : statement.lines()) {
                        if (line.kind() != StatementLayouts.LineKind.DETAIL) {
                            continue;
                        }
                        // La quote-part est arrondie a la precision interne avant d'entrer dans
                        // un montant : une multiplication par 0,6 sur des centimes produit plus
                        // de decimales que la monnaie n'en connait.
                        Money part = Money.of(line.amount().amount().multiply(quotePart)
                                                  .setScale(5, RoundingMode.HALF_UP),
                                              presentation).roundToCurrency();
                        String cle = kind.name() + "/" + line.code() + "|" + line.label();
                        postes.merge(cle, part, Money::plus);
                        porteurs.merge(cle, 1, Integer::sum);
                    }
                }
                return null;
            });
            agreges++;
        }

        for (Map.Entry<String, Integer> porteur : porteurs.entrySet()) {
            if (porteur.getValue() < agreges) {
                anomalies.add("La rubrique " + porteur.getKey().split("\\|", 2)[0]
                    + " n'est portee que par " + porteur.getValue() + " des " + agreges
                    + " entites agregees : leurs maquettes ne nomment pas les memes rubriques, et "
                    + "le total du groupe juxtapose au lieu d'additionner");
            }
        }

        // Les eliminations : deux comptes qui se font face doivent se repondre. L'ecart est
        // nomme, jamais absorbe — c'est une operation intra-groupe comptabilisee d'un seul cote.
        Money elimine = zero;
        for (ConsolidationScopes.Elimination elimination : scope.eliminations()) {
            Money gauche = soldeDe(elimination.leftEntityId(), elimination.leftAccountId(),
                                   periodEnd, presentation);
            Money droite = soldeDe(elimination.rightEntityId(), elimination.rightAccountId(),
                                   periodEnd, presentation);
            Money ecart = gauche.plus(droite);
            if (!ecart.isZero()) {
                anomalies.add("Elimination « " + elimination.label() + " » : "
                    + gauche + " d'un cote, " + droite + " de l'autre, ecart de " + ecart
                    + " — une operation intra-groupe est comptabilisee d'un seul cote");
            }
            elimine = elimine.plus(gauche.abs());
        }

        List<ReportFilings.Line> lines = new ArrayList<>();
        for (Map.Entry<String, Money> poste : postes.entrySet()) {
            String[] parts = poste.getKey().split("\\|", 2);
            lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.GL_ACCOUNT, scope.id(),
                parts[0], parts.length > 1 ? parts[1] : parts[0], poste.getValue(), null,
                scope.code(), null, null, null));
        }
        if (!scope.eliminations().isEmpty()) {
            lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.GL_ACCOUNT, scope.id(),
                "ELIMINATIONS", "Operations intra-groupe eliminees", elimine.negate(), null,
                scope.code(), null, scope.eliminations().size(), null));
        }
        return new Result(lines, anomalies);
    }

    /**
     * Le solde d'un compte d'une entite membre, <b>signe au debit</b>, lu dans sa portee.
     *
     * <p>Le solde ordinaire est oriente dans le sens naturel du compte : une creance et une dette
     * de meme montant y sont toutes deux positives, et leur somme ferait le double au lieu de
     * zero. Deux comptes qui se font face ne se comparent donc que dans une convention commune —
     * positif au debit —, et celle-ci ne depend pas de la facon dont chacun a ete parametre.
     */
    private Money soldeDe(UUID entityId, UUID accountId, LocalDate asOf, CurrencyRef presentation) {
        return database.inEntity(entityId, c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.functional_amount"
                + "                       ELSE -l.functional_amount END), 0)"
                + "  FROM journal_line l WHERE l.account_id = ? AND l.booking_date <= ?")) {
                ps.setObject(1, accountId);
                ps.setObject(2, asOf);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return Money.of(rs.getBigDecimal(1), presentation).roundToCurrency();
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException(
                    "Solde du compte " + accountId, e);
            }
        });
    }

    /**
     * Les anomalies d'un etat, comptes sans rubrique compris.
     *
     * <p>{@link Statements} rend les deux separement — un ecran ne dit pas deux fois la meme
     * chose. Pour une liasse, la distinction n'a pas lieu d'etre : un compte qu'aucune regle
     * n'affecte manque a l'etat, et on ne declare pas un actif incomplet.
     */
    private static void ajouterAnomalies(List<String> anomalies, StatementLayouts.Kind kind,
                                         Statements.Statement statement) {
        anomalies.addAll(statement.anomalies());
        for (Statements.Unassigned orphelin : statement.unassigned()) {
            anomalies.add(kind.name() + " : compte " + orphelin.code() + ", solde "
                          + orphelin.amount().roundToCurrency()
                          + (orphelin.side() == io.corebanking.ledger.domain.account.Direction.DEBIT
                                 ? " debiteur" : " crediteur")
                          + " qu'aucune regle n'affecte a une rubrique");
        }
    }
}
