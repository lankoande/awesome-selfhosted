package io.corebanking.api.usecase;

import io.corebanking.interest.rate.Tier;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductFamilies;
import io.corebanking.product.ProductFamily;
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

    /**
     * Ce qui est ouvrable a une date, dans une entite.
     *
     * @param on la date a laquelle la validite s'apprecie ; le jour meme par defaut. Elle se
     *           choisit pour preparer une ouverture a venir, pas pour reecrire le passe.
     */
    public record Catalogue(UUID legalEntityId, LocalDate on) {}

    /** Lecture du catalogue produit : le prealable a toute ouverture de compte. */
    public static final class ListOpenable
            implements UseCase<Catalogue, List<ProductCatalog.Openable>> {
        private final Database database;

        public ListOpenable(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PRODUCT_READ; }

        @Override
        public AccessTarget targetOf(Catalogue query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<ProductCatalog.Openable> execute(Catalogue query) {
            LocalDate on = query.on() == null ? LocalDate.now() : query.on();
            return database.inTransaction(c ->
                ProductCatalog.openable(c, query.legalEntityId(), on));
        }
    }

    // ------------------------------------------------------------------ lecture du parametrage

    /**
     * Le catalogue des familles de produit.
     *
     * <p>Une famille declare ce qu'un type de produit exige, admet, et ce qu'un parametre rend
     * obligatoire. Le servir permet a un poste de parametrage de construire sa saisie <b>a partir
     * du contrat</b> plutot que d'une copie de ces regles. Deux copies divergent : l'ecran
     * proposerait un parametre que l'activation refuse, ou tairait celui qu'elle exige.
     */
    public record Catalogues(UUID legalEntityId) {}

    public static final class ReadFamilies implements UseCase<Catalogues, List<ProductFamily>> {

        @Override public Operation operation() { return Operation.PRODUCT_READ; }

        @Override
        public AccessTarget targetOf(Catalogues query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<ProductFamily> execute(Catalogues query) {
            return ProductFamilies.all();
        }
    }

    /** Les versions d'une entite, brouillons compris ; filtrees par code et par etat. */
    public record VersionQuery(UUID legalEntityId, String code, String status) {}

    public static final class ListVersions
            implements UseCase<VersionQuery, List<ProductCatalog.VersionSummary>> {
        private final Database database;

        public ListVersions(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PRODUCT_READ; }

        @Override
        public AccessTarget targetOf(VersionQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<ProductCatalog.VersionSummary> execute(VersionQuery query) {
            return database.inTransaction(c ->
                ProductCatalog.versions(c, query.legalEntityId(), blankToNull(query.code()),
                                        blankToNull(query.status())));
        }
    }

    public record VersionLookup(UUID legalEntityId, UUID versionId) {}

    public static final class ReadVersion implements UseCase<VersionLookup, ProductCatalog.Version> {
        private final Database database;

        public ReadVersion(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PRODUCT_READ; }

        @Override
        public AccessTarget targetOf(VersionLookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        /** Une version d'une autre entite n'existe pas pour l'appelant : le filtre est dans la requete. */
        @Override
        public ProductCatalog.Version execute(VersionLookup query) {
            return database
                .inTransaction(c -> ProductCatalog.version(c, query.legalEntityId(),
                                                           query.versionId()))
                .orElseThrow(() -> new UnknownProductVersionException(query.versionId()));
        }
    }

    // ------------------------------------------------------------------ redaction

    public record Draft(UUID legalEntityId, String code, String productType, String label,
                        String currency, LocalDate validFrom, LocalDate validTo,
                        Map<String, String> parameters, List<Tier> tiers,
                        Map<String, List<Tier>> feeTiers, UUID actorId) {}

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
                draft.tiers() == null ? List.of() : draft.tiers(),
                draft.feeTiers() == null ? Map.of() : draft.feeTiers(), draft.actorId())));
        }
    }

    /** Ce que rend une activation, ou une fermeture. */
    public record Activation(UUID versionId, String code, String status) {}

    /**
     * Retrait d'un brouillon abandonne.
     *
     * <p>Seul acte du parametrage produit qui ne se fasse pas a deux : un brouillon n'engage rien,
     * aucun compte ne le cite, aucun arrete ne le resout. Exiger un second regard pour ranger sa
     * propre table de travail apprendrait surtout a ne plus ranger.
     */
    public record Withdrawal(UUID legalEntityId, UUID versionId, UUID actorId) {}

    public static final class WithdrawDraft implements UseCase<Withdrawal, Activation> {
        private final Database database;

        public WithdrawDraft(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PRODUCT_DRAFT; }

        @Override
        public AccessTarget targetOf(Withdrawal withdrawal) {
            return AccessTarget.inEntity(withdrawal.legalEntityId());
        }

        @Override
        public Activation execute(Withdrawal withdrawal) {
            ProductCatalog.VersionHeader header = requireVersion(
                database, withdrawal.legalEntityId(), withdrawal.versionId());
            database.inTransaction(c -> {
                ProductCatalog.withdrawDraft(c, withdrawal.legalEntityId(), withdrawal.versionId(),
                                             withdrawal.actorId());
                return null;
            });
            return new Activation(header.id(), header.code(), "WITHDRAWN");
        }
    }

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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + field);
        }
    }
}
