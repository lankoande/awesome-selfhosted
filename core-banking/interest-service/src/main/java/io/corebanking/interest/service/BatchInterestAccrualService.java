package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyAccrual;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.domain.posting.PostingSource;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calcul des interets sur un lot de comptes.
 *
 * <h2>Pourquoi une seconde implementation</h2>
 *
 * <p>Le calcul compte par compte enchaine une vingtaine d'acces a la base : resolution du produit,
 * etat du cumul, serie des soldes, comptabilisation, enregistrement. C'est acceptable pour une
 * operation isolee — un recalcul retroactif, une correction — et inacceptable a l'echelle d'un
 * arrete. Mesure sur banc : 4,2 ms par compte, soit plus de deux heures pour deux millions de
 * comptes, la ou la fenetre de nuit en autorise quatre-vingt-dix minutes.
 *
 * <p>Cette implementation renverse la boucle. Les donnees sont lues <b>par lot</b> — un acces pour
 * tous les comptes, au lieu d'un acces par compte — le calcul se fait en memoire, et
 * l'imputation est <b>agregee</b>. L'etat de chaque compte vient de sa position, en un acces pour
 * le lot, jamais d'une somme sur son historique.
 *
 * <h2>Une ecriture par couple de comptes, pas une par client</h2>
 *
 * <p>Les interets courus d'un meme produit debitent tous le meme compte de charges et creditent
 * tous le meme compte d'interets courus. Produire deux millions d'ecritures a deux lignes vers les
 * deux memes comptes generaux n'apporte rien au journal : le detail par client, lui, est conserve
 * ligne a ligne dans {@code interest_accrual}, avec son assiette, son taux et sa fraction d'annee.
 *
 * <p>C'est la pratique du metier, et elle a une consequence a connaitre : le grand livre du compte
 * d'interets courus porte une ecriture par jour et par produit, pas une par client. La piste vers
 * le client passe par la table d'interets — d'ou l'importance d'y conserver l'integralite du
 * calcul, ce que fait le moteur. Chaque journee qui impute reference l'ecriture agregee qui porte
 * son montant.
 *
 * <h2>Deux cotes</h2>
 *
 * <p>Un compte courant est remunere sur ses jours crediteurs et debite d'agios sur ses jours
 * debiteurs : deux series, deux positions, deux couples de comptes. Le lot ne traite qu'un cote a
 * la fois — celui que les conditions fournies designent — et l'appelant le lance une fois par
 * cote.
 */
public final class BatchInterestAccrualService {

    private final Database database;
    private final PostingService postingService;

    public BatchInterestAccrualService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    /** Accrual calcule pour un compte, pret a etre enregistre. */
    private record Computed(UUID accountId, InterestTerms terms, List<DailyAccrual> days,
                            Money cumulative, Money postedBefore, Money delta, int generation,
                            LocalDate from) {
        Pair pair() {
            return new Pair(terms.debitAccount(), terms.creditAccount(), terms.side());
        }
    }

    private record Pair(UUID debit, UUID credit, AccrualSide side) {}

    /**
     * @param chunkTag marque distinguant ce lot des autres lots du meme traitement. Elle entre dans
     *                 la cle d'idempotence de l'ecriture agregee, et doit donc etre <b>derivee du
     *                 contenu du lot</b>, jamais de son rang. Si un compte est ajoute entre un echec
     *                 et sa reprise, les decoupages se decalent : une marque fondee sur le rang
     *                 ferait alors porter a un lot different la cle d'un lot deja impute, et
     *                 l'ecriture serait silencieusement omise comme un rejeu.
     */
    public BatchAccrualOutcome accrue(UUID legalEntityId, List<UUID> accountIds, LocalDate through,
                                      TermsProvider terms, LocalDate bookingDate, UUID actorId,
                                      UUID batchRunId, String chunkTag) {
        if (accountIds.isEmpty()) {
            return new BatchAccrualOutcome(0, 0, Map.of(), List.of(), List.of());
        }

        List<String> anomalies = new ArrayList<>();
        List<Computed> computed = new ArrayList<>(accountIds.size());

        database.inTransaction(connection -> {
            Map<UUID, CurrencyRef> currencies = currenciesOf(connection, accountIds);
            Map<InterestPositions.Key, InterestPositions.Position> positions =
                InterestPositions.loadAll(connection, accountIds, currencies);
            Map<UUID, LocalDate> firstValueDates = firstValueDatesOf(connection, accountIds);
            Map<UUID, List<DayMovement>> movements = movementsOf(connection, accountIds, through);

            for (UUID accountId : accountIds) {
                try {
                    computeOne(accountId, through, terms, currencies, positions, firstValueDates,
                               movements).ifPresent(computed::add);
                } catch (RuntimeException e) {
                    anomalies.add("Compte " + accountId + " non remunere : " + e.getMessage());
                }
            }
            return null;
        });

        Map<Pair, UUID> entries = postAggregated(legalEntityId, computed, bookingDate, through,
                                                 actorId, batchRunId, chunkTag);
        recordAll(computed, bookingDate, through, batchRunId, entries);

        Map<UUID, Money> deltas = new LinkedHashMap<>();
        computed.forEach(c -> deltas.put(c.accountId(), c.delta()));
        return new BatchAccrualOutcome(accountIds.size(), computed.size(), deltas,
                                       List.copyOf(entries.values()), anomalies);
    }

