package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyAccrual;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comptabilisation des interets courus, et recalcul retroactif.
 *
 * <h2>Le probleme de l'arrondi quotidien</h2>
 *
 * <p>En devise sans subdivision — le XOF n'a pas de centimes — un interet couru quotidien n'est
 * jamais comptabilisable tel quel : 115,068 49 XOF ne s'impute pas. Trois strategies existent, et
 * deux sont fausses.
 *
 * <ul>
 *   <li><b>Arrondir chaque jour</b> et imputer l'arrondi : derive systematique, 25 XOF par an et
 *       par compte sur un solde de 1,2 M a 3,5 %. A l'echelle d'un portefeuille, des millions.</li>
 *   <li><b>Attendre la capitalisation</b> pour tout imputer : exact, mais les interets courus non
 *       echus n'apparaissent plus au bilan entre deux capitalisations. Non conforme.</li>
 *   <li><b>Imputer l'ecart du cumul arrondi</b> — la strategie retenue ici.</li>
 * </ul>
 *
 * <p>Chaque jour, le moteur calcule le cumul exact depuis l'origine, l'arrondit, et impute la
 * <b>difference</b> avec ce qui a deja ete impute. Le montant impute reste toujours un entier, le
 * cumul comptabilise ne s'ecarte jamais de plus d'une demi-unite du cumul exact, et la derive est
 * structurellement impossible : elle est corrigee a chaque journee.
 *
 * <h2>Le recalcul retroactif</h2>
 *
 * <p>Une operation antidatee modifie la serie des soldes en date de valeur, donc tous les interets
 * courus depuis cette date. Ce n'est pas un cas exceptionnel : il survient dans les premieres
 * semaines d'exploitation. Le moteur doit savoir extourner les interets devenus faux et les
 * reemettre — {@link #recomputeFrom}. Un moteur qui en est incapable facture des agios faux, ce
 * qui se traduit en reclamations de masse.
 */
public final class InterestAccrualService {

    private final Database database;
    private final PostingService postingService;

    public InterestAccrualService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    /**
     * Calcule et impute les interets courus jusqu'a une date de valeur incluse, en repartant du
     * lendemain de la derniere journee deja calculee.
     */
    public AccrualOutcome accrueThrough(UUID legalEntityId, UUID accountId, LocalDate through,
                                        InterestTermsResolver resolver, LocalDate bookingDate,
                                        UUID actorId, UUID batchRunId) {
        InterestTerms reference = resolver.termsAt(through);
        AccrualState state = database.inTransaction(c -> loadState(c, accountId, reference.side()));

        LocalDate from = state.lastAccrualDate() == null
            ? database.inTransaction(c -> ValueDatedSeries.firstValueDate(c, accountId))
            : state.lastAccrualDate().plusDays(1);

        if (from == null || through.isBefore(from)) {
            return new AccrualOutcome(accountId, from, through, state.generation(),
                                      state.cumulativePrecise(), state.postedTotal(),
                                      Money.zero(state.currency()), null);
        }
        return compute(legalEntityId, accountId, from, through, resolver, reference, bookingDate,
                       actorId, batchRunId, state);
    }

    /**
     * Extourne les interets courus a partir d'une date de valeur, puis les recalcule sur la serie
     * de soldes corrigee.
     *
     * <p>A appeler des qu'une ecriture porte une date de valeur anterieure a la derniere journee
     * deja remuneree. Les ecritures d'interets devenues fausses sont contre-passees, jamais
     * modifiees, et les lignes de calcul d'origine sont conservees en generation anterieure : on
     * peut donc toujours reconstituer ce qui avait ete facture, et pourquoi.
     */
    public AccrualOutcome recomputeFrom(UUID legalEntityId, UUID accountId, LocalDate fromValueDate,
                                        InterestTermsResolver resolver, LocalDate bookingDate,
                                        UUID actorId, UUID batchRunId) {
        InterestTerms reference = resolver.termsAt(fromValueDate);
        LocalDate lastAccrued = database.inTransaction(
            c -> loadState(c, accountId, reference.side()).lastAccrualDate());
        if (lastAccrued == null || lastAccrued.isBefore(fromValueDate)) {
            // Rien n'a encore ete remunere sur cette periode : le calcul courant suffira.
            return accrueThrough(legalEntityId, accountId, lastAccrued == null ? fromValueDate
                                                                              : lastAccrued,
                                 resolver, bookingDate, actorId, batchRunId);
        }

        List<PostedAccrual> aReprendre = database.inTransaction(
            c -> loadActiveEntriesFrom(c, accountId, reference.side(), fromValueDate));

        // 1. Contre-passer les ecritures d'interets devenues fausses.
        for (PostedAccrual posted : aReprendre) {
            postingService.reverse(
                posted.entryId(), posted.bookingDate(), bookingDate,
                IdempotencyKey.forBatch(String.valueOf(batchRunId), "INTEREST_REVERSAL",
                                        posted.entryId()),
                "Recalcul retroactif des interets a compter du " + fromValueDate);
        }

        // 2. Neutraliser les journees de calcul concernees, sans les supprimer.
        database.inTransaction(c -> {
            markReversed(c, accountId, reference.side(), fromValueDate);
            return null;
        });

        // 3. Recalculer sur la serie corrigee, avec le parametrage en vigueur a chaque journee.
        AccrualState state = database.inTransaction(c -> loadState(c, accountId, reference.side()));
        return compute(legalEntityId, accountId, fromValueDate, lastAccrued, resolver, reference,
                       bookingDate, actorId, batchRunId, state);
    }

    // ------------------------------------------------------------------ calcul

    private AccrualOutcome compute(UUID legalEntityId, UUID accountId, LocalDate from,
                                   LocalDate through, InterestTermsResolver resolver,
                                   InterestTerms reference, LocalDate bookingDate, UUID actorId,
                                   UUID batchRunId, AccrualState state) {

        // La generation doit etre determinee AVANT l'imputation : elle entre dans la cle
        // d'idempotence. Sans elle, une reemission apres extourne porterait la meme cle que
        // l'ecriture d'origine, serait prise pour un rejeu, et n'imputerait rien — le compte
        // d'interets courus resterait a zero apres un recalcul retroactif.
        int generation = database.inTransaction(
            c -> nextGeneration(c, accountId, reference.side(), from));

        List<DailyBalance> series = database.inTransaction(
            c -> ValueDatedSeries.build(c, accountId, from, through));
        if (series.isEmpty()) {
            return new AccrualOutcome(accountId, from, through, state.generation(),
                                      state.cumulativePrecise(), state.postedTotal(),
                                      Money.zero(state.currency()), null);
        }

        CurrencyRef currency = series.get(0).balance().currency();
        Money cumulative = state.cumulativePrecise().isZero()
            ? Money.zero(currency) : state.cumulativePrecise();

        // Chaque journee est remuneree avec le parametrage en vigueur ce jour-la. Un changement
        // de taux en cours de periode est donc pris en compte a la date exacte de son entree en
        // vigueur, et le rejeu du meme arrete redonne les memes montants.
        List<DailyAccrual> daily = new ArrayList<>(series.size());
        for (DailyBalance day : series) {
            InterestTerms termsOfDay = resolver.termsAt(day.day());
            requireStableStructure(reference, termsOfDay, day.day());

            Money basis = termsOfDay.side().basis(day.balance());
            BigDecimal fraction = termsOfDay.dayCount().dayFraction(day.day());
            Money amount = termsOfDay.rates().accrue(basis, fraction);
            cumulative = cumulative.plus(amount);
            daily.add(new DailyAccrual(day.day(), basis, termsOfDay.rates().effectiveRate(basis),
                                       fraction, amount));
        }

        // L'imputation est l'ecart entre le cumul exact arrondi et ce qui est deja impute.
        Money target = cumulative.roundToCurrency();
        Money delta = target.minus(state.postedTotal());

        UUID entryId = delta.isZero()
            ? null
            : postAccrual(legalEntityId, accountId, reference, delta, bookingDate, through,
                          generation, actorId, batchRunId);

        Money cumulativeFinal = cumulative;
        database.inTransaction(c -> {
            recordDays(c, accountId, reference.side(), generation, daily, cumulativeFinal,
                       delta, entryId, bookingDate);
            return null;
        });

        return new AccrualOutcome(accountId, from, through, generation, cumulative, target, delta,
                                  entryId);
    }

    /**
     * Le taux et la convention de jours peuvent varier d'une journee a l'autre : c'est le cas
     * normal d'un changement de bareme. Le <b>cote</b> remunere et les <b>comptes d'imputation</b>,
     * eux, ne le peuvent pas au sein d'un meme calcul : l'ecriture produite est unique et ne
     * saurait viser deux couples de comptes. Un tel changement est une migration de parametrage,
     * qui suppose de solder le calcul en cours avant de basculer. Le refus est explicite plutot que
     * silencieux — une imputation sur le mauvais compte de resultat ne se detecte qu'a l'arrete.
     */
    private void requireStableStructure(InterestTerms reference, InterestTerms ofDay,
                                        LocalDate day) {
        if (reference.side() != ofDay.side()
            || !reference.debitAccount().equals(ofDay.debitAccount())
            || !reference.creditAccount().equals(ofDay.creditAccount())) {
            throw new IllegalStateException(
                "Le parametrage du " + day + " change le cote remunere ou les comptes "
                + "d'imputation en cours de periode. Solder le calcul en cours avant de basculer : "
                + "une seule ecriture ne peut pas viser deux couples de comptes.");
        }
    }

    /**
     * Impute l'ecart. Un delta negatif — apres extourne, ou sur une serie de soldes revue a la
     * baisse — inverse simplement le sens des deux lignes : jamais de montant negatif au journal.
     */
    private UUID postAccrual(UUID legalEntityId, UUID accountId, InterestTerms terms, Money delta,
                             LocalDate bookingDate, LocalDate through, int generation, UUID actorId,
                             UUID batchRunId) {
        Money amount = delta.abs();
        UUID debit = delta.isPositive() ? terms.debitAccount() : terms.creditAccount();
        UUID credit = delta.isPositive() ? terms.creditAccount() : terms.debitAccount();

        IdempotencyKey key = IdempotencyKey.forBatch(
            String.valueOf(batchRunId), "INTEREST_ACCRUAL",
            accountId, terms.side(), through, "g" + generation);

        PostingResult result = postingService.post(new PostingCommand(
            key, legalEntityId, bookingDate, "INTEREST_ACCRUAL", actorId,
            io.corebanking.ledger.domain.posting.PostingSource.BATCH, batchRunId,
            List.of(PostingLine.debit(debit, amount, through, "Interets courus"),
                    PostingLine.credit(credit, amount, through, "Interets courus")),
            Map.of("account", accountId.toString(),
                   "side", terms.side().name(),
                   "through", through.toString(),
                   "generation", String.valueOf(generation))));
        return result.entryId();
    }

    // ------------------------------------------------------------------ persistance

    private record AccrualState(LocalDate lastAccrualDate, Money cumulativePrecise,
                                Money postedTotal, int generation, CurrencyRef currency) {}

    private record PostedAccrual(UUID entryId, LocalDate bookingDate) {}

    private AccrualState loadState(Connection c, UUID accountId, AccrualSide side) {
        CurrencyRef currency = io.corebanking.ledger.store.Balances.currencyOf(c, accountId);
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MAX(accrual_date),"
            + "       COALESCE(SUM(posted_delta), 0),"
            + "       COALESCE(MAX(generation), 1)"
            + "  FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LocalDate last = rs.getObject(1, LocalDate.class);
                Money posted = Money.of(rs.getBigDecimal(2), currency);
                int generation = rs.getInt(3);
                Money cumulative = last == null
                    ? Money.zero(currency)
                    : cumulativeAt(c, accountId, side, last, currency);
                return new AccrualState(last, cumulative, posted, generation, currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Etat des interets courus", e);
        }
    }

    private Money cumulativeAt(Connection c, UUID accountId, AccrualSide side, LocalDate day,
                               CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cumulative_precise FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND accrual_date = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Money.of(rs.getBigDecimal(1), currency) : Money.zero(currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Cumul des interets courus", e);
        }
    }

    private List<PostedAccrual> loadActiveEntriesFrom(Connection c, UUID accountId,
                                                      AccrualSide side, LocalDate from) {
        List<PostedAccrual> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT entry_id, booking_date FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND accrual_date >= ?"
            + "   AND status = 'ACTIVE' AND entry_id IS NOT NULL"
            + " ORDER BY booking_date")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new PostedAccrual(rs.getObject(1, UUID.class),
                                                  rs.getObject(2, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Ecritures d'interets a extourner", e);
        }
        return entries;
    }

    private void markReversed(Connection c, UUID accountId, AccrualSide side, LocalDate from) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE interest_accrual SET status = 'REVERSED'"
            + " WHERE account_id = ? AND side = ? AND accrual_date >= ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, from);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des journees recalculees", e);
        }
    }

    private void recordDays(Connection c, UUID accountId, AccrualSide side, int generation,
                            List<DailyAccrual> daily, Money cumulativeFinal, Money delta,
                            UUID entryId, LocalDate bookingDate) {
        Money running = cumulativeFinal;
        // Recalcul du cumul journee par journee, a rebours, pour l'historiser exactement.
        List<Money> cumulatives = new ArrayList<>(daily.size());
        for (int i = daily.size() - 1; i >= 0; i--) {
            cumulatives.add(0, running);
            running = running.minus(daily.get(i).amount());
        }

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO interest_accrual(id, account_id, accrual_date, side, generation,"
            + " basis_balance, effective_rate, year_fraction, precise_amount, cumulative_precise,"
            + " posted_delta, entry_id, booking_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 0; i < daily.size(); i++) {
                DailyAccrual day = daily.get(i);
                boolean isLast = i == daily.size() - 1;
                ps.setObject(1, Ids.newId());
                ps.setObject(2, accountId);
                ps.setObject(3, day.day());
                ps.setString(4, side.name());
                ps.setInt(5, generation);
                ps.setBigDecimal(6, day.basisBalance().amount());
                ps.setBigDecimal(7, day.effectiveRate());
                ps.setBigDecimal(8, day.yearFraction().setScale(18, java.math.RoundingMode.HALF_EVEN));
                ps.setBigDecimal(9, day.amount().amount());
                ps.setBigDecimal(10, cumulatives.get(i).amount());
                ps.setBigDecimal(11, isLast ? delta.amount() : BigDecimal.ZERO);
                ps.setObject(12, isLast ? entryId : null);
                ps.setObject(13, isLast ? bookingDate : null);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des interets courus", e);
        }
    }

    /**
     * Generation du calcul pour une journee : 1 au premier calcul, 2 apres un premier recalcul
     * retroactif, et ainsi de suite. Elle distingue les cles d'idempotence des reemissions
     * successives et conserve la tracabilite des calculs anterieurs.
     */
    private int nextGeneration(Connection c, UUID accountId, AccrualSide side, LocalDate day) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(MAX(generation), 0) + 1 FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND accrual_date = ?")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, day);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Generation de recalcul", e);
        }
    }
}
