package io.corebanking.api.usecase;

import io.corebanking.calendar.Calendars;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.UUID;

/**
 * Le referentiel d'exploitation : le reseau d'agences et les conditions de banque.
 *
 * <p>Les deux se posaient a deux et ne se relisaient nulle part. Une agence creee n'apparaissait
 * dans aucun ecran ; un ferie, une regle de date de valeur ou une heure limite ne se verifiaient
 * qu'en interrogeant la base. Celui qui parametrait ajoutait donc une regle sans voir celles qui
 * existaient deja — dont celle qu'il allait contredire.
 */
public final class NetworkUseCases {

    private NetworkUseCases() {}

    public record Query(UUID legalEntityId) {}

    /** Les agences de l'entite, siege compris. */
    public static final class ListBranches implements UseCase<Query, List<Branches.Branch>> {
        private final Database database;

        public ListBranches(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.BRANCH_READ; }

        @Override
        public AccessTarget targetOf(Query query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<Branches.Branch> execute(Query query) {
            return database.inTransaction(c -> Branches.ofEntity(c, query.legalEntityId()));
        }
    }

    /**
     * Les conditions de banque : calendrier, feries, regles de date de valeur, heures limites.
     *
     * <p>Une seule lecture pour les quatre : elles ne se lisent pas separement. Une date de valeur
     * est le produit d'une regle, d'une heure limite et d'un calendrier ; expliquer celle d'un
     * client suppose de les avoir sous les yeux ensemble.
     */
    public static final class ReadCalendar implements UseCase<Query, Calendars.Conditions> {
        private final Database database;

        public ReadCalendar(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.CALENDAR_READ; }

        @Override
        public AccessTarget targetOf(Query query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Calendars.Conditions execute(Query query) {
            return database.inTransaction(c -> Calendars.conditions(c, query.legalEntityId()));
        }
    }
}
