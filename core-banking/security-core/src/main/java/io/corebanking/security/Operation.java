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

    // ------------------------------------------------------------------ referentiel
    /** Ouverture d'un compte. */
    ACCOUNT_OPEN,
    /** Cloture d'un compte. */
    ACCOUNT_CLOSE,

    // ------------------------------------------------------------------ parametrage
    /** Redaction d'une version de produit. */
    PRODUCT_DRAFT,
    /** Activation d'une version de produit. */
    PRODUCT_ACTIVATE,

    // ------------------------------------------------------------------ exploitation
    /** Lancement du traitement de fin de journee. */
    TFJ_RUN,
    /** Annulation d'un TFJ deja execute. */
    TFJ_CANCEL,
    /** Reouverture d'une periode comptable close. */
    PERIOD_REOPEN,

    // ------------------------------------------------------------------ audit
    /** Consultation de la piste d'audit. */
    AUDIT_READ
}