    // ------------------------------------------------------------------ calcul

    private java.util.Optional<Computed> computeOne(
            UUID accountId, LocalDate through, TermsProvider termsProvider,
            Map<UUID, CurrencyRef> currencies,
            Map<InterestPositions.Key, InterestPositions.Position> positions,
            Map<UUID, LocalDate> firstValueDates, Map<UUID, List<DayMovement>> movements) {

        CurrencyRef currency = currencies.get(accountId);
        if (currency == null) {
            throw new IllegalStateException("compte inconnu");
        }

        // Le parametrage est resolu AVANT de decider qu'il n'y a rien a remunerer. Un compte sans
        // mouvement dont le produit ne se resout pas doit etre signale des aujourd'hui : il aura
        // des mouvements demain, et le defaut serait alors decouvert par la reclamation du client.
        // C'est la difference entre un compte sans interet a calculer et un compte mal parametre.
        InterestTerms reference = termsProvider.termsFor(accountId, through);

        InterestPositions.Position state = positions.getOrDefault(
            new InterestPositions.Key(accountId, reference.side()),
            InterestPositions.Position.empty(accountId, reference.side(), currency));

        LocalDate from = state.accruedThrough() == null
            ? firstValueDates.get(accountId)
            : state.accruedThrough().plusDays(1);
        if (from == null || through.isBefore(from)) {
            return java.util.Optional.empty();
        }
        Money cumulative = state.cumulativePrecise();
        List<DailyAccrual> days = new ArrayList<>();

        // Serie des soldes en date de valeur, reconstituee en memoire depuis les mouvements du lot.
        BigDecimal running = openingBalance(movements.get(accountId), from);
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (DayMovement movement : movements.getOrDefault(accountId, List.of())) {
            byDay.put(movement.valueDate(), movement.amount());
        }

        for (LocalDate day = from; !day.isAfter(through); day = day.plusDays(1)) {
            running = running.add(byDay.getOrDefault(day, BigDecimal.ZERO));
            InterestTerms termsOfDay = termsProvider.termsFor(accountId, day);
            requireStable(reference, termsOfDay, day);

            Money balance = Money.of(running.setScale(5, java.math.RoundingMode.UNNECESSARY),
                                     currency);
            Money basis = termsOfDay.side().basis(balance);
            BigDecimal fraction = termsOfDay.dayCount().dayFraction(day);
            Money amount = termsOfDay.rates().accrue(basis, fraction);
            cumulative = cumulative.plus(amount);
            days.add(new DailyAccrual(day, basis, termsOfDay.rates().effectiveRate(basis), fraction,
                                      amount));
        }

        Money delta = cumulative.roundToCurrency().minus(state.postedTotal());
        return java.util.Optional.of(new Computed(accountId, reference, days, cumulative,
                                                  state.postedTotal(), delta,
                                                  state.generation() + 1, from));
    }

    private static void requireStable(InterestTerms reference, InterestTerms ofDay, LocalDate day) {
        if (reference.side() != ofDay.side()
            || !reference.debitAccount().equals(ofDay.debitAccount())
            || !reference.creditAccount().equals(ofDay.creditAccount())) {
            throw new IllegalStateException(
                "le parametrage du " + day + " change le cote remunere ou les comptes "
                + "d'imputation en cours de periode");
        }
    }

    private static BigDecimal openingBalance(List<DayMovement> movements, LocalDate from) {
        BigDecimal opening = BigDecimal.ZERO;
        if (movements != null) {
            for (DayMovement movement : movements) {
                if (movement.valueDate().isBefore(from)) {
                    opening = opening.add(movement.amount());
                }
            }
        }
        return opening;
    }

    // ------------------------------------------------------------------ imputation agregee

    private Map<Pair, UUID> postAggregated(UUID legalEntityId, List<Computed> computed,
                                           LocalDate bookingDate, LocalDate through, UUID actorId,
                                           UUID batchRunId, String chunkTag) {
        Map<Pair, Money> totals = new LinkedHashMap<>();
        Map<Pair, Integer> counts = new LinkedHashMap<>();

        for (Computed c : computed) {
            if (c.delta().isZero()) {
                continue;
            }
            totals.merge(c.pair(), c.delta(), Money::plus);
            counts.merge(c.pair(), 1, Integer::sum);
        }

        Map<Pair, UUID> entries = new LinkedHashMap<>();
        totals.forEach((pair, total) -> {
            if (total.isZero()) {
                return;
            }
            Money amount = total.abs();
            UUID debit = total.isPositive() ? pair.debit() : pair.credit();
            UUID credit = total.isPositive() ? pair.credit() : pair.debit();

            var result = postingService.post(new PostingCommand(
                IdempotencyKey.forBatch(String.valueOf(batchRunId), "INTEREST_ACCRUAL_BATCH",
                                        pair.debit(), pair.credit(), pair.side(), through,
                                        chunkTag),
                legalEntityId, bookingDate, "INTEREST_ACCRUAL", actorId,
                PostingSource.BATCH, batchRunId,
                List.of(PostingLine.debit(debit, amount, through, "Interets courus du " + through),
                        PostingLine.credit(credit, amount, through, "Interets courus du " + through)),
                Map.of("side", pair.side().name(), "through", through.toString(),
                       "accounts", String.valueOf(counts.get(pair)))));
            entries.put(pair, result.entryId());
        });
        return entries;
    }

