package io.corebanking.deposits;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L'arrete de caisse.
 *
 * <p>Le guichetier compte ses especes ; le systeme confronte le comptage au solde comptable de
 * la caisse. L'ecart n'est jamais ajuste en silence : il est comptabilise sur le compte d'ecarts
 * de la caisse — excedent au credit, manquant au debit — et l'arrete en garde la trace. Une
 * journee de caisse ne s'arrete qu'une fois ; arretee, la caisse ne sert plus ce jour-la.
 */
public final class TillService {

    private static final Logger LOG = LoggerFactory.getLogger(TillService.class);

    public static final String TILL_DIFFERENCE = "TILL_DIFFERENCE";

    private final Database database;
    private final PostingService postingService;

    public TillService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    /** @param counted especes comptees, dans la devise de la caisse */
    public record Closing(UUID tillId, Money counted, UUID actorId) {}

    /** @param entryId ecriture de l'ecart, nulle quand le comptage tombe juste */
    public record Closure(UUID id, UUID tillId, String tillCode, LocalDate businessDate,
                          Money counted, Money book, Money difference, UUID entryId) {}

    public Closure close(Closing command) {
        Objects.requireNonNull(command.tillId(), "tillId");
        Objects.requireNonNull(command.counted(), "counted");
        Objects.requireNonNull(command.actorId(), "actorId");
        return database.inTransaction(c -> {
            Tills.Till till = Tills.require(c, command.tillId());
            LocalDate bookingDate = OperationsService.businessDate(c, till.legalEntityId());
            if (Tills.closedOn(c, till.id(), bookingDate)) {
                throw new Tills.TillClosedException(till.code(), bookingDate);
            }
            Account cash = Accounts.loadAll(c, Set.of(till.cashAccountId()))
                .get(till.cashAccountId());
            if (!command.counted().currency().equals(cash.currency())) {
                throw new IllegalArgumentException(
                    "La caisse " + till.code() + " est tenue en " + cash.currency().code()
                    + ", le comptage est en " + command.counted().currency().code());
            }
            if (command.counted().isNegative()) {
                throw new IllegalArgumentException("Un comptage d'especes n'est pas negatif");
            }

            Money book = Balances.current(c, cash.id());
            Money difference = command.counted().minus(book);
            UUID entryId = null;
            if (!difference.isZero()) {
                if (till.differenceAccountId() == null) {
                    throw new UnjustifiedDifferenceException(till.code(), difference);
                }
                Money amount = difference.abs();
                String narrative = (difference.isPositive() ? "Excedent" : "Manquant")
                    + " de caisse " + till.code() + " du " + bookingDate;
                List<PostingLine> lines = difference.isPositive()
                    ? List.of(PostingLine.debit(cash.id(), amount, bookingDate, narrative),
                              PostingLine.credit(till.differenceAccountId(), amount, bookingDate,
                                                 narrative))
                    : List.of(PostingLine.debit(till.differenceAccountId(), amount, bookingDate,
                                                narrative),
                              PostingLine.credit(cash.id(), amount, bookingDate, narrative));
                PostingResult result = postingService.post(PostingCommand.online(
                    IdempotencyKey.of("TILL_CLOSE|" + till.id() + "|" + bookingDate),
                    till.legalEntityId(), bookingDate, TILL_DIFFERENCE, command.actorId(), lines)
                    .withBranch(till.branchId()));
                entryId = result.entryId();
            }
            UUID id = Tills.recordClosure(c, till.id(), bookingDate, command.counted(), book,
                                          difference, entryId, command.actorId());
            LOG.info("caisse {} arretee pour le {} par {} : compte {}, livre {}, ecart {}",
                     till.code(), bookingDate, command.actorId(), command.counted(), book,
                     difference);
            return new Closure(id, till.id(), till.code(), bookingDate, command.counted(), book,
                               difference, entryId);
        });
    }

    /** Un ecart constate sans compte pour le porter : l'arrete est refuse, l'ecart ne s'ajuste pas. */
    public static class UnjustifiedDifferenceException extends RuntimeException {
        public UnjustifiedDifferenceException(String code, Money difference) {
            super("Ecart de " + difference + " a l'arrete de la caisse " + code
                  + ", et aucun compte d'ecart : un ecart se comptabilise, il ne s'ajuste pas.");
        }
    }
}
