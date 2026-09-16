package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.party.BeneficialOwners;
import io.corebanking.party.KycPolicies;
import io.corebanking.party.Parties;
import io.corebanking.party.Party;
import io.corebanking.party.PartyDocuments;
import io.corebanking.party.PartyFile;
import io.corebanking.party.Relationships;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dossier client : pieces, relations, beneficiaires effectifs, politique de diligence et
 * completude. Le depot d'une piece est un acte d'agence ; relations et beneficiaires se
 * declarent a deux ({@code DualControlHandlers}) ; les lectures relevent de {@code PARTY_READ}.
 */
public final class PartyFileUseCases {

    private PartyFileUseCases() {}

    public static final class DepositDocument
            implements UseCase<PartyDocuments.Deposit, PartyDocuments.Document> {
        private final Database database;

        public DepositDocument(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PARTY_DOCUMENT; }

        @Override
        public AccessTarget targetOf(PartyDocuments.Deposit deposit) {
            return AccessTarget.inEntity(deposit.legalEntityId());
        }

        @Override
        public PartyDocuments.Document execute(PartyDocuments.Deposit deposit) {
            return database.inTransaction(c -> PartyDocuments.deposit(c, deposit));
        }
    }

    public record PartyQuery(UUID legalEntityId, UUID partyId) {}

    /** Base des lectures du dossier : le tiers appartient a l'entite, ou il n'existe pas. */
    private abstract static class Read<T> implements UseCase<PartyQuery, T> {
        protected final Database database;

        Read(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PARTY_READ; }

        @Override
        public AccessTarget targetOf(PartyQuery query) {
            return AccessTarget.inEntity(require(query).legalEntityId());
        }

        protected Party require(PartyQuery query) {
            Party party = database.inTransaction(c -> Parties.require(c, query.partyId()));
            if (!party.legalEntityId().equals(query.legalEntityId())) {
                throw new IllegalArgumentException("Tiers inconnu : " + query.partyId());
            }
            return party;
        }
    }

    public static final class ReadDocuments extends Read<List<PartyDocuments.Document>> {
        public ReadDocuments(Database database) {
            super(database);
        }

        @Override
        public List<PartyDocuments.Document> execute(PartyQuery query) {
            return database.inTransaction(c -> PartyDocuments.of(c, query.partyId(), true));
        }
    }

    public static final class ReadRelationships extends Read<List<Relationships.Relationship>> {
        public ReadRelationships(Database database) {
            super(database);
        }

        @Override
        public List<Relationships.Relationship> execute(PartyQuery query) {
            return database.inTransaction(c -> Relationships.of(c, query.partyId()));
        }
    }

    public static final class ReadOwners extends Read<List<BeneficialOwners.Owner>> {
        public ReadOwners(Database database) {
            super(database);
        }

        @Override
        public List<BeneficialOwners.Owner> execute(PartyQuery query) {
            return database.inTransaction(c -> BeneficialOwners.current(c, query.partyId()));
        }
    }

    /** La completude du dossier a la date comptable : ce qui manque, et ce qui a expire. */
    public static final class ReadCompleteness extends Read<PartyFile.Completeness> {
        public ReadCompleteness(Database database) {
            super(database);
        }

        @Override
        public PartyFile.Completeness execute(PartyQuery query) {
            return database.inTransaction(c -> PartyFile.completeness(
                c, query.partyId(), AccountUseCases.businessDate(c, query.legalEntityId())));
        }
    }

    public record EntityQuery(UUID legalEntityId) {}

    /**
     * La politique declaree se lit par tous ceux qui montent un dossier : un guichetier doit
     * savoir quelles pieces reclamer. La declarer, elle, reste une decision de la conformite.
     */
    public static final class ReadPolicies
            implements UseCase<EntityQuery, List<KycPolicies.Policy>> {
        private final Database database;

        public ReadPolicies(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PARTY_FILE_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<KycPolicies.Policy> execute(EntityQuery query) {
            return database.inTransaction(c -> KycPolicies.all(c, query.legalEntityId()));
        }
    }

    /** Les dossiers incomplets de l'entite : la liste de travail de la conformite. */
    public static final class ReadIncompleteFiles
            implements UseCase<EntityQuery, List<PartyFile.Completeness>> {
        private final Database database;

        public ReadIncompleteFiles(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PARTY_FILE_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<PartyFile.Completeness> execute(EntityQuery query) {
            return database.inTransaction(c -> PartyFile.incomplete(
                c, query.legalEntityId(), AccountUseCases.businessDate(c, query.legalEntityId())));
        }
    }

    static LocalDate today(Database database, UUID legalEntityId) {
        return database.inTransaction(c -> AccountUseCases.businessDate(c, legalEntityId));
    }
}
