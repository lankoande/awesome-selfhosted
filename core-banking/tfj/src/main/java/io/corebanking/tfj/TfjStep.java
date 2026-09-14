package io.corebanking.tfj;

/**
 * Etape d'un traitement de fin de journee.
 *
 * <p>Une etape est <b>idempotente</b> : la rejouer produit le meme etat, jamais un doublon. C'est
 * ce qui rend la reprise sure, et c'est obtenu par des cles de comptabilisation deterministes
 * derivees du couple run et etape — jamais par un drapeau « deja fait » qui mentirait apres un
 * arret brutal.
 */
public interface TfjStep {

    String name();

    /**
     * Une etape bloquante interrompt le traitement en cas d'echec ou d'anomalie. Les autres
     * consignent et laissent le TFJ se poursuivre.
     *
     * <p>Le partage n'est pas indifferent : rendre bloquant ce qui ne l'est pas fait echouer des
     * TFJ pour des motifs sans consequence, et l'exploitation prend l'habitude de forcer. Ne pas
     * rendre bloquant ce qui l'est laisse passer un ecart comptable.
     */
    boolean blocking();

    StepResult execute(TfjContext context);
}
