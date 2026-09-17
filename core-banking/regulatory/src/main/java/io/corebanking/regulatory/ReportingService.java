package io.corebanking.regulatory;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La production des etats reglementaires.
 *
 * <h2>Ce que le service garantit</h2>
 *
 * <ul>
 *   <li><b>L'etat se calcule, il ne se saisit pas.</b> Chaque methode lit le journal et les
 *       sous-livres a la date de fin de periode. Un etat saisi a la main est un etat qu'on ne
 *       peut pas reproduire, et l'inspection demande toujours a le reproduire.</li>
 *   <li><b>Le recensement des engagements est agrege par client</b>, tous concours confondus,
 *       bilan et hors bilan. C'est la raison d'etre du dedoublonnage du referentiel : un client
 *       en double se declare deux fois sous le seuil au lieu d'une fois au-dessus, et la
 *       centrale des risques ne voit plus le risque qu'elle est faite pour voir.</li>
 *   <li><b>Rien ne sort vers le bureau du credit sans consentement.</b> L'historique de
 *       remboursement est une donnee personnelle ; le declarer sans accord est une faute, pas un
 *       oubli. Le consentement se revoque, et la revocation vaut pour les etats suivants.</li>
 *   <li><b>Un etat regenere est confronte a celui qui a ete transmis.</b> S'ils different, ce
 *       n'est pas l'etat qui a bouge : ce sont les donnees, et c'est une anomalie.</li>
 * </ul>
 */
