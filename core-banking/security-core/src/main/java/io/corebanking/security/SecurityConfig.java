package io.corebanking.security;

import static io.corebanking.security.Roles.ACCOUNTANT;
import static io.corebanking.security.Roles.AUDITOR;
import static io.corebanking.security.Roles.BRANCH_MANAGER;
import static io.corebanking.security.Roles.CREDIT_MANAGER;
import static io.corebanking.security.Roles.CREDIT_OFFICER;
import static io.corebanking.security.Roles.CUSTOMER_OFFICER;
import static io.corebanking.security.Roles.OPERATOR;
import static io.corebanking.security.Roles.PRODUCT_MANAGER;
import static io.corebanking.security.Roles.RISK_OFFICER;
import static io.corebanking.security.Roles.TELLER;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Politique d'habilitation de l'application. <b>Source unique de verite.</b>
 *
 * <h2>Pourquoi ici, et nulle part ailleurs</h2>
 *
 * <p>Aucune annotation d'habilitation n'existe dans le code — ni {@code @PreAuthorize}, ni
 * {@code @Secured}, ni {@code @RolesAllowed}. Une regle ArchUnit casse la compilation si l'une
 * d'elles reapparait. Trois raisons :
 *
 * <ul>
 *   <li><b>Auditabilite.</b> Un controleur interne, un commissaire aux comptes ou un inspecteur
 *       demande la matrice des habilitations. Ici, elle s'imprime — {@link #describe()}. Dispersee
 *       en annotations, elle se reconstitue a la main, et cette reconstitution est fausse des la
 *       livraison suivante.</li>
 *   <li><b>Coherence.</b> Deux operations equivalentes finissent toujours par diverger quand leurs
 *       regles sont ecrites a deux endroits, a six mois d'intervalle, par deux personnes.</li>
 *   <li><b>Revue.</b> Un changement d'habilitation apparait dans un diff d'un seul fichier, que
 *       l'on peut exiger de faire relire par le controle interne.</li>
 * </ul>
 *
 * <h2>Le risque que cette centralisation doit neutraliser</h2>
 *
 * <p>Une annotation oubliee laisse une methode ouverte. Une table centrale incomplete fait
 * exactement la meme chose, en moins visible : la methode n'apparait nulle part, donc personne ne
 * la cherche. Deux garde-fous repondent a ce risque, et ils sont la raison pour laquelle cette
 * approche est plus sure que les annotations, et non l'inverse :
 *
 * <ol>
 *   <li>{@link Operation} est une enumeration. Le bloc statique ci-dessous <b>refuse de charger la
 *       classe</b> si une seule valeur n'a pas de regle : l'application ne demarre pas. Une
 *       operation ajoutee sans habilitation ne peut donc pas atteindre la production.</li>
 *   <li>Le refus est le defaut. Une operation absente de la table serait refusee, jamais autorisee.</li>
 * </ol>
 *
 * <h2>Ce que cette table ne contient pas</h2>
 *
 * <p>Aucune URL, aucun nom de methode, aucun detail de transport. La politique porte sur des
 * operations metier. Le point d'entree HTTP se contente d'associer une requete a une operation ;
 * changer une route ne change aucune habilitation.
 */
public final class SecurityConfig {

    private static final CurrencyRef XOF = io.corebanking.kernel.money.Currencies.XOF;

    private static final Map<Operation, AccessRule> POLICY = buildPolicy();

    static {
        // Verification d'exhaustivite au chargement de la classe : une operation sans regle
        // empeche l'application de demarrer, plutot que de creer un trou silencieux.
        List<Operation> orphelines = java.util.Arrays.stream(Operation.values())
            .filter(operation -> !POLICY.containsKey(operation))
            .toList();
        if (!orphelines.isEmpty()) {
            throw new ExceptionInInitializerError(
                "Operations sans regle d'habilitation : " + orphelines
                + ". Toute operation doit figurer dans SecurityConfig avant d'etre exposee.");
        }
    }

    private SecurityConfig() {}

