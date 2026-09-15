package io.corebanking.security;

import static io.corebanking.kernel.money.Currencies.EUR;
import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {

    private static final UUID ENTITE_A = UUID.randomUUID();
    private static final UUID ENTITE_B = UUID.randomUUID();
    private static final UUID AGENCE_1 = UUID.randomUUID();
    private static final UUID AGENCE_2 = UUID.randomUUID();

    private final List<AccessDecision> traces = new ArrayList<>();
    private final AuthorizationService service =
        new AuthorizationService((caller, operation, target, decision) -> traces.add(decision));

    private Caller caller(String subject, UUID entity, UUID branch, String... roles) {
        return new Caller(subject, subject + "@bank", Set.of(roles), entity, branch);
    }

    @Test
    @DisplayName("toute operation du catalogue porte une regle, sans quoi la classe ne charge pas")
    void the_policy_is_exhaustive() {
        for (Operation operation : Operation.values()) {
            assertThat(SecurityConfig.policy())
                .as("operation %s", operation)
                .containsKey(operation);
        }
    }

    @Test
    @DisplayName("un appelant sans role est refuse sur toutes les operations")
    void a_caller_without_roles_is_denied_everything() {
        Caller anonyme = caller("anon", ENTITE_A, AGENCE_1);
        for (Operation operation : Operation.values()) {
            AccessDecision decision = service.decide(anonyme, operation,
                AccessTarget.inBranch(ENTITE_A, AGENCE_1));
            assertThat(decision.allowed()).as("operation %s", operation).isFalse();
            assertThat(decision.reason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("le cloisonnement des entites prime sur le role")
    void entity_isolation_beats_role() {
        Caller directeur = caller("dir", ENTITE_A, AGENCE_1, Roles.BRANCH_MANAGER);

        AccessDecision decision = service.decide(directeur, Operation.ACCOUNT_OPEN,
            AccessTarget.inBranch(ENTITE_B, AGENCE_1));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("cloisonnement des entites");
    }

    @Test
    @DisplayName("un guichetier ne voit pas les comptes d'une autre agence")
    void branch_scope_is_enforced() {
        Caller guichetier = caller("t1", ENTITE_A, AGENCE_1, Roles.TELLER);

        assertThat(service.decide(guichetier, Operation.ACCOUNT_BALANCE_READ,
            AccessTarget.inBranch(ENTITE_A, AGENCE_1)).allowed()).isTrue();

        AccessDecision refus = service.decide(guichetier, Operation.ACCOUNT_BALANCE_READ,
            AccessTarget.inBranch(ENTITE_A, AGENCE_2));
        assertThat(refus.allowed()).isFalse();
        assertThat(refus.reason()).contains("agence");
    }

    @Test
    @DisplayName("le plafond applique est le plus favorable des roles de l'appelant")
    void the_highest_ceiling_of_the_caller_applies() {
        Caller guichetier = caller("t1", ENTITE_A, AGENCE_1, Roles.TELLER);
        Caller directeur = caller("d1", ENTITE_A, AGENCE_1, Roles.TELLER, Roles.BRANCH_MANAGER);

        AccessTarget cinqMillions = AccessTarget.inBranch(ENTITE_A, AGENCE_1)
            .withAmount(Money.of("5000000", XOF));

        // Le guichetier est plafonne a 2 000 000.
        assertThat(service.decide(guichetier, Operation.CASH_OPERATION, cinqMillions).allowed())
            .isFalse();
        // Le directeur, qui cumule les deux roles, beneficie du plafond le plus eleve.
        assertThat(service.decide(directeur, Operation.CASH_OPERATION, cinqMillions).allowed())
            .isTrue();
    }

    @Test
    @DisplayName("un montant dans une devise sans plafond defini est refuse, pas autorise")
    void an_amount_in_an_uncapped_currency_is_denied() {
        Caller guichetier = caller("t1", ENTITE_A, AGENCE_1, Roles.TELLER);

        AccessDecision decision = service.decide(guichetier, Operation.CASH_OPERATION,
            AccessTarget.inBranch(ENTITE_A, AGENCE_1).withAmount(Money.of("10.00", EUR)));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("aucun plafond defini en EUR");
    }

    @Test
    @DisplayName("l'auteur d'une operation ne peut pas la valider lui-meme")
    void maker_cannot_be_checker() {
        Caller comptable = caller("acc1", ENTITE_A, null, Roles.ACCOUNTANT);

        assertThat(service.decide(comptable, Operation.ENTRY_REVERSAL,
            AccessTarget.inEntity(ENTITE_A).madeBy("acc2")).allowed()).isTrue();

        AccessDecision refus = service.decide(comptable, Operation.ENTRY_REVERSAL,
            AccessTarget.inEntity(ENTITE_A).madeBy("acc1"));
        assertThat(refus.allowed()).isFalse();
        assertThat(refus.reason()).contains("separation des taches");
    }

    @Test
    @DisplayName("un guichetier n'arrete que sa caisse ; le chef d'agence arrete toute caisse de son agence")
    void own_only_roles_act_on_their_own_objects() {
        UUID agence = UUID.randomUUID();
        Caller guichetier = caller("t1", ENTITE_A, agence, Roles.TELLER);
        Caller chef = caller("m1", ENTITE_A, agence, Roles.BRANCH_MANAGER);
        AccessTarget saCaisse = AccessTarget.inBranch(ENTITE_A, agence).ownedBy("t1");
        AccessTarget autreCaisse = AccessTarget.inBranch(ENTITE_A, agence).ownedBy("t2");
        AccessTarget sansTitulaire = AccessTarget.inBranch(ENTITE_A, agence);

        assertThat(service.decide(guichetier, Operation.TILL_CLOSE, saCaisse).allowed()).isTrue();
        AccessDecision refus = service.decide(guichetier, Operation.TILL_CLOSE, autreCaisse);
        assertThat(refus.allowed()).isFalse();
        assertThat(refus.reason()).contains("objet propre");
        assertThat(service.decide(guichetier, Operation.TILL_CLOSE, sansTitulaire).allowed())
            .isFalse();
        assertThat(service.decide(chef, Operation.TILL_CLOSE, autreCaisse).allowed()).isTrue();
        assertThat(service.decide(chef, Operation.TILL_CLOSE, sansTitulaire).allowed()).isTrue();
    }

    @Test
    @DisplayName("l'auditeur est le seul profil transverse aux entites")
    void only_the_auditor_crosses_entities() {
        Caller auditeur = caller("aud", ENTITE_A, null, Roles.AUDITOR);
        assertThat(service.decide(auditeur, Operation.AUDIT_READ,
            AccessTarget.inEntity(ENTITE_B)).allowed()).isTrue();

        // Et cette transversalite ne s'etend a aucune autre operation.
        assertThat(service.decide(auditeur, Operation.ACCOUNT_JOURNAL_READ,
            AccessTarget.inEntity(ENTITE_B)).allowed()).isFalse();
    }

    @Test
    @DisplayName("les refus sont toujours traces, les consultations reussies aussi")
    void denials_and_successful_reads_are_both_traced() {
        Caller guichetier = caller("t1", ENTITE_A, AGENCE_1, Roles.TELLER);

        // Consultation autorisee : tracee, car la consultation abusive est invisible autrement.
        service.require(guichetier, Operation.ACCOUNT_BALANCE_READ,
            AccessTarget.inBranch(ENTITE_A, AGENCE_1));
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).allowed()).isTrue();

        // Operation autorisee non sensible en lecture : pas de trace d'habilitation.
        service.require(guichetier, Operation.CASH_OPERATION,
            AccessTarget.inBranch(ENTITE_A, AGENCE_1).withAmount(Money.of("100000", XOF)));
        assertThat(traces).hasSize(1);

        // Refus : trace, toujours.
        assertThatThrownBy(() -> service.require(guichetier, Operation.TFJ_CANCEL,
            AccessTarget.inEntity(ENTITE_A))).isInstanceOf(AccessDeniedException.class);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(1).allowed()).isFalse();
    }

    @Test
    @DisplayName("la matrice des habilitations s'imprime depuis la politique reellement appliquee")
    void the_matrix_is_generated_from_the_applied_policy() {
        String matrice = SecurityConfig.describe();

        for (Operation operation : Operation.values()) {
            assertThat(matrice).contains(operation.name());
        }
        assertThat(matrice).contains("Double validation");
    }

    @Test
    @DisplayName("un jeton sans entite juridique est refuse, pas interprete comme un acces global")
    void a_token_without_legal_entity_is_rejected() {
        assertThatThrownBy(() -> new Caller("x", "x@bank", Set.of(Roles.AUDITOR), null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("aucune identite n'est valable hors d'une entite juridique");
    }

    @Test
    @DisplayName("une operation deplacee passe sous son propre plafond, plus bas que l'ordinaire")
    void a_remote_operation_has_its_own_lower_ceiling() {
        Caller guichetier = caller("g", ENTITE_A, AGENCE_1, Roles.TELLER);
        AccessTarget aSaCaisse = AccessTarget.inBranch(ENTITE_A, AGENCE_1)
            .withAmount(Money.of("1500000", XOF));

        assertThat(service.decide(guichetier, Operation.CASH_OPERATION, aSaCaisse).allowed())
            .isTrue();
        AccessDecision deplace = service.decide(guichetier, Operation.CASH_OPERATION,
                                                aSaCaisse.performedRemotely());
        assertThat(deplace.allowed()).isFalse();
        assertThat(deplace.reason()).contains("deplacees");
        assertThat(service.decide(guichetier, Operation.CASH_OPERATION,
                                  AccessTarget.inBranch(ENTITE_A, AGENCE_1)
                                      .withAmount(Money.of("400000", XOF)).performedRemotely())
                       .allowed()).isTrue();
    }

    @Test
    @DisplayName("une operation deplacee n'est admise que si la regle la prevoit")
    void a_remote_operation_needs_to_be_provided_for() {
        Caller chef = caller("chef", ENTITE_A, AGENCE_1, Roles.BRANCH_MANAGER);

        // La cloture d'un compte revient a l'agence qui en repond : deplacee, refusee.
        AccessDecision cloture = service.decide(chef, Operation.ACCOUNT_CLOSE,
            AccessTarget.inBranch(ENTITE_A, AGENCE_2).performedRemotely());
        assertThat(cloture.allowed()).isFalse();
        assertThat(cloture.reason()).contains("deplacee");

        // La lecture d'un solde d'une autre agence est admise deplacee, et tracee.
        AccessDecision lecture = service.decide(chef, Operation.ACCOUNT_BALANCE_READ,
            AccessTarget.inBranch(ENTITE_A, AGENCE_2).performedRemotely());
        assertThat(lecture.allowed()).isTrue();
        // ... mais pas sans le dire : le meme objet, sans l'indicateur, reste hors perimetre.
        assertThat(service.decide(chef, Operation.ACCOUNT_BALANCE_READ,
                                  AccessTarget.inBranch(ENTITE_A, AGENCE_2)).allowed()).isFalse();
    }
}