public final class ReportingService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingService.class);

    private final Database database;

    public ReportingService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    // ------------------------------------------------------------------ production

    /**
     * Produit et fige l'etat d'une declaration pour la periode qui se termine ce jour-la.
     *
     * @return l'identifiant de l'etat produit
     */
    public UUID produce(UUID legalEntityId, UUID declarationId, LocalDate periodEnd,
                        LocalDate producedOn, UUID producedBy) {
        return database.inEntity(legalEntityId, c -> {
            RegulatoryDeclarations.Declaration declaration =
                RegulatoryDeclarations.require(c, declarationId);
            if (!declaration.legalEntityId().equals(legalEntityId)) {
                throw new IllegalArgumentException("Declaration inconnue : " + declarationId);
            }
            CurrencyRef currency = functionalCurrency(c, legalEntityId);
            List<ReportFilings.Line> lines = compute(c, legalEntityId, declaration.method(),
                                                     declaration.frequency(),
                                                     declaration.thresholdAmount(), periodEnd,
                                                     currency);
            return ReportFilings.produce(c, declaration, periodEnd, lines, currency, producedOn,
                                         producedBy);
        });
    }

    /**
     * Recalcule un etat deja produit et le confronte au contenu fige.
     *
     * <p>C'est l'exigence de reproductibilite, rendue verifiable : un etat regenere apres
     * controle doit etre identique a celui qui a ete transmis. Les ecarts sont nommes, ligne par
     * ligne — un etat qui ne se reproduit plus n'est pas une curiosite, c'est le signe que
     * quelque chose a bouge derriere lui.
     */
    public List<String> differences(UUID legalEntityId, UUID filingId) {
        return database.inEntity(legalEntityId, c -> {
            ReportFilings.Filing filing = ReportFilings.require(c, filingId);
            RegulatoryDeclarations.Declaration declaration =
                RegulatoryDeclarations.require(c, filing.declarationId());
            CurrencyRef currency = functionalCurrency(c, legalEntityId);
            // Le parametrage confronte est celui de la production — la methode et le seuil
            // recopies sur l'etat —, jamais celui d'aujourd'hui : un seuil deplace depuis ferait
            // croire a un ecart de donnees la ou il n'y a qu'un changement de regle.
            List<ReportFilings.Line> now = compute(c, legalEntityId, filing.method(),
                                                   declaration.frequency(), filing.thresholdUsed(),
                                                   filing.periodEnd(), currency);
            return compare(filing.lines(), now);
        });
    }

    /**
     * Confronte deux versions d'un etat, ligne par ligne.
     *
     * <p>La comparaison porte sur <b>tout ce que la ligne dit</b>, pas seulement son montant : le
     * hors bilan, la classe de risque, le retard et le nombre d'occurrences sont declares au meme
     * titre. Ne comparer que le montant laisserait passer un engagement disparu ou une classe
     * changee — c'est-a-dire precisement ce que le superviseur lit sur la ligne.
     */
    static List<String> compare(List<ReportFilings.Line> filed, List<ReportFilings.Line> now) {
        List<String> differences = new ArrayList<>();
        if (filed.size() != now.size()) {
            differences.add("L'etat transmis porte " + filed.size() + " lignes, le recalcul en "
                            + "donne " + now.size());
        }
        int common = Math.min(filed.size(), now.size());
        for (int i = 0; i < common; i++) {
            ReportFilings.Line a = filed.get(i);
            ReportFilings.Line b = now.get(i);
            String where = "Ligne " + (i + 1) + " — " + a.subjectReference() + " : ";
            if (!a.subjectId().equals(b.subjectId())) {
                differences.add("Ligne " + (i + 1) + " : " + a.subjectReference() + " transmis, "
                                + b.subjectReference() + " au recalcul");
                continue;
            }
            if (!a.amount().equals(b.amount())) {
                differences.add(where + a.amount() + " transmis, " + b.amount() + " au recalcul");
            }
            if (!Objects.equals(a.offBalance(), b.offBalance())) {
                differences.add(where + "hors bilan " + a.offBalance() + " transmis, "
                                + b.offBalance() + " au recalcul");
            }
            if (!Objects.equals(a.classification(), b.classification())) {
                differences.add(where + "classe " + a.classification() + " transmise, "
                                + b.classification() + " au recalcul");
            }
            if (!Objects.equals(a.daysPastDue(), b.daysPastDue())) {
                differences.add(where + "retard de " + a.daysPastDue() + " jours transmis, "
                                + b.daysPastDue() + " au recalcul");
            }
            if (!Objects.equals(a.occurrences(), b.occurrences())) {
                differences.add(where + a.occurrences() + " occurrences transmises, "
                                + b.occurrences() + " au recalcul");
            }
        }
        return differences;
    }

    private List<ReportFilings.Line> compute(Connection c, UUID entity,
                                             RegulatoryDeclarations.Method method,
                                             RegulatoryDeclarations.Frequency frequency,
                                             BigDecimal threshold, LocalDate periodEnd,
                                             CurrencyRef currency) {
        LocalDate periodStart = frequency.startOfPeriodEndingOn(periodEnd)
            .orElseThrow(() -> new ReportFilings.FilingRefusedException(
                "Le " + periodEnd + " ne ferme pas de periode " + frequency));
        return switch (method) {
            case ACCOUNTING_SITUATION -> accountingSituation(c, entity, periodEnd, currency);
            case CREDIT_REGISTRY ->
                creditRegistry(c, entity, periodEnd, currency, threshold, false);
            case CREDIT_BUREAU -> creditRegistry(c, entity, periodEnd, currency, null, true);
            case PAYMENT_INCIDENTS ->
                paymentIncidents(c, entity, periodStart, periodEnd, currency);
            case TAX_COLLECTION -> taxCollection(c, entity, periodStart, periodEnd, currency);
        };
    }

    // ------------------------------------------------------------------ situation comptable

    /**
     * La balance des comptes generaux a la fin de periode, en devise de tenue de compte.
     *
     * <p>Elle est exhaustive : un compte a solde nul y figure aussi, parce que son absence
     * serait lue comme un compte inexistant. Le solde est celui du journal a la date, pas celui
     * d'un cliche : le cliche est incremental et vit sa vie, le journal est la verite.
     */
    private List<ReportFilings.Line> accountingSituation(Connection c, UUID entity,
                                                         LocalDate periodEnd,
                                                         CurrencyRef currency) {
        List<ReportFilings.Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT a.id, a.code, a.normal_balance, a.nature,
                   COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.functional_amount
                                     ELSE -l.functional_amount END), 0) AS solde
              FROM account a
              LEFT JOIN journal_line l ON l.account_id = a.id AND l.booking_date <= ?
             WHERE a.legal_entity_id = ? AND a.account_kind IN ('GL','INTERNAL','NOSTRO','SUSPENSE')
             GROUP BY a.id, a.code, a.normal_balance, a.nature
             ORDER BY a.code
            """)) {
            ps.setObject(1, periodEnd);
            ps.setObject(2, entity);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Le solde est rendu dans le sens naturel du compte : un compte de passif
                    // credite porte un solde positif, comme dans la balance qu'on transmet.
                    BigDecimal debiteur = rs.getBigDecimal(5);
                    BigDecimal montant = "CREDIT".equals(rs.getString(3))
                        ? debiteur.negate() : debiteur;
                    lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.GL_ACCOUNT,
                        rs.getObject(1, UUID.class), rs.getString(2),
                        "Compte " + rs.getString(2),
                        Money.of(montant, currency).roundToCurrency(), null, rs.getString(4),
                        null, null, null));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Situation comptable au " + periodEnd, e);
        }
        return lines;
    }

    // ------------------------------------------------------------------ centrale des risques

    /**
     * Les engagements recenses par client, tous concours confondus.
     *
     * <p>Le bilan est l'encours reellement porte : le solde des comptes de pret du client a la
     * date. Le hors bilan est ce que la banque s'est engagee a mettre a disposition sans l'avoir
     * verse — les tranches planifiees non debloquees d'une ligne de mobilisation ouverte. Les
     * additionner serait faux ; les ignorer le serait aussi, parce qu'un engagement de
     * financement se realise a la demande du client, pas de la banque.
     *
     * <p>La classe de risque retenue est <b>la plus degradee</b> de ses concours : un client sain
     * sur un credit et douteux sur un autre est un client douteux. C'est la regle de contagion,
     * et elle est la raison pour laquelle le recensement est par client et non par contrat.
     */
    private List<ReportFilings.Line> creditRegistry(Connection c, UUID entity, LocalDate periodEnd,
                                                    CurrencyRef currency, BigDecimal threshold,
                                                    boolean consentOnly) {
        List<ReportFilings.Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
            WITH encours AS (
                -- L'encours porte, lu dans le journal a la date : la contre-passation y compte
                -- comme partout ailleurs, sans quoi l'etat contredirait la balance de la banque.
                SELECT k.customer_id AS party_id,
                       SUM(COALESCE(b.solde, 0)) AS bilan
                  FROM loan_contract k
                  LEFT JOIN LATERAL (
                      SELECT SUM(CASE WHEN l.direction = 'DEBIT' THEN l.functional_amount
                                      ELSE -l.functional_amount END) AS solde
                        FROM journal_line l
                       WHERE l.account_id = k.loan_account_id
                         AND l.booking_date <= ?) b ON TRUE
                 WHERE k.legal_entity_id = ? AND k.customer_id IS NOT NULL
                   AND k.disbursed_on <= ?
                 GROUP BY k.customer_id),
            engagements AS (
                -- Ce que la banque s'est engagee a mettre a disposition et n'a pas verse, tel
                -- qu'on le savait a la fin de periode : une tranche debloquee ou annulee depuis
                -- etait bien un engagement ce jour-la, et l'etat doit le redire a l'identique.
                SELECT k.customer_id AS party_id,
                       SUM(t.planned_amount) AS hors_bilan
                  FROM loan_tranche t
                  JOIN loan_contract k ON k.id = t.contract_id
                  JOIN loan_mobilisation m ON m.contract_id = k.id
                 WHERE k.legal_entity_id = ? AND k.customer_id IS NOT NULL
                   AND (t.released_on IS NULL OR t.released_on > ?)
                   AND (t.cancelled_on IS NULL OR t.cancelled_on > ?)
                   AND (m.closed_on IS NULL OR m.closed_on > ?)
                   -- L'engagement cesse avec le delai de tirage : au-dela, la banque n'est plus
                   -- tenue de verser, et le recenser surestimerait son exposition.
                   AND m.drawdown_deadline >= ?
                 GROUP BY k.customer_id),
            classe AS (
                -- La classe de chaque concours <b>a la fin de periode</b> — la derniere connue a
                -- cette date, pas la pire jamais atteinte : un credit revenu sain apres un retard
                -- serait sinon declare douteux pour toujours. La pire de ces classes est retenue
                -- pour le client : c'est la contagion, et c'est pourquoi le recensement est par
                -- client et non par contrat.
                SELECT derniere.customer_id AS party_id,
                       MAX(derniere.bucket_ordinal) AS pire,
                       MAX(derniere.days_past_due) AS retard
                  FROM (SELECT DISTINCT ON (x.contract_id)
                               k.customer_id, x.bucket_ordinal, x.days_past_due
                          FROM loan_classification x
                          JOIN loan_contract k ON k.id = x.contract_id
                         WHERE k.legal_entity_id = ? AND k.customer_id IS NOT NULL
                           AND x.status = 'ACTIVE' AND x.classified_on <= ?
                         ORDER BY x.contract_id, x.classified_on DESC, x.created_at DESC)
                       AS derniere
                 GROUP BY derniere.customer_id)
            SELECT p.id, p.reference, p.display_name,
                   COALESCE(encours.bilan, 0) AS bilan,
                   COALESCE(engagements.hors_bilan, 0) AS hors_bilan,
                   classe.pire, classe.retard
              FROM party p
              LEFT JOIN encours ON encours.party_id = p.id
              LEFT JOIN engagements ON engagements.party_id = p.id
              LEFT JOIN classe ON classe.party_id = p.id
             WHERE p.legal_entity_id = ?
               AND (COALESCE(encours.bilan, 0) > 0 OR COALESCE(engagements.hors_bilan, 0) > 0)
               AND (NOT ?::boolean OR EXISTS (
                        SELECT 1 FROM party_credit_bureau_consent k
                         WHERE k.party_id = p.id AND k.granted
                           AND k.granted_on <= ?
                           AND (k.revoked_on IS NULL OR k.revoked_on > ?)))
               AND (?::numeric IS NULL
                    OR COALESCE(encours.bilan, 0) + COALESCE(engagements.hors_bilan, 0) >= ?)
             ORDER BY p.reference
            """)) {
            ps.setObject(1, periodEnd);
            ps.setObject(2, entity);
            ps.setObject(3, periodEnd);
            ps.setObject(4, entity);
            ps.setObject(5, periodEnd);
            ps.setObject(6, periodEnd);
            ps.setObject(7, periodEnd);
            ps.setObject(8, periodEnd);
            ps.setObject(9, entity);
            ps.setObject(10, periodEnd);
            ps.setObject(11, entity);
            ps.setBoolean(12, consentOnly);
            ps.setObject(13, periodEnd);
            ps.setObject(14, periodEnd);
            ps.setBigDecimal(15, threshold);
            ps.setBigDecimal(16, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int ordinal = rs.getInt(6);
                    boolean classified = !rs.wasNull();
                    int retard = rs.getInt(7);
                    boolean late = !rs.wasNull();
                    lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.PARTY,
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Money.of(rs.getBigDecimal(4), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(5), currency).roundToCurrency(),
                        classified ? bucketOf(c, entity, ordinal) : null,
                        late ? retard : null, null, null));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des engagements au " + periodEnd, e);
        }
        return lines;
    }

    /** Le code de la classe de risque du rang retenu : c'est lui que le superviseur lit. */
    private String bucketOf(Connection c, UUID entity, int ordinal) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT b.code FROM risk_bucket b JOIN risk_profile r ON r.id = b.profile_id"
            + " WHERE r.legal_entity_id = ? AND b.ordinal = ? ORDER BY r.valid_from DESC LIMIT 1")) {
            ps.setObject(1, entity);
            ps.setInt(2, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : String.valueOf(ordinal);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Classe de risque", e);
        }
    }

    // ------------------------------------------------------------------ incidents de paiement

    /**
     * Les incidents de paiement de la periode, par compte.
     *
     * <p>Un incident se declare quel que soit son montant : c'est le fait qui compte, pas la
     * somme. Le compte porte son titulaire, parce que c'est la personne qui est recensee — et
     * c'est elle qui sera frappee d'interdiction bancaire si les incidents se repetent.
     */
    private List<ReportFilings.Line> paymentIncidents(Connection c, UUID entity,
                                                      LocalDate periodStart, LocalDate periodEnd,
                                                      CurrencyRef currency) {
        List<ReportFilings.Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT i.account_id, a.code, COUNT(*) AS nombre, SUM(i.amount) AS total,
                   MIN(i.occurred_on) AS premier,
                   COALESCE(MAX(p.reference), '') AS titulaire
              FROM cheque_incident i
              JOIN account a ON a.id = i.account_id
              LEFT JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= i.occurred_on
                   AND (h.valid_to IS NULL OR h.valid_to >= i.occurred_on)
              LEFT JOIN party p ON p.id = h.party_id
             WHERE i.legal_entity_id = ? AND i.occurred_on BETWEEN ? AND ?
             GROUP BY i.account_id, a.code
             ORDER BY a.code
            """)) {
            ps.setObject(1, entity);
            ps.setObject(2, periodStart);
            ps.setObject(3, periodEnd);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String titulaire = rs.getString(6);
                    lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.ACCOUNT,
                        rs.getObject(1, UUID.class), rs.getString(2),
                        titulaire.isEmpty() ? "Compte " + rs.getString(2)
                                            : "Titulaire " + titulaire,
                        Money.of(rs.getBigDecimal(4), currency).roundToCurrency(), null, null,
                        null, rs.getInt(3),
                        "premier incident le " + rs.getObject(5, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Incidents de paiement de la periode", e);
        }
        return lines;
    }

    // ------------------------------------------------------------------ fiscalite

    /**
     * Les taxes collectees sur la periode, par taxe.
     *
     * <p>Le montant du n'est pas un calcul refait sur l'assiette : c'est <b>ce qui est passe sur
     * le compte de collecte</b>. Recalculer reviendrait a declarer ce que la banque aurait du
     * prelever, quand l'administration attend ce qu'elle a preleve — et l'ecart entre les deux,
     * s'il existe, est un probleme de la banque, pas une variable de la declaration.
     *
     * <p>La contre-passation y compte comme partout : une commission annulee rend sa taxe, et la
     * declaration doit le dire. Ne pas la compter ferait reverser une taxe que le client ne doit
     * plus.
     *
     * <p>Une taxe sans mouvement sur la periode figure quand meme, a zero : son absence serait
     * lue comme un oubli de declaration, ce qui est une infraction, la ou zero collecte n'en est
     * pas une.
     */
    private List<ReportFilings.Line> taxCollection(Connection c, UUID entity,
                                                   LocalDate periodStart, LocalDate periodEnd,
                                                   CurrencyRef currency) {
        List<ReportFilings.Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT t.id, t.code, t.label, t.rate_percent, t.basis,
                   COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.functional_amount
                                     ELSE -l.functional_amount END), 0) AS collecte
              FROM tax_rule t
              LEFT JOIN journal_line l ON l.account_id = t.collection_account_id
                   AND l.booking_date BETWEEN ? AND ?
             WHERE t.legal_entity_id = ? AND t.valid_from <= ?
               AND (t.valid_to IS NULL OR t.valid_to >= ?)
             GROUP BY t.id, t.code, t.label, t.rate_percent, t.basis
             ORDER BY t.code
            """)) {
            ps.setObject(1, periodStart);
            ps.setObject(2, periodEnd);
            ps.setObject(3, entity);
            ps.setObject(4, periodEnd);
            ps.setObject(5, periodStart);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new ReportFilings.Line(ReportFilings.SubjectKind.GL_ACCOUNT,
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Money.of(rs.getBigDecimal(6), currency).roundToCurrency(), null,
                        rs.getString(5), null, null,
                        "taux de reference " + rs.getBigDecimal(4) + " %"));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Taxes collectees de la periode", e);
        }
        return lines;
    }

    // ------------------------------------------------------------------ echeances

    /** Une echeance declarative depassee : la declaration, la periode, et de combien. */
    public record Overdue(String declarationCode, LocalDate periodEnd, LocalDate dueOn,
                          boolean produced) {

        public String describe(LocalDate on) {
            return "Declaration " + declarationCode + " de la periode close le " + periodEnd
                   + " : echeance du " + dueOn + " depassee de "
                   + java.time.temporal.ChronoUnit.DAYS.between(dueOn, on) + " jours"
                   + (produced ? " — l'etat est produit, il n'est pas transmis"
                               : " — aucun etat n'est produit");
        }
    }

    /** Compte rendu d'une passe de surveillance des echeances declaratives. */
    public record Watch(int declarations, List<Overdue> overdue) {

        public Watch {
            overdue = List.copyOf(overdue == null ? List.of() : overdue);
        }
    }

    /**
     * Les echeances declaratives depassees a une date donnee.
     *
     * <p>Le retard declaratif est en lui-meme un manquement : il ne se decouvre pas quand le
     * superviseur appelle. La surveillance remonte a douze periodes en arriere — au-dela, ce
     * n'est plus un retard, c'est un contentieux, et il ne se traite pas par une anomalie
     * d'arrete.
     */
    public static final int WATCHED_PERIODS = 12;

    public Watch watch(UUID legalEntityId, LocalDate on) {
        return database.inEntity(legalEntityId, c -> {
            List<RegulatoryDeclarations.Declaration> declarations =
                RegulatoryDeclarations.activeOn(c, legalEntityId, on);
            List<Overdue> overdue = new ArrayList<>();
            for (RegulatoryDeclarations.Declaration declaration : declarations) {
                overdue.addAll(overdueOf(c, declaration, on));
            }
            if (!overdue.isEmpty()) {
                LOG.warn("Echeances declaratives depassees au {} : {}", on, overdue.size());
            }
            return new Watch(declarations.size(), overdue);
        });
    }

    private List<Overdue> overdueOf(Connection c, RegulatoryDeclarations.Declaration declaration,
                                    LocalDate on) {
        List<Overdue> overdue = new ArrayList<>();
        RegulatoryDeclarations.Frequency frequency = declaration.frequency();
        LocalDate start = frequency.startOfPeriodContaining(on);
        for (int i = 0; i < WATCHED_PERIODS; i++) {
            start = periodBefore(frequency, start);
            LocalDate end = frequency.nextStart(start).minusDays(1);
            if (start.isBefore(declaration.validFrom())
                || (declaration.validTo() != null && end.isAfter(declaration.validTo()))) {
                continue;
            }
            LocalDate due = declaration.dueOn(end);
            if (!on.isAfter(due)) {
                continue;
            }
            Optional<ReportFilings.Filing> filing =
                ReportFilings.forPeriod(c, declaration.id(), end);
            if (filing.isEmpty() || !filing.get().transmitted()) {
                overdue.add(new Overdue(declaration.code(), end, due, filing.isPresent()));
            }
        }
        return overdue;
    }

    private static LocalDate periodBefore(RegulatoryDeclarations.Frequency frequency,
                                          LocalDate start) {
        return switch (frequency) {
            case MONTHLY -> start.minusMonths(1);
            case QUARTERLY -> start.minusMonths(3);
            case YEARLY -> start.minusYears(1);
        };
    }

    // ------------------------------------------------------------------ consentement

    /**
     * Enregistre ou revoque le consentement du client a la declaration au bureau du credit.
     *
     * <p>La revocation ne reecrit pas le passe : ce qui a ete declare l'a ete. Elle vaut pour les
     * etats suivants, et c'est la seule promesse que la banque peut tenir.
     */
    public void recordConsent(UUID legalEntityId, UUID partyId, boolean granted, LocalDate on,
                              UUID recordedBy) {
        database.inEntity(legalEntityId, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO party_credit_bureau_consent(party_id, legal_entity_id, granted,"
                + " granted_on, revoked_on, recorded_by) VALUES (?,?,?,?,?,?)"
                + " ON CONFLICT (party_id) DO UPDATE SET granted = EXCLUDED.granted,"
                + "   granted_on = CASE WHEN EXCLUDED.granted"
                + "                     THEN EXCLUDED.granted_on"
                + "                     ELSE party_credit_bureau_consent.granted_on END,"
                + "   revoked_on = EXCLUDED.revoked_on, recorded_by = EXCLUDED.recorded_by,"
                + "   updated_at = now()")) {
                ps.setObject(1, partyId);
                ps.setObject(2, legalEntityId);
                ps.setBoolean(3, granted);
                ps.setObject(4, on);
                ps.setObject(5, granted ? null : on);
                ps.setObject(6, recordedBy);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Consentement au bureau du credit", e);
            }
            return null;
        });
    }

    /** Le consentement en vigueur d'un tiers a une date donnee. */
    public static boolean consented(Connection c, UUID partyId, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM party_credit_bureau_consent WHERE party_id = ? AND granted"
            + "   AND granted_on <= ? AND (revoked_on IS NULL OR revoked_on > ?)")) {
            ps.setObject(1, partyId);
            ps.setObject(2, on);
            ps.setObject(3, on);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Consentement du tiers " + partyId, e);
        }
    }

    // ------------------------------------------------------------------ outillage

    private CurrencyRef functionalCurrency(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode FROM legal_entity e"
            + "  JOIN currency cur ON cur.code = e.functional_currency WHERE e.id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Devise de tenue de l'entite", e);
        }
    }
}