    private static Map<Operation, AccessRule> buildPolicy() {
        Map<Operation, AccessRule> policy = new EnumMap<>(Operation.class);

        // ------------------------------------------------------------------ consultation
        // Tracees en lecture : un agent habilite qui consulte des comptes sans motif est le cas de
        // fraude interne le plus frequent, et il est invisible d'un journal limite aux modifications.
        // Un client de passage se sert dans n'importe quelle agence : la lecture deplacee est
        // admise, et tracee comme toute lecture.
        policy.put(Operation.ACCOUNT_BALANCE_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_BRANCH).allowingRemote().tracedOnRead().build());

        policy.put(Operation.ACCOUNT_JOURNAL_READ,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // Les restitutions comptables lisent la banque entiere : la comptabilite et l'audit, et
        // eux seuls ; un chef d'agence lit ses comptes, pas le journal.
        policy.put(Operation.LEDGER_READ,
            AccessRule.allow(ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        policy.put(Operation.PARTY_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, AUDITOR)
                .within(Scope.OWN_BRANCH).allowingRemote().tracedOnRead().build());

        // ------------------------------------------------------------------ operations
        // Les plafonds vivent ici, pas dans le jeton : un attribut Keycloak mal renseigne ne doit
        // pas pouvoir elever un plafond sans passer par une revue de code.
        // Le guichetier tient la caisse de son agence ; le client peut etre d'une autre agence
        // — operation deplacee — sous un plafond plus bas, l'identite verifiee.
        policy.put(Operation.CASH_OPERATION,
            AccessRule.allow(TELLER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH)
                .upTo(Map.of(TELLER,         Money.of("2000000", XOF),
                             BRANCH_MANAGER, Money.of("25000000", XOF)))
                .remoteUpTo(Map.of(TELLER,         Money.of("500000", XOF),
                                   BRANCH_MANAGER, Money.of("5000000", XOF)))
                .build());

        // Une caisse se cree dans l'agence du chef qui la demande, et un second chef la valide :
        // elle affecte un compte de caisse a un guichetier, et nomme le compte des ecarts.
        policy.put(Operation.TILL_MANAGE,
            AccessRule.allow(BRANCH_MANAGER).within(Scope.OWN_BRANCH)
                .requiringSecondPerson().build());

        // L'arrete de caisse : le guichetier arrete la sienne, le chef d'agence toute caisse de
        // son agence. L'ecart constate est comptabilise, jamais ajuste en silence.
        policy.put(Operation.TILL_CLOSE,
            AccessRule.allow(TELLER, BRANCH_MANAGER).within(Scope.OWN_BRANCH)
                .ownOnlyFor(TELLER).build());

        policy.put(Operation.TRANSFER,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(TELLER,           Money.of("5000000", XOF),
                             CUSTOMER_OFFICER, Money.of("50000000", XOF),
                             BRANCH_MANAGER,   Money.of("100000000", XOF)))
                .build());

        // Un paiement sortant fait sortir de l'argent de la banque : memes porteurs et memes
        // plafonds qu'un virement, hors guichet. Son suivi est un acte de back-office ; sa
        // lecture est tracee comme toute consultation.
        policy.put(Operation.PAYMENT_ORDER,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(CUSTOMER_OFFICER, Money.of("50000000", XOF),
                             BRANCH_MANAGER,   Money.of("100000000", XOF)))
                .build());

        policy.put(Operation.PAYMENT_PROCESS,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.PAYMENT_READ,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER, OPERATOR, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // Un plafond negocie se pose a deux, dans l'agence du compte.
        policy.put(Operation.ACCOUNT_LIMIT_MANAGE,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        // Cheques : le chequier se delivre a deux dans l'agence ; le paiement d'un cheque est un
        // acte de guichet ou de compensation, plafonne comme une operation de caisse ; la remise
        // est un acte de guichet ; son suivi est du back-office ; l'opposition, un acte de
        // gestion du compte ; la lecture est tracee.
        policy.put(Operation.CHEQUE_BOOK_ISSUE,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.CHEQUE_PAY,
            AccessRule.allow(TELLER, BRANCH_MANAGER, OPERATOR)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(TELLER,         Money.of("2000000", XOF),
                             BRANCH_MANAGER, Money.of("25000000", XOF),
                             OPERATOR,       Money.of("100000000", XOF)))
                .build());

        policy.put(Operation.CHEQUE_DEPOSIT,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).build());

