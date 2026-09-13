package io.corebanking.security;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UseCaseExecutorTest {

    private static final UUID ENTITE = UUID.randomUUID();
    private static final UUID AGENCE = UUID.randomUUID();

    private final UseCaseExecutor executor =
        new UseCaseExecutor(new AuthorizationService(AuthorizationAudit.none()));

    /** Commande de versement au guichet. */
    private record Versement(UUID entite, UUID agence, Money montant) {}

    private static final class VersementUseCase implements UseCase<Versement, String> {
        private final AtomicBoolean execute = new AtomicBoolean();

        @Override
        public Operation operation() {
            return Operation.CASH_OPERATION;
        }

        @Override
        public AccessTarget targetOf(Versement command) {
            return AccessTarget.inBranch(command.entite(), command.agence())
                               .withAmount(command.montant());
        }

        @Override
        public String execute(Versement command) {
            execute.set(true);
            return "comptabilise";
        }
    }

    private Caller caller(UUID entite, UUID agence, String... roles) {
        return new Caller("s1", "s1@bank", Set.of(roles), entite, agence);
    }

    @Test
    @DisplayName("un cas d'usage autorise s'execute normalement")
    void an_authorized_use_case_runs() {
        var useCase = new VersementUseCase();
        String resultat = executor.run(caller(ENTITE, AGENCE, Roles.TELLER), useCase,
            new Versement(ENTITE, AGENCE, Money.of("500000", XOF)));

        assertThat(resultat).isEqualTo("comptabilise");
        assertThat(useCase.execute).isTrue();
    }

    @Test
    @DisplayName("un refus intervient avant toute execution : aucun effet de bord n'a lieu")
    void a_denial_happens_before_any_side_effect() {
        var useCase = new VersementUseCase();

        assertThatThrownBy(() -> executor.run(caller(ENTITE, AGENCE, Roles.TELLER), useCase,
            new Versement(ENTITE, AGENCE, Money.of("9000000", XOF))))   // au-dela du plafond
            .isInstanceOf(AccessDeniedException.class);

        // Le point essentiel : le cas d'usage n'a pas tourne du tout. Un controle place a
        // l'interieur du service laisserait passer les effets deja produits avant lui.
        assertThat(useCase.execute).isFalse();
    }

    @Test
    @DisplayName("le cloisonnement d'entite est applique meme si le cas d'usage l'ignore")
    void entity_isolation_applies_without_the_use_case_knowing() {
        var useCase = new VersementUseCase();
        UUID autreEntite = UUID.randomUUID();

        assertThatThrownBy(() -> executor.run(caller(ENTITE, AGENCE, Roles.TELLER), useCase,
            new Versement(autreEntite, AGENCE, Money.of("1000", XOF))))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("cloisonnement des entites");

        assertThat(useCase.execute).isFalse();
    }
}