    private void recordAll(List<Computed> computed, LocalDate bookingDate, LocalDate through,
                           UUID batchRunId, Map<Pair, UUID> entries) {
        if (computed.isEmpty()) {
            return;
        }

        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO interest_accrual(id, account_id, accrual_date, side, generation,"
                + " basis_balance, effective_rate, year_fraction, precise_amount,"
                + " cumulative_precise, posted_delta, posted_cumulative, entry_id, booking_date,"
                + " batch_run_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Computed c : computed) {
                    UUID entryId = c.delta().isZero() ? null : entries.get(c.pair());
                    Money running = c.cumulative();
                    List<Money> cumulatives = new ArrayList<>(c.days().size());
                    for (int i = c.days().size() - 1; i >= 0; i--) {
                        cumulatives.add(0, running);
                        running = running.minus(c.days().get(i).amount());
                    }
                    for (int i = 0; i < c.days().size(); i++) {
                        DailyAccrual day = c.days().get(i);
                        boolean last = i == c.days().size() - 1;
                        ps.setObject(1, Ids.newId());
                        ps.setObject(2, c.accountId());
                        ps.setObject(3, day.day());
                        ps.setString(4, c.terms().side().name());
                        ps.setInt(5, c.generation());
                        ps.setBigDecimal(6, day.basisBalance().amount());
                        ps.setBigDecimal(7, day.effectiveRate());
                        ps.setBigDecimal(8, day.yearFraction()
                            .setScale(18, java.math.RoundingMode.HALF_EVEN));
                        ps.setBigDecimal(9, day.amount().amount());
                        ps.setBigDecimal(10, cumulatives.get(i).amount());
                        ps.setBigDecimal(11, last ? c.delta().amount() : BigDecimal.ZERO);
                        ps.setBigDecimal(12, last ? c.postedBefore().plus(c.delta()).amount()
                                                  : c.postedBefore().amount());
                        ps.setObject(13, last ? entryId : null);
                        ps.setObject(14, last ? bookingDate : null);
                        ps.setObject(15, batchRunId);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new LedgerStoreException("Enregistrement des interets du lot", e);
            }
            for (Computed c : computed) {
                InterestPositions.recordAccrual(connection, c.accountId(), c.terms().side(),
                                                c.terms().accruedAccount(), through,
                                                c.cumulative(), c.postedBefore().plus(c.delta()),
                                                c.generation());
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ lectures par lot

    private record DayMovement(LocalDate valueDate, BigDecimal amount) {}

    private Map<UUID, CurrencyRef> currenciesOf(Connection c, Collection<UUID> accountIds) {
        Map<UUID, CurrencyRef> currencies = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, cur.code, cur.scale, cur.rounding_mode FROM account a"
            + " JOIN currency cur ON cur.code = a.currency WHERE a.id = ANY (?)")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    currencies.put(rs.getObject(1, UUID.class),
                        new CurrencyRef(rs.getString(2), rs.getInt(3),
                                        java.math.RoundingMode.valueOf(rs.getString(4))));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Devises du lot", e);
        }
        return currencies;
    }

    private Map<UUID, LocalDate> firstValueDatesOf(Connection c, Collection<UUID> accountIds) {
        Map<UUID, LocalDate> dates = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT account_id, MIN(value_date) FROM journal_line WHERE account_id = ANY (?)"
            + " GROUP BY account_id")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dates.put(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Premieres dates de valeur du lot", e);
        }
        return dates;
    }

    private Map<UUID, List<DayMovement>> movementsOf(Connection c, Collection<UUID> accountIds,
                                                     LocalDate through) {
        Map<UUID, List<DayMovement>> movements = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id, l.value_date,"
            + "       SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.account_id = ANY (?) AND l.value_date <= ?"
            + " GROUP BY l.account_id, l.value_date"
            + " ORDER BY l.account_id, l.value_date")) {
            ps.setArray(1, uuidArray(c, accountIds));
            ps.setObject(2, through);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movements.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>())
                        .add(new DayMovement(rs.getObject(2, LocalDate.class),
                                             rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Mouvements en date de valeur du lot", e);
        }
        return movements;
    }

    private static Array uuidArray(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray());
    }

    /**
     * Marque d'un lot, derivee de son contenu : empreinte des identifiants de comptes qu'il
     * couvre. Deux decoupages differents produisent deux marques differentes, et un meme
     * decoupage rejoue produit la meme.
     */
    public static String chunkTag(List<UUID> accountIds) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            accountIds.stream().sorted().forEach(
                id -> digest.update(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
