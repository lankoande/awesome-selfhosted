package io.corebanking.api.usecase;

import io.corebanking.interest.rate.Tier;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.ProductCatalog;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parametrage produit : la redaction d'une version est un acte simple, son activation se valide a
 * deux ({@code DualControlHandlers}) — un parametrage produit des montants sur des comptes
 * clients.
 */
public final class ProductUseCases {

    private ProductUseCases() {}

    public record Draft(UUID legalEntityId, String code, String productType, String label,
                        String currency, LocalDate validFrom, LocalDate validTo,
                        Map<String, String> parameters, List<Tier> tiers, UUID actorId) {}

    public static final class CreateDraft implements UseCase<Draft, UUID> {
        private final Database database;

        public CreateDraft(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PRODUCT_DRAFT; }

        @Override
        public AccessTarget targetOf(Draft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(Draft draft) {
            requireText(draft.code(), "code");
            requireText(draft.productType(), "productType");
            requireText(draft.label(), "label");
            requireText(draft.currency(), "currency");
            if (draft.validFrom() == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            return database.inTransaction(c -> ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                draft.legalEntityId(), draft.code(), draft.productType(), draft.label(),
                draft.currency(), draft.validFrom(), draft.validTo(),
                draft.parameters() == null ? Map.of() : draft.parameters(),
                draft.tiers() == null ? List.of() : draft.tiers(), draft.actorId())));
        }
    }

    /** Ce que rend une activation. */
    public record Activation(UUID versionId, String code, String status) {}

    /**
     * La version, dans l'entite attendue, ou une erreur nommee : une version d'une autre entite
     * n'existe pas pour l'appelant.
     */
    public static ProductCatalog.VersionHeader requireVersion(Database database, UUID legalEntityId,
                                                              UUID versionId) {
        return database.inTransaction(c -> ProductCatalog.findVersion(c, versionId))
            .filter(header -> header.legalEntityId().equals(legalEntityId))
            .orElseThrow(() -> new UnknownProductVersionException(versionId));
    }

    public static class UnknownProductVersionException extends RuntimeException {
        public UnknownProductVersionException(UUID versionId) {
            super("Version de produit inconnue : " + versionId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + field);
        }
    }
}
