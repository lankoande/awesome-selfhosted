package io.corebanking.ledger.store;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Exercices fiscaux.
 *
 * <p>Un exercice porte ses bornes, son statut et son compte de resultat — celui sur lequel la
 * cloture annuelle solde les charges et les produits. Il s'ouvre a deux, comme tout ce qui fixe
 * ce que la banque presente ; il se clot par la cloture annuelle, et se rouvre par son
 * annulation, en le disant.
 */
public final class FiscalYears {

    private FiscalYears() {}

    public record FiscalYear(UUID id, UUID legalEntityId, LocalDate start, LocalDate end,
                             UUID resultAccountId, String status, UUID closedByRunId) {}

    private static final String SELECT = "SELECT id, legal_entity_id, start_date, end_date,"
        + " result_account_id, status, closed_by_run_id FROM fiscal_year";

    /**
     * Ouvre un exercice. Le compte de resultat est un compte general de bilan de l'entite, dans
     * sa devise de tenue de compte : c'est lui qui recoit le resultat, il ne peut pas etre un
     * compte de resultat lui-meme.
     */
    public static UUID open(Connection c, UUID legalEntityId, LocalDate start, LocalDate end,
                            UUID resultAccountId, UUID createdBy, UUID approvedBy) {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(resultAccountId, "resultAccountId");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                "Un exercice se termine apres son debut : du " + start + " au " + end);
        }
        if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
            throw new IllegalArgumentException(
                "Un exercice s'ouvre a deux : le demandeur ne peut pas etre le valideur");
        }
        Account result = Accounts.loadAll(c, Set.of(resultAccountId)).get(resultAccountId);
        if (result == null) {
            throw new IllegalArgumentException("Compte de resultat inconnu : " + resultAccountId);
        }
        CurrencyRef functional = Entities.functionalCurrency(c, legalEntityId);
        if (!result.legalEntityId().equals(legalEntityId) || result.kind() != AccountKind.GL
            || result.nature() != AccountNature.BALANCE_SHEET
            || !result.currency().code().equals(functional.code()) || !result.postable()) {
            throw new IllegalArgumentException(
                "Le compte " + result.code() + " ne peut pas recevoir le resultat : il doit etre "
                + "un compte general de bilan imputable de l'entite, en " + functional.code());
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO fiscal_year(id, legal_entity_id, start_date, end_date,"
            + " result_account_id, created_by, approved_by) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, start);
            ps.setObject(4, end);
            ps.setObject(5, resultAccountId);
            ps.setObject(6, createdBy);
            ps.setObject(7, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Ouverture de l'exercice du " + start + " au " + end
                + " (un exercice ne chevauche pas un autre)", e);
        }
        return id;
    }

    // ------------------------------------------------------------------ lecture

    public static Optional<FiscalYear> endingOn(Connection c, UUID legalEntityId, LocalDate end) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND end_date = ?", legalEntityId, end);
    }

    public static Optional<FiscalYear> covering(Connection c, UUID legalEntityId, LocalDate date) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date",
                   legalEntityId, date);
    }

    public static List<FiscalYear> ofEntity(Connection c, UUID legalEntityId) {
        List<FiscalYear> years = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY start_date")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    years.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des exercices de l'entite", e);
        }
        return years;
    }

    /** Periodes de l'exercice, hors la derniere, qui ne sont pas closes : ce qui bloque la cloture. */
    public static List<String> periodsNotClosedBefore(Connection c, UUID legalEntityId,
                                                      LocalDate start, LocalDate end) {
        List<String> open = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT start_date, end_date, status FROM accounting_period"
            + " WHERE legal_entity_id = ? AND start_date >= ? AND end_date < ?"
            + "   AND status <> 'CLOSED' ORDER BY start_date")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, start);
            ps.setObject(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add("du " + rs.getObject(1, LocalDate.class) + " au "
                             + rs.getObject(2, LocalDate.class) + " (" + rs.getString(3) + ")");
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des periodes de l'exercice", e);
        }
        return open;
    }

    // ------------------------------------------------------------------ soldes de resultat

    /**
     * Solde d'un compte de resultat par agence et par devise, en date comptable : positif au
     * debit, negatif au credit. Lu dans le journal, pas dans un cliche : la determination du
     * resultat est exacte par construction.
     */
    public record ProfitAndLossBalance(UUID accountId, UUID branchId, CurrencyRef currency,
                                       Money signedBalance) {}

    public static List<ProfitAndLossBalance> profitAndLossBalances(Connection c,
                                                                   UUID legalEntityId,
                                                                   LocalDate through) {
        List<ProfitAndLossBalance> balances = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id, l.branch_id, cur.code, cur.scale, cur.rounding_mode,"
            + "       SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + "  JOIN currency cur ON cur.code = l.currency"
            + " WHERE l.legal_entity_id = ? AND a.nature = 'PROFIT_AND_LOSS'"
            + "   AND l.booking_date <= ?"
            + " GROUP BY l.account_id, l.branch_id, cur.code, cur.scale, cur.rounding_mode"
            + " HAVING SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount ELSE -l.amount END) <> 0"
            + " ORDER BY cur.code, l.branch_id, l.account_id")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, through);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(3), rs.getInt(4),
                                                           RoundingMode.valueOf(rs.getString(5)));
                    balances.add(new ProfitAndLossBalance(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), currency,
                        Money.of(rs.getBigDecimal(6), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des soldes de resultat", e);
        }
        return balances;
    }

    // ------------------------------------------------------------------ cloture

    public static void close(Connection c, UUID fiscalYearId, UUID runId) {
        update(c, "UPDATE fiscal_year SET status = 'CLOSED', closed_by_run_id = ? WHERE id = ?"
                  + " AND status <> 'CLOSED'", runId, fiscalYearId, "Cloture de l'exercice");
    }

    /** Rouvre un exercice clos ; REOPENED n'est pas OPEN : l'annulation laisse une trace. */
    public static void reopen(Connection c, UUID fiscalYearId) {
        update(c, "UPDATE fiscal_year SET status = 'REOPENED', closed_by_run_id = ? WHERE id = ?"
                  + " AND status = 'CLOSED'", null, fiscalYearId, "Reouverture de l'exercice");
    }

    private static void update(Connection c, String sql, UUID runId, UUID fiscalYearId,
                               String what) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            ps.setObject(2, fiscalYearId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(what + " : exercice " + fiscalYearId
                                                + " introuvable ou deja dans cet etat");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException(what, e);
        }
    }

    // ------------------------------------------------------------------ resultat de l'exercice

    /** Un exercice par son identifiant. */
    public static Optional<FiscalYear> find(Connection c, UUID fiscalYearId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, fiscalYearId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'exercice " + fiscalYearId, e);
        }
    }

    public static FiscalYear require(Connection c, UUID fiscalYearId) {
        return find(c, fiscalYearId).orElseThrow(() -> new UnknownFiscalYearException(fiscalYearId));
    }

    /**
     * L'exercice, verrouille pour la transaction en cours. Tout ce qui decide de son etat — sa
     * cloture, sa reouverture, l'affectation de son resultat — passe par ce verrou : deux
     * decisions concurrentes se serialisent, et la seconde relit l'exercice tel que la premiere
     * l'a laisse au lieu de raisonner sur un etat qui n'est plus.
     */
    public static FiscalYear lock(Connection c, UUID fiscalYearId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, fiscalYearId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new UnknownFiscalYearException(fiscalYearId);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrouillage de l'exercice " + fiscalYearId, e);
        }
    }

    /**
     * Le resultat net d'un exercice clos : ce que la cloture qui l'a clos a porte au compte de
     * resultat, lu dans ses ecritures de determination du resultat — positif pour un benefice,
     * negatif pour une perte. Vide tant que l'exercice n'est pas clos : un resultat ne se lit
     * pas sur un exercice ouvert, il n'existe pas encore.
     */
    public static Optional<Money> netResult(Connection c, FiscalYear year) {
        if (year.closedByRunId() == null) {
            return Optional.empty();
        }
        CurrencyRef currency = Balances.currencyOf(c, year.resultAccountId());
        Money net = Money.zero(currency);
        for (BranchResult branch : resultByBranch(c, year)) {
            net = net.plus(branch.credit());
        }
        return Optional.of(net);
    }

    /**
     * Le resultat par agence, tel que la cloture l'a porte au compte de resultat : positif au
     * credit (benefice de l'agence), negatif au debit (perte). C'est ce que l'affectation solde,
     * agence par agence, pour que le compte de resultat ne garde de solde nulle part.
     */
    public record BranchResult(UUID branchId, Money credit) {}

    public static List<BranchResult> resultByBranch(Connection c, FiscalYear year) {
        if (year.closedByRunId() == null) {
            return List.of();
        }
        CurrencyRef currency = Balances.currencyOf(c, year.resultAccountId());
        List<BranchResult> results = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.branch_id,"
            + " SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END)"
            + " FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id"
            + " AND e.booking_date = l.booking_date"
            + " WHERE l.account_id = ? AND e.batch_run_id = ? AND e.transaction_type = ?"
            + " AND e.reversal_of IS NULL"
            + " GROUP BY l.branch_id"
            + " HAVING SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END) <> 0"
            + " ORDER BY l.branch_id")) {
            ps.setObject(1, year.resultAccountId());
            ps.setObject(2, year.closedByRunId());
            ps.setString(3, YEAR_END_RESULT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new BranchResult(rs.getObject(1, UUID.class),
                                                 Money.of(rs.getBigDecimal(2), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du resultat par agence", e);
        }
        return results;
    }

    /** Type des ecritures de determination du resultat, celles que la cloture annuelle passe. */
    public static final String YEAR_END_RESULT = "YEAR_END_RESULT";

    /** Type de l'ecriture d'affectation du resultat. */
    public static final String RESULT_APPROPRIATION = "RESULT_APPROPRIATION";

    // ------------------------------------------------------------------ affectation du resultat

    /** Une destination du resultat : reserve, report a nouveau, dividendes a payer. */
    public record Allocation(UUID accountId, Money amount) {}

    /**
     * La decision d'affectation, telle que l'assemblee l'a prise.
     *
     * @param bookingDate date comptable de l'ecriture, apres la fin de l'exercice
     * @param decidedOn   date de la decision
     * @param reference   la piece qui la porte : proces-verbal, resolution
     */
    public record Appropriation(UUID fiscalYearId, LocalDate bookingDate, LocalDate decidedOn,
                                String reference, List<Allocation> allocations, UUID createdBy,
                                UUID approvedBy) {
        public Appropriation {
            Objects.requireNonNull(fiscalYearId, "fiscalYearId");
            Objects.requireNonNull(bookingDate, "bookingDate");
            Objects.requireNonNull(decidedOn, "decidedOn");
            allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));
        }
    }

    /** Une affectation comptabilisee ; contre-passee, elle ne compte plus, mais elle reste. */
    public record AppropriationRecord(UUID id, UUID fiscalYearId, UUID entryId,
                                      LocalDate bookingDate, LocalDate decidedOn, String reference,
                                      Money netResult, UUID createdBy, UUID approvedBy,
                                      boolean reversed) {}

    /**
     * Affecte le resultat d'un exercice clos : une ecriture, a deux, qui solde le compte de
     * resultat de l'exercice — agence par agence, la ou la cloture l'a porte — sur les comptes
     * que la decision designe, au siege. Les destinations sont des comptes generaux de bilan de
     * l'entite, dans sa devise de tenue de compte, et leur somme est exactement le resultat :
     * une affectation partielle n'existe pas, le report a nouveau est une destination comme une
     * autre. Un exercice rouvert n'a plus de resultat ; un exercice deja affecte ne l'est pas
     * deux fois tant que son ecriture n'est pas contre-passee — ni par deux demandes
     * concurrentes : l'affectation s'execute sous le verrou de l'exercice.
     */
    public static AppropriationRecord appropriate(Connection c, PostingService posting,
                                                  Appropriation request) {
        // Sous le verrou de l'exercice : deux affectations concurrentes se suivent, et la
        // seconde voit la premiere ; une annulation de cloture concurrente attend, ou est vue.
        FiscalYear year = lock(c, request.fiscalYearId());
        if (!"CLOSED".equals(year.status())) {
            throw new NotAppropriableException(
                "L'exercice du " + year.start() + " au " + year.end() + " n'est pas clos ("
                + year.status() + ") : son resultat n'est pas determine");
        }
        currentAppropriation(c, year.id()).ifPresent(existing -> {
            throw new NotAppropriableException(
                "Le resultat de l'exercice du " + year.start() + " au " + year.end()
                + " est deja affecte par l'ecriture " + existing.entryId() + " du "
                + existing.bookingDate() + " : la contre-passer avant d'affecter a nouveau");
        });
        if (request.createdBy() == null || request.approvedBy() == null
            || request.approvedBy().equals(request.createdBy())) {
            throw new IllegalArgumentException(
                "Le resultat s'affecte a deux : le demandeur ne peut pas etre le valideur");
        }
        if (request.reference() == null || request.reference().isBlank()) {
            throw new IllegalArgumentException(
                "La piece de la decision d'affectation est obligatoire");
        }
        if (!request.bookingDate().isAfter(year.end())) {
            throw new IllegalArgumentException(
                "L'affectation du resultat se comptabilise apres la fin de l'exercice, le "
                + year.end() + " : pas le " + request.bookingDate());
        }
        if (request.decidedOn().isBefore(year.end())) {
            throw new IllegalArgumentException(
                "Le resultat se decide apres la fin de l'exercice, le " + year.end()
                + " : pas le " + request.decidedOn());
        }
        Money net = netResult(c, year).orElseThrow();
        if (net.isZero()) {
            throw new NotAppropriableException(
                "Le resultat de l'exercice du " + year.start() + " au " + year.end()
                + " est nul : rien a affecter");
        }
        Account result = Accounts.loadAll(c, Set.of(year.resultAccountId()))
            .get(year.resultAccountId());
        List<Allocation> allocations = request.allocations();
        if (allocations.isEmpty()) {
            throw new IllegalArgumentException("Une affectation designe au moins une destination");
        }
        Set<UUID> ids = new HashSet<>();
        allocations.forEach(allocation -> ids.add(allocation.accountId()));
        if (ids.size() != allocations.size()) {
            throw new IllegalArgumentException("Un compte de destination n'est cite qu'une fois");
        }
        Map<UUID, Account> destinations = Accounts.loadAll(c, ids);
        Money allocated = Money.zero(result.currency());
        for (Allocation allocation : allocations) {
            Account destination = destinations.get(allocation.accountId());
            if (destination == null || !destination.legalEntityId().equals(year.legalEntityId())) {
                throw new IllegalArgumentException(
                    "Compte de destination inconnu : " + allocation.accountId());
            }
            if (destination.id().equals(result.id()) || destination.kind() != AccountKind.GL
                || destination.nature() != AccountNature.BALANCE_SHEET || !destination.postable()
                || !destination.currency().code().equals(result.currency().code())) {
                throw new IllegalArgumentException(
                    "Le compte " + destination.code() + " ne peut pas recevoir le resultat : une "
                    + "destination est un compte general de bilan imputable, en "
                    + result.currency().code() + ", autre que le compte de resultat");
            }
            if (!allocation.amount().currency().code().equals(result.currency().code())
                || !allocation.amount().isPositive()) {
                throw new IllegalArgumentException(
                    "Chaque destination recoit un montant positif en " + result.currency().code()
                    + " : " + destination.code() + " recevrait " + allocation.amount());
            }
            allocated = allocated.plus(allocation.amount());
        }
        if (!allocated.equals(net.abs())) {
            throw new IllegalArgumentException(
                "L'affectation porte sur " + allocated.roundToCurrency()
                + " ; le resultat de l'exercice est " + net.roundToCurrency()
                + " : tout le resultat s'affecte, ni plus ni moins");
        }

        // L'ecriture : le compte de resultat solde la ou la cloture l'a porte, les destinations
        // au siege ; le service d'imputation complete les liaisons s'il y a lieu.
        UUID headOffice = Branches.headOffice(c, year.legalEntityId());
        String narrative = "Affectation du resultat de l'exercice du " + year.start() + " au "
            + year.end() + " — " + request.reference().trim();
        List<PostingLine> lines = new ArrayList<>();
        for (BranchResult branch : resultByBranch(c, year)) {
            Money amount = branch.credit().abs();
            lines.add((branch.credit().isPositive()
                       ? PostingLine.debit(result.id(), amount, request.bookingDate(), narrative)
                       : PostingLine.credit(result.id(), amount, request.bookingDate(), narrative))
                      .withBranch(branch.branchId()));
        }
        for (Allocation allocation : allocations) {
            lines.add((net.isPositive()
                       ? PostingLine.credit(allocation.accountId(), allocation.amount(),
                                            request.bookingDate(), narrative)
                       : PostingLine.debit(allocation.accountId(), allocation.amount(),
                                           request.bookingDate(), narrative))
                      .withBranch(headOffice));
        }
        UUID id = Ids.newId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("fiscalYearId", year.id().toString());
        metadata.put("appropriationId", id.toString());
        metadata.put("decidedOn", request.decidedOn().toString());
        PostingResult posted = posting.post(PostingCommand.online(
            IdempotencyKey.of(RESULT_APPROPRIATION + "-" + id), year.legalEntityId(),
            request.bookingDate(), RESULT_APPROPRIATION, request.createdBy(), lines)
            .withBranch(headOffice).withMetadata(metadata));

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO result_appropriation(id, legal_entity_id, fiscal_year_id, entry_id,"
            + " booking_date, decided_on, reference, net_result, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, year.legalEntityId());
            ps.setObject(3, year.id());
            ps.setObject(4, posted.entryId());
            ps.setObject(5, request.bookingDate());
            ps.setObject(6, request.decidedOn());
            ps.setString(7, request.reference().trim());
            ps.setBigDecimal(8, net.amount());
            ps.setObject(9, request.createdBy());
            ps.setObject(10, request.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'affectation du resultat", e);
        }
        return new AppropriationRecord(id, year.id(), posted.entryId(), request.bookingDate(),
                                       request.decidedOn(), request.reference().trim(), net,
                                       request.createdBy(), request.approvedBy(), false);
    }

    private static final String SELECT_APPROPRIATION =
        "SELECT r.id, r.fiscal_year_id, r.entry_id, r.booking_date, r.decided_on, r.reference,"
        + " r.net_result, r.created_by, r.approved_by, jr.reversed_entry_id IS NOT NULL,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM result_appropriation r"
        + " JOIN fiscal_year y ON y.id = r.fiscal_year_id"
        + " JOIN account a ON a.id = y.result_account_id"
        + " JOIN currency cur ON cur.code = a.currency"
        + " LEFT JOIN journal_reversal jr ON jr.reversed_entry_id = r.entry_id"
        + " WHERE r.fiscal_year_id = ?";

    /** L'affectation en vigueur : celle dont l'ecriture n'est pas contre-passee. */
    public static Optional<AppropriationRecord> currentAppropriation(Connection c,
                                                                     UUID fiscalYearId) {
        return appropriations(c, fiscalYearId).stream()
            .filter(record -> !record.reversed()).findFirst();
    }

    /** Toutes les affectations d'un exercice, contre-passees comprises, de la derniere a la premiere. */
    public static List<AppropriationRecord> appropriations(Connection c, UUID fiscalYearId) {
        List<AppropriationRecord> records = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_APPROPRIATION + " ORDER BY r.created_at DESC, r.id")) {
            ps.setObject(1, fiscalYearId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(11), rs.getInt(12),
                                                           RoundingMode.valueOf(rs.getString(13)));
                    records.add(new AppropriationRecord(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getString(6),
                        Money.of(rs.getBigDecimal(7), currency), rs.getObject(8, UUID.class),
                        rs.getObject(9, UUID.class), rs.getBoolean(10)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des affectations de l'exercice", e);
        }
        return records;
    }

    public static class UnknownFiscalYearException extends RuntimeException {
        public UnknownFiscalYearException(UUID fiscalYearId) {
            super("Exercice inconnu : " + fiscalYearId);
        }
    }

    /** L'exercice n'est pas dans un etat ou son resultat s'affecte : un conflit, pas une requete fausse. */
    public static class NotAppropriableException extends IllegalStateException {
        public NotAppropriableException(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ interne

    private static Optional<FiscalYear> one(Connection c, String sql, UUID legalEntityId,
                                            LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'exercice", e);
        }
    }

    private static FiscalYear read(ResultSet rs) throws SQLException {
        return new FiscalYear(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                              rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                              rs.getObject(5, UUID.class), rs.getString(6),
                              rs.getObject(7, UUID.class));
    }
}
