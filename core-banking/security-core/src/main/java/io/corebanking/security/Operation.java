package io.corebanking.security;

/**
 * Catalogue exhaustif des operations protegees.
 *
 * <p>C'est une enumeration, et non une chaine libre, pour une raison precise : le compilateur et le
 * test d'exhaustivite garantissent ensemble qu'aucune operation ne peut exister sans regle. Avec des
 * identifiants textuels, une faute de frappe creerait silencieusement une operation inconnue de la
 * politique — donc refusee en production, ou pire, oubliee du controle.
 *
 * <p>Ajouter une valeur ici <b>casse la compilation de {@link SecurityConfig}</b> tant que la regle
 * correspondante n'est pas ecrite. C'est volontaire.
 *
 * <h2>Ce que le catalogue couvre, et ce qu'il ne couvre pas</h2>
 *
 * <p>Une operation par point d'entree de service qui change l'etat de la banque, ou lit ce qui
 * doit etre trace. Le rattachement point d'entree → operation se fera dans les cas d'usage
 * ({@link UseCase#operation()}) ; d'ici la, {@code OperationCoverageTest} tient l'inventaire.
 *
 * <p>N'en font pas partie, deliberement : la creation d'une entite juridique ou d'une devise —
 * des actes de deploiement, pas des operations d'exploitation — et les traitements de masse du
 * TFJ, couverts par {@link #TFJ_RUN} : exigibilite, charges de retard, classification, cloture
 * des credits soldes, perception des commissions.
 */
public enum Operation {

    // ------------------------------------------------------------------ consultation
    /** Lecture du solde d'un compte. Tracee : la consultation abusive est la fraude interne la plus courante. */
    ACCOUNT_BALANCE_READ,
    /** Lecture du detail des ecritures d'un compte. */
    ACCOUNT_JOURNAL_READ,
    /** Lecture du referentiel client. */
    PARTY_READ,
    /**
     * Restitutions comptables : balance, grand livre, journal de l'entite. La comptabilite et
     * l'audit lisent le journal entier ; c'est une lecture de la banque, pas d'un client, et
     * elle est tracee comme toute lecture.
     */
    LEDGER_READ,

    // ------------------------------------------------------------------ operations
    /** Versement ou retrait au guichet. */
    CASH_OPERATION,
    /** Virement entre comptes. */
    TRANSFER,
    /** Ordre de paiement sortant : le client est debite, les fonds attendent le correspondant. */
    PAYMENT_ORDER,
    /** Suivi d'un paiement sortant : envoi, reglement sur le nostro, retour, annulation avant envoi. */
    PAYMENT_PROCESS,
    /** Consultation des ordres de paiement. */
    PAYMENT_READ,
    /** Plafond propre a un compte : par operation, par jour, par mois. */
    ACCOUNT_LIMIT_MANAGE,
    /** Delivrance d'un chequier a un compte, aux frais du produit. */
    CHEQUE_BOOK_ISSUE,
    /** Paiement d'un cheque emis : au guichet, ou par compensation sur le nostro. */
    CHEQUE_PAY,
    /** Remise d'un cheque tire sur une autre banque, creditee sauf bonne fin. */
    CHEQUE_DEPOSIT,
    /** Suivi d'une remise : reglement par le correspondant, ou impaye. */
    CHEQUE_PROCESS,
    /** Opposition sur un cheque, pour un motif que la loi admet. */
    CHEQUE_STOP,
    /** Consultation des chequiers, cheques, incidents et remises. */
    CHEQUE_READ,
    /** Enregistrement d'un mandat de prelevement sur un compte, a deux. */
    MANDATE_REGISTER,
    /** Revocation d'un mandat par le client. */
    MANDATE_REVOKE,
    /** Ordre permanent : le virement que le client programme une fois, mis en place a deux. */
    STANDING_ORDER_REGISTER,
    /** Revocation d'un ordre permanent par le client. */
    STANDING_ORDER_CANCEL,
    /** Consultation des ordres permanents et de leurs echeances. */
    STANDING_ORDER_READ,
    /** Presentation par la compensation d'un prelevement d'un creancier d'ailleurs, sur un mandat. */
    DIRECT_DEBIT_PRESENT,
    /** Remise d'un client creancier : sur un debiteur d'ailleurs, credite sauf bonne fin a l'echeance ; sur un debiteur de la banque, par son mandat. */
    DIRECT_DEBIT_ISSUE,
    /** Suivi d'un prelevement : reglement, rappel, remboursement, retour. */
    DIRECT_DEBIT_PROCESS,
    /** Consultation des mandats et des prelevements. */
    DIRECT_DEBIT_READ,
    /** Depot d'une piece au dossier d'un tiers : un acte d'agence, date. */
    PARTY_DOCUMENT,
    /** Relations entre tiers et beneficiaires effectifs : elles donnent un pouvoir ou engagent un groupe. */
    PARTY_RELATIONSHIP,
    /** Politique de diligence : pieces exigees, beneficiaires effectifs, seuil de detention. */
    KYC_POLICY_MANAGE,
    /** Lecture du dossier a l'echelle de l'entite : politique declaree, dossiers incomplets. */
    PARTY_FILE_READ,
    /** Cotation d'un cours de reference, a deux : il controle tout cours applique. */
    FX_RATE_QUOTE,
    /** Position de change : comptes de position et de contre-valeur, resultat, marge toleree, a deux. */
    FX_POSITION_MANAGE,
    /** Consultation des cours et des positions de change. */
    FX_READ,
    /** Demande de credit : depot, instruction, conditions — le travail d'agence sur un dossier. */
    LOAN_APPLICATION,
    /** Decision sur une demande de credit, a deux et sous delegation par montant. */
    LOAN_APPLICATION_DECIDE,
    /** Levee d'une condition suspensive : le geste qui ouvre le versement, a deux. */
    LOAN_CONDITION_CLEAR,
    /** Politique d'octroi d'un produit : endettement, montant, duree, apport, garantie, a deux. */
    LENDING_POLICY_MANAGE,
    /** Passage en perte : la sortie d'un actif des livres, a deux. */
    LOAN_WRITE_OFF,
    /** Encaissement sur une creance passee en perte. */
    LOAN_RECOVERY,
    /** Revision du taux d'un credit en cours, a deux : elle change ce que le client doit. */
    LOAN_RATE_REVISION,
    /** Politique de suspens : anciennete toleree et responsable par nature, a deux. */
    SUSPENSE_MANAGE,
    /** Revue des suspens : ce qui attend le correspondant, avec son anciennete. */
    SUSPENSE_READ,
    /** Contre-passation d'une ecriture. */
    ENTRY_REVERSAL,
    /** Pose ou levee d'un blocage de montant sur un compte. */
    ACCOUNT_HOLD,
    /** Pose ou levee d'un blocage de compte : opposition, saisie, gel. */
    ACCOUNT_BLOCK,

