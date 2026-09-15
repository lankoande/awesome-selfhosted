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

    // ------------------------------------------------------------------ operations
    /** Versement ou retrait au guichet. */
    CASH_OPERATION,
    /** Virement entre comptes. */
    TRANSFER,
    /** Contre-passation d'une ecriture. */
    ENTRY_REVERSAL,
    /** Pose ou levee d'un blocage sur un compte. */
    ACCOUNT_HOLD,

    /** Ecriture d'ordre divers : une imputation hors de toute operation de guichet ou de credit. */
    JOURNAL_ENTRY_MANUAL,

    // ------------------------------------------------------------------ referentiel
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
    /** Calendriers, jours feries, regles de date de valeur. */
    CALENDAR_MANAGE,
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

    // ------------------------------------------------------------------ audit
    /** Consultation de la piste d'audit. */
    AUDIT_READ
}