        policy.put(Operation.CHEQUE_PROCESS,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.CHEQUE_STOP,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.CHEQUE_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, OPERATOR, ACCOUNTANT,
                             AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // Prelevements : le mandat s'enregistre a deux dans l'agence du compte et se revoque par
        // le gestionnaire du compte ; un prelevement d'un creancier d'ailleurs est presente par
        // la compensation, donc par le back-office ; la remise d'un creancier de la banque — sur
        // un debiteur d'ailleurs, ou de la banque par son mandat — est plafonnee par role comme
        // un virement ; le suivi est du back-office ; la lecture est tracee.
        policy.put(Operation.MANDATE_REGISTER,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.MANDATE_REVOKE,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.DIRECT_DEBIT_PRESENT,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.DIRECT_DEBIT_ISSUE,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(CUSTOMER_OFFICER, Money.of("50000000", XOF),
                             BRANCH_MANAGER,   Money.of("100000000", XOF)))
                .build());

        policy.put(Operation.DIRECT_DEBIT_PROCESS,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.DIRECT_DEBIT_READ,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER, OPERATOR, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // Dossier client : une piece se depose au guichet, dans l'agence du tiers ; une relation
        // ou un beneficiaire effectif se declare a deux — l'un donne un pouvoir sur des comptes,
        // l'autre est une declaration reglementaire ; la politique de diligence est du controle.
        policy.put(Operation.PARTY_DOCUMENT,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).build());

        policy.put(Operation.PARTY_RELATIONSHIP,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.KYC_POLICY_MANAGE,
            AccessRule.allow(RISK_OFFICER).within(Scope.OWN_ENTITY)
                .requiringSecondPerson().build());

        // La politique declaree et la liste des dossiers incomplets se lisent a l'echelle de
        // l'entite : le guichetier doit savoir quelles pieces reclamer, la conformite et l'audit
        // ont besoin de la liste de travail — qu'aucun perimetre d'agence ne doit tronquer.
        policy.put(Operation.PARTY_FILE_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, RISK_OFFICER, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // Change : un cours de reference controle tout cours applique, et une position dit ou
        // l'exposition se mesure — les deux se posent a deux, au siege ; leur lecture est tracee.
        policy.put(Operation.FX_RATE_QUOTE,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY)
                .requiringSecondPerson().build());

        policy.put(Operation.FX_POSITION_MANAGE,
            AccessRule.allow(ACCOUNTANT).within(Scope.OWN_ENTITY)
                .requiringSecondPerson().build());

        policy.put(Operation.FX_READ,
            AccessRule.allow(OPERATOR, ACCOUNTANT, AUDITOR).within(Scope.OWN_ENTITY)
                .tracedOnRead().build());

        // Suspens : la politique — anciennete toleree, responsable — se fixe a deux par le
        // back-office ; la revue est une consultation d'exploitation et de controle, tracee.
        policy.put(Operation.SUSPENSE_MANAGE,
            AccessRule.allow(OPERATOR, ACCOUNTANT).within(Scope.OWN_ENTITY)
                .requiringSecondPerson().build());

        policy.put(Operation.SUSPENSE_READ,
            AccessRule.allow(OPERATOR, ACCOUNTANT, AUDITOR).within(Scope.OWN_ENTITY)
                .tracedOnRead().build());

