package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.Numbering;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L'etablissement et son plan de numerotation.
 *
 * <p>Deux lectures de sensibilite differente, et c'est voulu. L'identite de l'etablissement figure
 * en en-tete de chaque releve : un guichetier la voit tous les jours. Le plan de numerotation dit
 * comment se composent les numeros de comptes — le lire, c'est savoir deviner ceux des autres —,
 * et il ne se montre pas au guichet.
 */
public final class EstablishmentUseCases {

    private EstablishmentUseCases() {}

    /** L'identite de l'etablissement. */
    public static final class ReadEstablishment
            implements UseCase<UUID, Entities.Establishment> {
        private final Database database;

        public ReadEstablishment(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.ESTABLISHMENT_READ; }

        @Override
        public AccessTarget targetOf(UUID legalEntityId) {
            return AccessTarget.inEntity(legalEntityId);
        }

        @Override
        public Entities.Establishment execute(UUID legalEntityId) {
            return database.inTransaction(c -> Entities.establishment(c, legalEntityId));
        }
    }

    /** Ce qu'on demande au plan de numerotation : toutes les regles, ou celles d'un domaine. */
    public record RuleQuery(UUID legalEntityId, Numbering.Domain domain) {}

    /** Les regles de numerotation, brouillons et retirees comprises. */
    public static final class ReadNumberingRules
            implements UseCase<RuleQuery, List<Numbering.Rule>> {
        private final Database database;

        public ReadNumberingRules(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.NUMBERING_READ; }

        @Override
        public AccessTarget targetOf(RuleQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<Numbering.Rule> execute(RuleQuery query) {
            return database.inTransaction(c -> Numbering.list(c, query.legalEntityId(),
                                                              query.domain()));
        }
    }

    /** Ce que la regle composerait maintenant, sans consommer le compteur. */
    public record PreviewQuery(UUID legalEntityId, UUID ruleId, UUID branchId, LocalDate on) {}

    /**
     * Le prochain numero, en blanc.
     *
     * <p>Un ecran qui montrerait un gabarit sans montrer le numero qu'il produit demanderait a son
     * lecteur de faire le calcul de tete — et la cle de controle ne se calcule pas de tete.
     */
    public static final class PreviewNumber implements UseCase<PreviewQuery, String> {
        private final Database database;

        public PreviewNumber(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.NUMBERING_READ; }

        @Override
        public AccessTarget targetOf(PreviewQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public String execute(PreviewQuery query) {
            return database.inTransaction(c -> Numbering.preview(
                c, query.legalEntityId(), query.ruleId(), query.branchId(),
                query.on() == null ? Entities.establishment(c, query.legalEntityId())
                                         .businessDate()
                                   : query.on()));
        }
    }

    /** Redaction d'une regle : elle ne numerote rien tant qu'une seconde main ne l'active pas. */
    public static final class DraftNumberingRule implements UseCase<Numbering.Draft, UUID> {
        private final Database database;

        public DraftNumberingRule(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.NUMBERING_DRAFT; }

        @Override
        public AccessTarget targetOf(Numbering.Draft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(Numbering.Draft draft) {
            return database.inTransaction(c -> Numbering.draft(c, draft));
        }
    }
}
