package io.corebanking.loan.service;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Interets courus non echus sur credits.
 *
 * <h2>La regle d'etalement</h2>
 *
 * <p>L'interet d'une echeance est fixe par l'echeancier : a annuite constante, il est calcule au
 * taux periodique proportionnel, qui n'est pas un taux journalier ; dans les autres methodes, sur
 * les jours reellement ecoules. Plutot que de recalculer chaque jour un interet dont la somme ne
 * redonnerait pas exactement celui de l'echeance, le moteur <b>etale lineairement l'interet
 * contractuel de l'echeance sur les jours de sa periode</b> : a la journee {@code j}, le cumul
 * exact vaut {@code interet x jours ecoules / jours de la periode}. A la date d'echeance, le cumul
 * est l'interet de l'echeance, au centime pres et par construction. La regle vaut pour toutes les
 * methodes d'amortissement, et pour un rattrapage de plusieurs journees.
 *
 * <p>Le montant impute est l'ecart entre le cumul arrondi et ce qui a deja ete impute — le meme
 * mecanisme que les interets sur depots, avec la meme propriete : jamais de derive d'arrondi.
 *
 * <h2>A l'echeance</h2>
 *
 * <p>La creance de l'echeance reprend l'interet des courus ; l'etalement de la journee d'echeance
 * complete le cumul dans le meme arrete. Les deux se compensent : le compte de courus revient a
 * zero pour cette echeance, et le produit a ete reconnu jour apres jour.
 *
 * <h2>Echeancier remplace, credit suspendu</h2>
 *
 * <p>Un echeancier remplace — remboursement anticipe, rechelonnement — emporte ses courus : les
 * echeances qu'il ne reclamera jamais sont reprises, et le nouvel echeancier repart de sa propre
 * premiere periode. Un credit sous suspension d'interets constate ses courus en interets reserves,
 * hors resultat, comme ses echeances.
 *
 * <h2>Une ecriture par couple de comptes</h2>
 *
 * <p>Comme pour les depots, l'imputation est agregee par couple compte de courus et compte de
 * produit ; le detail par echeance et par journee est conserve ligne a ligne.
 */
public final class LoanInterestAccrualService {

    private static final int CHUNK_SIZE = Integer.getInteger("loan.accrual.chunk", 5_000);

    private final Database database;
    private final PostingService postingService;

