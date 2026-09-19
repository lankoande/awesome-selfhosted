package io.corebanking.api.usecase;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaSimulator;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relecture, essai et fin de vie des schemas comptables.
 *
 * <p>Le schema comptable etait le parametrage le plus critique du socle — il decide de la
 * traduction de chaque evenement en ecritures — et le seul qui ne se relisait pas. On pouvait le
 * rediger et l'activer ; on ne pouvait ni le retrouver, ni le lire, ni en fermer la validite, ni
 * savoir ce qu'il produirait.
 */
public final class SchemaUseCases {

    private SchemaUseCases() {}

    // ------------------------------------------------------------------ le catalogue du socle

    public record CatalogueQuery(UUID legalEntityId, String currency) {}

    /**
     * Ce que le socle impute, evenement par evenement, et ce qui se parametre.
     *
     * <p>Sans cette lecture, celui qui redigeait un schema ne savait ni quels evenements existent,
     * ni quelles grandeurs ils portent, ni quels comptes ils designent par role — et surtout pas
     * lequel serait effectivement resolu. Il ecrivait de memoire.
     */
    public static final class ReadStandard
            implements UseCase<CatalogueQuery, List<StandardSchemas.Event>> {
        private final Database database;

        public ReadStandard(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_READ; }

        @Override
        public AccessTarget targetOf(CatalogueQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<StandardSchemas.Event> execute(CatalogueQuery query) {
            return database.inTransaction(c ->
                StandardSchemas.all(currencyOf(c, query.legalEntityId(), query.currency())));
        }
    }

    // ------------------------------------------------------------------ les schemas parametres

    public record SchemaQuery(UUID legalEntityId, String code, String status) {}

    /** Les schemas de l'entite, brouillons compris. */
    public static final class ListSchemas
            implements UseCase<SchemaQuery, List<SchemaCatalog.Summary>> {
        private final Database database;

        public ListSchemas(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_READ; }

        @Override
        public AccessTarget targetOf(SchemaQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<SchemaCatalog.Summary> execute(SchemaQuery query) {
            return database.inTransaction(c -> SchemaCatalog.summaries(
                c, query.legalEntityId(), query.code(), query.status()));
        }
    }

    public record SchemaLookup(UUID legalEntityId, UUID schemaId) {}

    /** Un schema en entier : en-tete, derivations dans l'ordre, lignes, variables attendues. */
    public static final class ReadSchema implements UseCase<SchemaLookup, SchemaCatalog.Detail> {
        private final Database database;

        public ReadSchema(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_READ; }

        @Override
        public AccessTarget targetOf(SchemaLookup lookup) {
            return AccessTarget.inEntity(lookup.legalEntityId());
        }

        @Override
        public SchemaCatalog.Detail execute(SchemaLookup lookup) {
            return database.inTransaction(c ->
                SchemaCatalog.detail(c, lookup.legalEntityId(), lookup.schemaId())
                    .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                        "Schema comptable", lookup.schemaId())));
        }
    }

    // ------------------------------------------------------------------ l'essai

    /**
     * Un essai : un schema — redige ou enregistre —, un evenement, un jeu de valeurs.
     *
     * @param schema  schema en cours de redaction, essaye avant d'etre enregistre
     * @param schemaId schema deja enregistre, essaye tel qu'il est en base
     */
    public record Trial(UUID legalEntityId, AccountingSchema schema, UUID schemaId,
                        String eventType, String currency, Map<String, BigDecimal> values) {}

    /**
     * Essaie un schema sur un cas, sans rien imputer.
     *
     * <p>C'est la seule facon de repondre a la question que se pose celui qui redige : <i>pour
     * cette operation-la, quelles lignes cela produit-il ?</i> Personne ne lit
     * {@code round(net, 0) + round(tax, 0)} et n'en deduit l'ecriture.
     *
     * <p>L'essai porte aussi bien sur un schema en cours de saisie que sur un schema enregistre :
     * le premier usage sert a mettre au point, le second a comprendre une imputation passee.
     */
    public static final class Simulate implements UseCase<Trial, SchemaSimulator.Outcome> {
        private final Database database;

        public Simulate(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_READ; }

        @Override
        public AccessTarget targetOf(Trial trial) {
            return AccessTarget.inEntity(trial.legalEntityId());
        }

        @Override
        public SchemaSimulator.Outcome execute(Trial trial) {
            if (trial.eventType() == null || trial.eventType().isBlank()) {
                throw new IllegalArgumentException("Essai sans type d'evenement");
            }
            return database.inTransaction(c -> {
                CurrencyRef currency = currencyOf(c, trial.legalEntityId(), trial.currency());
                EventTemplate template = templateOf(c, trial, currency);
                return SchemaSimulator.run(template, currency,
                                           trial.values() == null ? Map.of() : trial.values());
            });
        }

        private EventTemplate templateOf(java.sql.Connection c, Trial trial, CurrencyRef currency) {
            if (trial.schema() != null) {
                return trial.schema().requireTemplate(trial.eventType());
            }
            if (trial.schemaId() != null) {
                SchemaCatalog.Summary header =
                    SchemaCatalog.detail(c, trial.legalEntityId(), trial.schemaId())
                        .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                            "Schema comptable", trial.schemaId()))
                        .header();
                return SchemaCatalog.resolveAt(c, trial.legalEntityId(), header.code(),
                                               header.validFrom())
                    .requireTemplate(trial.eventType());
            }
            // Ni schema redige ni schema enregistre : c'est un schema du socle qu'on veut essayer.
            EventTemplate standard = StandardSchemas.templates(currency).get(trial.eventType());
            if (standard == null) {
                throw new IllegalArgumentException(
                    "Essai sans schema : l'evenement « " + trial.eventType() + " » n'est traduit "
                    + "par aucun schema du socle. Fournissez un schema ou son identifiant.");
            }
            return standard;
        }
    }

    // ------------------------------------------------------------------ fin de vie

    public record Withdrawal(UUID legalEntityId, UUID schemaId, UUID actorId) {}

    /** Retire un brouillon abandonne : seul acte du parametrage comptable qui ne soit pas a deux. */
    public static final class WithdrawDraft implements UseCase<Withdrawal, Void> {
        private final Database database;

        public WithdrawDraft(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_DRAFT; }

        @Override
        public AccessTarget targetOf(Withdrawal withdrawal) {
            return AccessTarget.inEntity(withdrawal.legalEntityId());
        }

        @Override
        public Void execute(Withdrawal withdrawal) {
            return database.inTransaction(c -> {
                SchemaCatalog.withdrawDraft(c, withdrawal.legalEntityId(), withdrawal.schemaId(),
                                            withdrawal.actorId());
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ interne

    /** La devise demandee, ou celle de tenue de compte de l'entite. */
    static CurrencyRef currencyOf(java.sql.Connection c, UUID legalEntityId, String code) {
        return code == null || code.isBlank()
            ? Entities.functionalCurrency(c, legalEntityId)
            : AccountUseCases.currency(c, code);
    }
}