        // Une correction se valide par un tiers : c'est le geste par lequel une fraude se dissimule.
        policy.put(Operation.ENTRY_REVERSAL,
            AccessRule.allow(BRANCH_MANAGER, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNT_HOLD,
            AccessRule.allow(BRANCH_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Un blocage de compte prive un client de ses fonds, ou l'en libere a tort : a deux,
        // et jamais par celui qui tient le guichet.
        policy.put(Operation.ACCOUNT_BLOCK,
            AccessRule.allow(BRANCH_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Une ecriture d'ordre divers n'a ni client ni operation pour la justifier : elle ne
        // passe que par le comptable, et jamais seul.
        policy.put(Operation.JOURNAL_ENTRY_MANUAL,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ referentiel
        policy.put(Operation.PARTY_CREATE,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).build());

        // La verification de la connaissance client ouvre tout le reste : elle se fait a deux,
        // et le risque y a sa place.
        policy.put(Operation.KYC_VERIFY,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNT_OPEN,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNT_CLOSE,
            AccessRule.allow(BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        // Changer le produit d'un compte change ce que le client paie et ce qu'il gagne.
        policy.put(Operation.ACCOUNT_PRODUCT_ASSIGN,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        // ------------------------------------------------------------------ credit
        policy.put(Operation.LOAN_READ,
            AccessRule.allow(CUSTOMER_OFFICER, CREDIT_OFFICER, CREDIT_MANAGER, BRANCH_MANAGER,
                             RISK_OFFICER, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        // L'origination : monter et instruire un dossier est un travail d'agence ; decider est
        // une delegation, et la delegation se mesure en francs. Au-dela du plafond du responsable
        // des engagements, aucun role ne porte la decision : elle releve d'un comite, et le refus
        // le dit au lieu de laisser passer.
        policy.put(Operation.LOAN_APPLICATION,
            AccessRule.allow(CUSTOMER_OFFICER, CREDIT_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).build());

        policy.put(Operation.LOAN_APPLICATION_DECIDE,
            AccessRule.allow(BRANCH_MANAGER, CREDIT_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(BRANCH_MANAGER, Money.of("25000000", XOF),
                             CREDIT_MANAGER, Money.of("250000000", XOF)))
                .requiringSecondPerson().build());

        // Lever une condition suspensive libere des fonds, comme une mainlevee de surete.
        policy.put(Operation.LOAN_CONDITION_CLEAR,
            AccessRule.allow(CREDIT_OFFICER, CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.LENDING_POLICY_MANAGE,
            AccessRule.allow(RISK_OFFICER).within(Scope.OWN_ENTITY)
                .requiringSecondPerson().build());

        // Sortir un actif des livres constate une perte : la decision est du siege, a deux, et
        // plafonnee comme un deblocage — c'est le meme argent, dans l'autre sens.
        policy.put(Operation.LOAN_WRITE_OFF,
            AccessRule.allow(CREDIT_MANAGER, ACCOUNTANT)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(CREDIT_MANAGER, Money.of("100000000", XOF),
                             ACCOUNTANT, Money.of("100000000", XOF)))
                .requiringSecondPerson().build());

        // Encaisser sur une creance amortie est une operation de guichet comme une autre.
        policy.put(Operation.LOAN_RECOVERY,
            AccessRule.allow(TELLER, CREDIT_OFFICER, CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).build());

        policy.put(Operation.LOAN_RATE_REVISION,
            AccessRule.allow(CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.LOAN_CONTRACT_CREATE,
            AccessRule.allow(CREDIT_OFFICER, BRANCH_MANAGER).within(Scope.OWN_BRANCH).build());

        // L'argent sort ici. Double validation sans exception, et un plafond par role : au-dela,
        // la decision releve d'un comite ; l'origination porte la meme delegation sur l'octroi.
        policy.put(Operation.LOAN_DISBURSE,
            AccessRule.allow(CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(BRANCH_MANAGER, Money.of("50000000", XOF),
                             CREDIT_MANAGER, Money.of("500000000", XOF)))
                .requiringSecondPerson().build());

        // Rechelonner, c'est modifier ce que le client doit : meme regime qu'un parametrage.
        policy.put(Operation.LOAN_RESCHEDULE,
            AccessRule.allow(CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Un droit de l'emprunteur : l'agent l'enregistre, il ne le decide pas. Mais il publie
        // un nouvel echeancier, et un echeancier s'approuve a deux, comme tout ce qui fixe ce
        // que le client doit — la base l'exige (approved_by <> created_by), la politique aussi.
        policy.put(Operation.LOAN_PREPAY,
            AccessRule.allow(CREDIT_OFFICER, CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.LOAN_REPAYMENT,
            AccessRule.allow(TELLER, CREDIT_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH)
                .upTo(Map.of(TELLER,         Money.of("2000000", XOF),
                             CREDIT_OFFICER, Money.of("25000000", XOF),
                             BRANCH_MANAGER, Money.of("25000000", XOF)))
                .build());

        // Une mainlevee decouvre la banque ; une prise de surete surevaluee reduit la provision.
        policy.put(Operation.COLLATERAL_MANAGE,
            AccessRule.allow(CREDIT_OFFICER, CREDIT_MANAGER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ parametrage
        // Un parametrage produit des montants sur des comptes clients : meme regime qu'une operation.
        policy.put(Operation.PRODUCT_DRAFT,
            AccessRule.allow(PRODUCT_MANAGER).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.PRODUCT_ACTIVATE,
            AccessRule.allow(PRODUCT_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Une grille de risque decide du niveau de provision de tout le portefeuille : elle est
        // redigee par le risque et validee par une seconde main, comptable ou risque.
        policy.put(Operation.RISK_PARAMETER_DRAFT,
            AccessRule.allow(RISK_OFFICER).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.RISK_PARAMETER_ACTIVATE,
            AccessRule.allow(RISK_OFFICER, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNTING_SCHEMA_DRAFT,
            AccessRule.allow(ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.ACCOUNTING_SCHEMA_ACTIVATE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Une maquette d'etat financier fixe ce que la banque presente : la comptabilite la
        // redige, une seconde main comptable l'active.
        policy.put(Operation.STATEMENT_LAYOUT_DRAFT,
            AccessRule.allow(ACCOUNTANT).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.STATEMENT_LAYOUT_ACTIVATE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Un jour ferie deplace des dates de valeur et des echeances : ce n'est pas anodin.
        policy.put(Operation.CALENDAR_MANAGE,
            AccessRule.allow(OPERATOR, PRODUCT_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Une agence porte des comptes de liaison et des plafonds : l'exploitation la cree, la
        // comptabilite la valide.
        policy.put(Operation.BRANCH_MANAGE,
            AccessRule.allow(OPERATOR, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Une exoneration est un produit abandonne : elle se decide a deux.
        policy.put(Operation.FEE_EXEMPTION_GRANT,
            AccessRule.allow(BRANCH_MANAGER, PRODUCT_MANAGER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // Un taux de retenue faux est reverse faux a l'Etat sur tout le portefeuille : la
        // declaration est comptable et validee a deux, comme un schema.
        policy.put(Operation.TAX_PARAMETER_DECLARE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ exploitation
        policy.put(Operation.TFJ_RUN,
            AccessRule.allow(OPERATOR).within(Scope.OWN_ENTITY).build());

        // Annuler un TFJ contre-passe des millions d'ecritures : double validation, sans exception.
        policy.put(Operation.TFJ_CANCEL,
            AccessRule.allow(OPERATOR, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.PERIOD_CLOSE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.PERIOD_REOPEN,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // L'exercice et sa cloture fixent ce que la banque presente : la comptabilite, a deux.
        policy.put(Operation.FISCAL_YEAR_MANAGE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.YEAR_CLOSE,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.YEAR_REOPEN,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // L'affectation du resultat execute une decision d'assemblee : la comptabilite la passe,
        // a deux ; le montant n'est pas plafonne, il est celui du resultat, exactement.
        policy.put(Operation.RESULT_APPROPRIATION,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ audit
        // Seul acces legitimement transverse aux entites, et le seul.
        policy.put(Operation.AUDIT_READ,
            AccessRule.allow(AUDITOR).within(Scope.ANY_ENTITY).tracedOnRead().build());

        return Map.copyOf(policy);
    }

    // ==================================================================== segregation des taches

    /**
     * Cumul de roles interdit sur une meme identite.
     *
     * @param reason motif, restitue tel quel au porteur et dans la piste d'audit
     */
    public record RoleConflict(String first, String second, String reason) {
        boolean matches(java.util.Set<String> roles) {
            return roles.contains(first) && roles.contains(second);
        }
    }

    /**
     * Cumuls interdits.
     *
     * <p>La liste est courte, et c'est voulu. Le cumul de deux roles n'est pas en soi un defaut :
     * un chef d'agence tient une caisse, et la regle du valideur distinct de l'auteur
     * ({@code dualControl}) empeche deja quiconque de valider sa propre operation. Ce mecanisme-la
     * est meilleur qu'une interdiction de cumul, parce qu'il agit au niveau de l'operation et non
     * de l'identite : il n'empeche pas de travailler, il empeche de se controler soi-meme.
     *
     * <p>Reste un cas que le controle par operation ne couvre pas : <b>l'auditeur qui opere</b>.
     * Le probleme n'est pas qu'il valide sa propre ecriture — c'est qu'il verifie a posteriori un
     * perimetre dont il fait partie. Aucune regle a l'echelle de l'operation ne peut le detecter,
     * puisque chacune de ses actions, prise isolement, est reguliere.
     */
    private static final List<RoleConflict> SEGREGATION = buildSegregation();

    /**
     * Les cumuls interdits sont <b>derives du catalogue</b> : un role portant l'attribut
     * {@code exclusive} ne se cumule avec aucun autre. Declarer un nouveau profil exclusif reste
     * donc du parametrage, sans toucher a cette classe.
     */
    private static List<RoleConflict> buildSegregation() {
        List<RoleConflict> conflicts = new java.util.ArrayList<>();
        java.util.Set<String> exclusives = RoleCatalogue.exclusiveRoles();
        for (String exclusive : exclusives) {
            for (String other : RoleCatalogue.declared()) {
                if (!other.equals(exclusive)) {
                    conflicts.add(new RoleConflict(exclusive, other,
                        "role exclusif : son porteur ne peut pas operer sur le perimetre "
                        + "qu'il controle"));
                }
            }
        }
        return List.copyOf(conflicts);
    }

    /** Premier cumul interdit detecte, le cas echeant. */
    public static java.util.Optional<RoleConflict> segregationConflict(java.util.Set<String> roles) {
        return SEGREGATION.stream().filter(conflict -> conflict.matches(roles)).findFirst();
    }

    public static List<RoleConflict> segregationRules() {
        return SEGREGATION;
    }

    // ==================================================================== lecture

    /**
     * Regle applicable a une operation. Ne renvoie jamais {@code null} : l'exhaustivite est
     * garantie au chargement de la classe.
     */
    public static AccessRule ruleFor(Operation operation) {
        AccessRule rule = POLICY.get(operation);
        return rule == null ? AccessRule.denyAll() : rule;
    }

    public static Map<Operation, AccessRule> policy() {
        return POLICY;
    }

    /**
     * Matrice des habilitations sous forme lisible. C'est l'artefact remis au controle interne et a
     * l'inspection ; il est genere depuis la politique reellement appliquee, et ne peut donc pas
     * diverger d'elle.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Operation | Roles | Perimetre | Plafonds | Double validation | Lecture tracee\n");
        sb.append("---|---|---|---|---|---\n");
        for (Operation operation : Operation.values()) {
            AccessRule rule = POLICY.get(operation);
            sb.append(operation).append(" | ")
              .append(rule.roles().stream().sorted().toList()).append(" | ")
              .append(rule.scope()).append(" | ")
              .append(rule.ceilings().isEmpty() ? "-" : new java.util.TreeMap<>(rule.ceilings()))
              .append(" | ")
              .append(rule.dualControl() ? "oui" : "non").append(" | ")
              .append(rule.auditEvenOnSuccess() ? "oui" : "non").append('\n');
        }
        return sb.toString();
    }
}