    /** Ecriture d'ordre divers : une imputation hors de toute operation de guichet ou de credit. */
    JOURNAL_ENTRY_MANUAL,

    // ------------------------------------------------------------------ referentiel
    /** Creation d'un tiers et de ses identifiants ; blocage et deblocage du dossier. */
    PARTY_CREATE,
    /** Verification de la connaissance client : ce qui rend un tiers operable. */
    KYC_VERIFY,
    /** Ouverture d'un compte. */
    ACCOUNT_OPEN,
    /** Cloture d'un compte. */
    ACCOUNT_CLOSE,
    /** Rattachement d'un compte a un produit, ou changement de produit. */
    ACCOUNT_PRODUCT_ASSIGN,

    // ------------------------------------------------------------------ credit
    /** Consultation d'un dossier de credit : contrat, echeancier, creances, classification. */
    LOAN_READ,
    /** Creation d'un contrat de credit et rattachement a son client. */
    LOAN_CONTRACT_CREATE,
    /**
     * Mise a disposition des fonds : deblocage unique, ouverture d'une mobilisation, deblocage
     * d'une tranche. C'est le moment ou l'argent sort ; le plafond porte sur le montant verse.
     */
    LOAN_DISBURSE,
    /** Rechelonnement : publication d'un nouvel echeancier. */
    LOAN_RESCHEDULE,
    /** Remboursement anticipe, total ou partiel. */
    LOAN_PREPAY,
    /** Reglement d'echeance recu au guichet. Le prelevement d'office releve du TFJ. */
    LOAN_REPAYMENT,
    /** Prise, affectation et mainlevee d'une surete. */
    COLLATERAL_MANAGE,

    // ------------------------------------------------------------------ parametrage
    /** Redaction d'une version de produit — commissions comprises. */
    PRODUCT_DRAFT,
    /** Activation d'une version de produit. */
    PRODUCT_ACTIVATE,
    /** Redaction d'une grille de risque ou d'un regime de surete. */
    RISK_PARAMETER_DRAFT,
    /** Activation d'une grille de risque ou d'un regime de surete. */
    RISK_PARAMETER_ACTIVATE,
    /** Redaction d'un schema comptable. */
    ACCOUNTING_SCHEMA_DRAFT,
    /** Activation d'un schema comptable. */
    ACCOUNTING_SCHEMA_ACTIVATE,
    /** Redaction d'une maquette d'etat financier : bilan, compte de resultat, hors bilan. */
    STATEMENT_LAYOUT_DRAFT,
    /** Activation d'une maquette d'etat financier : ce que la banque presente, a deux. */
    STATEMENT_LAYOUT_ACTIVATE,
    /** Calendriers, jours feries, regles de date de valeur. */
    CALENDAR_MANAGE,
    /** Creation d'une agence ou d'une region, avec ses comptes de liaison. */
    BRANCH_MANAGE,
    /** Creation d'une caisse : compte de caisse, guichetier titulaire, compte d'ecart. */
    TILL_MANAGE,
    /**
     * Arrete de caisse : comptage, ecart constate et comptabilise, journee de caisse close. Un
     * guichetier n'arrete que sa caisse ; le chef d'agence arrete toute caisse de son agence.
     */
    TILL_CLOSE,
    /** Exoneration d'une commission pour un compte. */
    FEE_EXEMPTION_GRANT,
    /** Declaration d'une retenue a la source ou d'une taxe, par entite et periode de validite. */
    TAX_PARAMETER_DECLARE,

    // ------------------------------------------------------------------ exploitation
    /** Lancement ou reprise du traitement de fin de journee. */
    TFJ_RUN,
    /** Annulation d'un TFJ deja execute. */
    TFJ_CANCEL,
    /** Cloture d'une periode comptable. L'ouverture est un effet de la bascule de journee. */
    PERIOD_CLOSE,
    /** Reouverture d'une periode comptable close. */
    PERIOD_REOPEN,
    /** Ouverture d'un exercice fiscal : ses bornes et son compte de resultat. */
    FISCAL_YEAR_MANAGE,
    /** Cloture annuelle : determination du resultat, dernier mois et exercice clos. */
    YEAR_CLOSE,
    /** Annulation d'une cloture annuelle : le resultat defait, l'exercice et son dernier mois rouverts. */
    YEAR_REOPEN,
    /** Affectation du resultat d'un exercice clos : la decision de l'assemblee, comptabilisee. */
    RESULT_APPROPRIATION,

    // ------------------------------------------------------------------ audit
    /** Consultation de la piste d'audit. */
    AUDIT_READ
}
