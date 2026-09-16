package io.corebanking.ledger.store;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Revalorisation des positions de change.
 *
 * <p>A chaque arrete, la contre-valeur portee par chaque position est confrontee a ce que la
 * position vaut au cours du jour. L'ecart va au resultat de change : gain latent au credit,
 * perte latente au debit. L'ecriture est en devise de tenue — elle ne touche pas le solde en
 * devise, qui n'a pas bouge : c'est sa valeur qui a bouge, pas sa quantite.
 *
 * <p>L'ecart de l'arrete precedent n'est pas repris : la contre-valeur portee inclut deja les
 * revalorisations passees, et chaque arrete ne comptabilise que le chemin parcouru depuis le
 * precedent. Un arrete annule contre-passe son ecriture, ce qui remet la contre-valeur ou elle
 * etait — et le rejeu recalcule le meme ecart.
 */
public final class FxRevaluation {

    public static final String TRANSACTION_TYPE = "FX_REVALUATION";
    public static final String STEP = "FX_REVALUATION";

    private final Database database;
    private final PostingService postingService;

    public FxRevaluation(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    /**
     * @param delta   l'ecart comptabilise : positif en gain, negatif en perte
     * @param entryId l'ecriture, ou nul quand l'ecart est nul et que rien n'a ete comptabilise
     */
    public record Result(String currency, Money balance, BigDecimal rate, Money carriedValue,
                         Money revaluedValue, Money delta, UUID entryId) {}

    /** Devise cotee sans position declaree, ou position sans cours du jour : ce qui manque. */
    public record Gap(String currency, String reason) {}

    /**
     * Revalorise toutes les positions de l'entite a la date, et rend le detail de chacune.
     *
     * @param runId le traitement qui revalorise ; la cle de chaque ecriture en derive, ce qui
     *              rend l'etape rejouable sans double comptabilisation
     */
    public List<Result> revalue(UUID legalEntityId, LocalDate businessDate, UUID runId,
                                UUID actorId) {
        List<FxPositions.Position> positions = database.inTransaction(
            c -> FxPositions.all(c, legalEntityId));
        List<Result> results = new ArrayList<>(positions.size());
        for (FxPositions.Position position : positions) {
            results.add(revalueOne(position, businessDate, runId, actorId));
        }
        return results;
    }

    private Result revalueOne(FxPositions.Position position, LocalDate businessDate, UUID runId,
                              UUID actorId) {
        return database.inTransaction(c -> {
            CurrencyRef functional = Entities.functionalCurrency(c, position.legalEntityId());
            FxRates.Rate rate = FxRates.quotedOn(c, position.legalEntityId(), position.currency(),
                                                 businessDate)
                .orElseThrow(() -> new LedgerStoreException(
                    "Aucun cours du " + businessDate + " en " + position.currency()
                    + " : la position ne se revalorise pas au cours d'un autre jour."));
            Money balance = Balances.current(c, position.positionAccountId());
            Money carried = Balances.current(c, position.counterValueAccountId());
            Money revalued = FxPositions.revaluedValue(balance, rate.rate(), functional);
            Money delta = revalued.minus(carried);
            if (delta.isZero()) {
                return new Result(position.currency(), balance, rate.rate(), carried, revalued,
                                  delta, null);
            }
            Money amount = delta.abs();
            String narrative = "Revalorisation de la position " + position.currency() + " au cours "
                + rate.rate().stripTrailingZeros().toPlainString() + " du " + rate.quotedOn();
            // Ecart positif : la position vaut plus que ce qu'elle a coute — la contre-valeur
            // monte au debit, le gain va au credit. Ecart negatif : l'inverse.
            List<PostingLine> lines = delta.isPositive()
                ? List.of(PostingLine.debit(position.counterValueAccountId(), amount, businessDate,
                                            narrative),
                          PostingLine.credit(position.gainAccountId(), amount, businessDate,
                                             narrative))
                : List.of(PostingLine.debit(position.lossAccountId(), amount, businessDate,
                                            narrative),
                          PostingLine.credit(position.counterValueAccountId(), amount, businessDate,
                                             narrative));
            UUID headOffice = Branches.headOffice(c, position.legalEntityId());
            UUID entryId = postingService.post(PostingCommand.batch(
                IdempotencyKey.forBatch(runId.toString(), STEP, position.id()),
                position.legalEntityId(), businessDate, TRANSACTION_TYPE, actorId, runId, lines)
                .withBranch(headOffice)).entryId();
            return new Result(position.currency(), balance, rate.rate(), carried, revalued, delta,
                              entryId);
        });
    }

    /**
     * Ce qui manque pour revaloriser : une position sans cours du jour, une devise detenue sans
     * position declaree. Les deux arretent la journee — la premiere parce que la revalorisation
     * ne se fait pas au cours de la veille, la seconde parce qu'une exposition non declaree n'est
     * revalorisee par personne.
     */
    public static List<Gap> gaps(java.sql.Connection c, UUID legalEntityId, LocalDate businessDate) {
        List<Gap> gaps = new ArrayList<>();
        CurrencyRef functional = Entities.functionalCurrency(c, legalEntityId);
        for (FxPositions.Position position : FxPositions.all(c, legalEntityId)) {
            Optional<FxRates.Rate> rate = FxRates.quotedOn(c, legalEntityId, position.currency(),
                                                            businessDate);
            if (rate.isEmpty()) {
                gaps.add(new Gap(position.currency(), "aucun cours cote au " + businessDate
                                 + " : la position ne se revalorise pas au cours d'un autre jour"));
            }
        }
        for (String held : Entities.currenciesHeld(c, legalEntityId)) {
            if (!held.equals(functional.code())
                && FxPositions.find(c, legalEntityId, held).isEmpty()) {
                gaps.add(new Gap(held, "devise detenue sans position de change declaree : "
                                 + "l'exposition n'est mesuree ni revalorisee par personne"));
            }
        }
        return gaps;
    }
}
