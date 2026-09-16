package io.corebanking.api.usecase;

import io.corebanking.deposits.TermDepositService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.UUID;

/**
 * Depots a terme : la souscription et la rupture se decident a deux ({@code DualControlHandlers}),
 * et les lectures sont tracees a l'echelle de l'entite — un DAT est un engagement de la banque,
 * que la comptabilite et l'audit lisent au meme titre que l'agence qui l'a place.
 */
public final class TermDepositUseCases {

    private TermDepositUseCases() {}

    public record EntityQuery(UUID legalEntityId, String status) {}

    public static final class ReadDeposits
            implements UseCase<EntityQuery, List<TermDepositService.TermDeposit>> {
        private final Database database;

        public ReadDeposits(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.TERM_DEPOSIT_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<TermDepositService.TermDeposit> execute(EntityQuery query) {
            return database.inTransaction(
                c -> TermDepositService.deposits(c, query.legalEntityId(), query.status()));
        }
    }

    public record DepositQuery(UUID legalEntityId, UUID termDepositId) {}

    /** Un contrat et ce que chaque echeance a donne. */
    public record DepositView(TermDepositService.TermDeposit deposit,
                              List<TermDepositService.Payment> payments) {}

    public static final class ReadDeposit implements UseCase<DepositQuery, DepositView> {
        private final Database database;

        public ReadDeposit(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.TERM_DEPOSIT_READ; }

        @Override
        public AccessTarget targetOf(DepositQuery query) {
            return AccessTarget.inEntity(
                require(database, query.legalEntityId(), query.termDepositId()).legalEntityId());
        }

        @Override
        public DepositView execute(DepositQuery query) {
            return database.inTransaction(c -> new DepositView(
                TermDepositService.require(c, query.termDepositId()),
                TermDepositService.payments(c, query.termDepositId())));
        }
    }

    /** Le depot releve de l'entite de l'appelant, ou il n'existe pas. */
    static TermDepositService.TermDeposit require(Database database, UUID legalEntityId,
                                                  UUID termDepositId) {
        TermDepositService.TermDeposit deposit = database.inTransaction(
            c -> TermDepositService.require(c, termDepositId));
        if (!deposit.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Depot a terme inconnu : " + termDepositId);
        }
        return deposit;
    }
}
