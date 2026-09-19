package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.StatementLayouts;
import io.corebanking.ledger.store.Statements;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Relecture, essai et fin de vie des maquettes d'etats financiers.
 *
 * <p>Meme diagnostic que les produits, le reseau et les schemas comptables : le socle savait
 * rediger et activer, il ne savait ni retrouver, ni fermer. Avec une consequence propre a ce
 * domaine : <b>on ne pouvait pas eprouver un brouillon</b>. Une maquette s'activait a deux, et
 * c'est en lisant le bilan qu'on decouvrait qu'elle laissait quarante comptes sans rubrique —
 * apres l'avoir transmis au superviseur.
 */
public final class LayoutUseCases {

    private LayoutUseCases() {}

    public record LayoutQuery(UUID legalEntityId, StatementLayouts.Kind kind, String status) {}

    /** Les maquettes de l'entite, brouillons compris. */
    public static final class ListLayouts
            implements UseCase<LayoutQuery, List<StatementLayouts.Summary>> {
        private final Database database;

        public ListLayouts(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.STATEMENT_LAYOUT_READ; }

        @Override
        public AccessTarget targetOf(LayoutQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<StatementLayouts.Summary> execute(LayoutQuery query) {
            return database.inTransaction(c -> StatementLayouts.summaries(
                c, query.legalEntityId(), query.kind(), query.status()));
        }
    }

    public record Trial(UUID legalEntityId, UUID layoutId, LocalDate from, LocalDate to) {}

    /**
     * Essaie une maquette sur le journal, sans rien produire d'officiel.
     *
     * <p>Les controles sont ceux de la production : comptes qu'aucune regle n'affecte, equilibre,
     * resultat anterieur non clos. Aucune indulgence pour un essai — un essai indulgent ne vaut
     * rien, puisque c'est precisement ce qu'on cherche a savoir avant d'activer.
     */
    public static final class PreviewStatement implements UseCase<Trial, Statements.Statement> {
        private final Database database;

        public PreviewStatement(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.STATEMENT_LAYOUT_READ; }

        @Override
        public AccessTarget targetOf(Trial trial) {
            return AccessTarget.inEntity(trial.legalEntityId());
        }

        @Override
        public Statements.Statement execute(Trial trial) {
            return database.inTransaction(c -> {
                StatementLayouts.Layout layout = StatementLayouts.find(c, trial.layoutId())
                    .filter(found -> found.legalEntityId().equals(trial.legalEntityId()))
                    .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                        "Maquette d'etat financier", trial.layoutId()));
                LocalDate to = trial.to() != null ? trial.to()
                    : AccountUseCases.businessDate(c, trial.legalEntityId());
                LocalDate from = trial.from();
                if (layout.kind() == StatementLayouts.Kind.INCOME_STATEMENT && from == null) {
                    // Par defaut, l'exercice en cours : c'est la periode qu'un compte de resultat
                    // presente, et la seule dont la somme veuille dire quelque chose.
                    from = io.corebanking.ledger.store.FiscalYears
                        .covering(c, trial.legalEntityId(), to)
                        .map(io.corebanking.ledger.store.FiscalYears.FiscalYear::start)
                        .orElse(to.withDayOfYear(1));
                }
                return Statements.preview(c, trial.legalEntityId(), layout, from, to);
            });
        }
    }

    public record Withdrawal(UUID legalEntityId, UUID layoutId, UUID actorId) {}

    /** Retire un brouillon abandonne : seul acte du parametrage des etats qui ne soit pas a deux. */
    public static final class WithdrawDraft implements UseCase<Withdrawal, Void> {
        private final Database database;

        public WithdrawDraft(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.STATEMENT_LAYOUT_DRAFT; }

        @Override
        public AccessTarget targetOf(Withdrawal withdrawal) {
            return AccessTarget.inEntity(withdrawal.legalEntityId());
        }

        @Override
        public Void execute(Withdrawal withdrawal) {
            return database.inTransaction(c -> {
                StatementLayouts.withdrawDraft(c, withdrawal.legalEntityId(), withdrawal.layoutId(),
                                               withdrawal.actorId());
                return null;
            });
        }
    }
}
