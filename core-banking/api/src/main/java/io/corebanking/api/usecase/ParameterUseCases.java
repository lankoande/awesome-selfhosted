package io.corebanking.api.usecase;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.CollateralPolicy;
import io.corebanking.loan.RiskGrid;
import io.corebanking.loan.service.Collaterals;
import io.corebanking.loan.service.RiskProfiles;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Parametrage du risque et des schemas comptables : la redaction est un acte simple, l'activation
 * se valide a deux ({@code DualControlHandlers}). Ce qui est active decide du niveau de provision
 * de tout le portefeuille, ou de la traduction comptable de toute operation.
 */
public final class ParameterUseCases {

    private ParameterUseCases() {}

    public record CollateralPolicyDraft(UUID legalEntityId, CollateralPolicy policy,
                                        LocalDate validFrom, LocalDate validTo, UUID actorId) {}

    public static final class DraftCollateralPolicy implements UseCase<CollateralPolicyDraft, UUID> {
        private final Database database;

        public DraftCollateralPolicy(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.RISK_PARAMETER_DRAFT; }

        @Override
        public AccessTarget targetOf(CollateralPolicyDraft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(CollateralPolicyDraft draft) {
            requireDate(draft.validFrom());
            return database.inTransaction(c -> Collaterals.createPolicy(c, new Collaterals.PolicyDraft(
                draft.legalEntityId(), draft.policy(), draft.validFrom(), draft.validTo(),
                draft.actorId())));
        }
    }

    public record RiskProfileDraft(UUID legalEntityId, String label, LocalDate validFrom,
                                   LocalDate validTo, RiskGrid grid, UUID actorId) {}

    public static final class DraftRiskProfile implements UseCase<RiskProfileDraft, UUID> {
        private final Database database;

        public DraftRiskProfile(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.RISK_PARAMETER_DRAFT; }

        @Override
        public AccessTarget targetOf(RiskProfileDraft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(RiskProfileDraft draft) {
            requireDate(draft.validFrom());
            return database.inTransaction(c -> RiskProfiles.createDraft(c, new RiskProfiles.Draft(
                draft.legalEntityId(), draft.label(), draft.validFrom(), draft.validTo(),
                draft.grid(), draft.actorId())));
        }
    }

    public record AccountingSchemaDraft(UUID legalEntityId, String code, String label,
                                        String currency, LocalDate validFrom, LocalDate validTo,
                                        AccountingSchema schema, UUID actorId) {}

    public static final class DraftAccountingSchema implements UseCase<AccountingSchemaDraft, UUID> {
        private final Database database;

        public DraftAccountingSchema(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_DRAFT; }

        @Override
        public AccessTarget targetOf(AccountingSchemaDraft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(AccountingSchemaDraft draft) {
            requireDate(draft.validFrom());
            return database.inTransaction(c -> {
                CurrencyRef currency = AccountUseCases.currency(c, draft.currency());
                return SchemaCatalog.createDraft(c, new SchemaCatalog.Draft(
                    draft.legalEntityId(), draft.code(), draft.label(), currency,
                    draft.validFrom(), draft.validTo(), draft.schema(), draft.actorId()));
            });
        }
    }

    private static void requireDate(LocalDate validFrom) {
        if (validFrom == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
        }
    }

    /** Un objet de parametrage inconnu — ou d'une autre entite, que le cloisonnement ne montre pas. */
    public static class UnknownParameterException extends RuntimeException {
        public UnknownParameterException(String what, UUID id) {
            super(what + " inconnu : " + id);
        }
    }
}