    public LoanInterestAccrualService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    /** Compte rendu d'une passe d'etalement. */
    public record Outcome(long linesExamined, long linesAccrued, long entries,
                          List<String> anomalies) {
        public Outcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    /** Comptes d'imputation et agence du credit : une paire de lignes par agence dans le lot. */
    private record Pair(UUID accrued, UUID credit, UUID branchId) {}

    public Outcome accrue(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                          UUID batchRunId) {
        List<LoanStore.AccrualCandidate> candidates = database.inTransaction(
            c -> LoanStore.accrualCandidates(c, legalEntityId, businessDate));
        if (candidates.isEmpty()) {
            return new Outcome(0, 0, 0, List.of());
        }
        Set<UUID> contracts = new java.util.LinkedHashSet<>();
        candidates.forEach(candidate -> contracts.add(candidate.contractId()));
        Set<UUID> suspended = database.inTransaction(
            c -> LoanStore.suspendedContracts(c, contracts));

        Map<String, ProductVersion> products = new HashMap<>();
        List<String> anomalies = new ArrayList<>();
        long accrued = 0;
        long entries = 0;

        for (int start = 0; start < candidates.size(); start += CHUNK_SIZE) {
            List<LoanStore.AccrualCandidate> chunk = candidates.subList(
                start, Math.min(start + CHUNK_SIZE, candidates.size()));

            List<LoanStore.InterestAccrualRow> rows = new ArrayList<>(chunk.size());
            for (LoanStore.AccrualCandidate line : chunk) {
                if (line.doneToday()) {
                    continue;                              // reprise : journee deja enregistree
                }
                try {
                    ProductVersion product = products.computeIfAbsent(
                        line.productCode() + "@" + businessDate,
                        key -> database.inTransaction(c -> ProductCatalog.resolveAt(
                            c, legalEntityId, line.productCode(), businessDate)));
                    rows.add(compute(line, businessDate, product,
                                     suspended.contains(line.contractId())));
                } catch (RuntimeException e) {
                    anomalies.add("credit " + line.reference() + ", echeance " + line.number()
                                  + " : " + e.getMessage());
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            Map<Pair, UUID> posted = post(legalEntityId, rows, businessDate, actorId, batchRunId);
            entries += posted.size();
            database.inTransaction(c -> {
                LoanStore.insertInterestAccruals(
                    c, rows, businessDate,
                    row -> posted.get(new Pair(row.accruedAccountId(), creditOf(row),
                                               row.line().branchId())),
                    batchRunId);
                return null;
            });
            accrued += rows.size();
        }
        return new Outcome(candidates.size(), accrued, entries, anomalies);
    }

    // ------------------------------------------------------------------ calcul

    private LoanStore.InterestAccrualRow compute(LoanStore.AccrualCandidate line,
                                                 LocalDate businessDate, ProductVersion product,
                                                 boolean suspended) {
        int periodDays = (int) ChronoUnit.DAYS.between(line.periodStart(), line.dueDate()) + 1;
        int elapsed = (int) Math.min(periodDays,
                                     ChronoUnit.DAYS.between(line.periodStart(), businessDate) + 1);
        Money cumulative;
        if (line.superseded()) {
            // L'echeancier n'est plus : ce qui avait ete constate pour cette echeance est repris.
            cumulative = Money.zero(line.currency());
        } else {
            BigDecimal share = line.interest().amount()
                .multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(periodDays), Money.MAX_INTERNAL_SCALE,
                        RoundingMode.HALF_EVEN);
            cumulative = Money.of(share, line.currency());
        }
        Money delta = cumulative.roundToCurrency().minus(line.posted());
        UUID accrued = LoanCatalog.accruedInterest(product);
        UUID credit = suspended ? LoanCatalog.reservedInterest(product)
                                : LoanCatalog.interestIncome(product);
        return new LoanStore.InterestAccrualRow(line, periodDays, elapsed, cumulative, delta,
                                                accrued, suspended, credit);
    }

    private static UUID creditOf(LoanStore.InterestAccrualRow row) {
        return row.creditAccountId();
    }

    // ------------------------------------------------------------------ imputation agregee

    private Map<Pair, UUID> post(UUID legalEntityId, List<LoanStore.InterestAccrualRow> rows,
                                 LocalDate businessDate, UUID actorId, UUID batchRunId) {
        Map<Pair, Money> totals = new LinkedHashMap<>();
        for (LoanStore.InterestAccrualRow row : rows) {
            if (!row.delta().isZero()) {
                totals.merge(new Pair(row.accruedAccountId(), row.creditAccountId(),
                                      row.line().branchId()),
                             row.delta(), Money::plus);
            }
        }
        String chunkTag = chunkTag(rows);
        Map<Pair, UUID> entries = new LinkedHashMap<>();
        totals.forEach((pair, total) -> {
            if (total.isZero()) {
                return;
            }
            Money amount = total.abs();
            UUID debit = total.isPositive() ? pair.accrued() : pair.credit();
            UUID credit = total.isPositive() ? pair.credit() : pair.accrued();
            var result = postingService.post(PostingCommand.batch(
                IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_ICNE", pair.accrued(),
                                        pair.credit(), businessDate, chunkTag, pair.branchId()),
                legalEntityId, businessDate, LoanSchemas.EVENT_INTEREST_ACCRUAL, actorId,
                batchRunId,
                List.of(PostingLine.debit(debit, amount, businessDate,
                                          "Interets courus sur credits au " + businessDate),
                        PostingLine.credit(credit, amount, businessDate,
                                           "Interets courus sur credits au " + businessDate)))
                .withBranch(pair.branchId()));
            entries.put(pair, result.entryId());
        });
        return entries;
    }

    /** Marque du lot, derivee de son contenu : les echeances qu'il couvre. */
    static String chunkTag(List<LoanStore.InterestAccrualRow> rows) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            rows.stream()
                .map(row -> row.line().scheduleId() + "#" + row.line().number())
                .sorted()
                .forEach(key -> digest.update(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
