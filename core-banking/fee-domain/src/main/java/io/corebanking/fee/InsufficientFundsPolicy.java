package io.corebanking.fee;

/**
 * Conduite a tenir lorsque le disponible ne couvre pas la commission.
 *
 * <p>C'est la decision la plus lourde du parametrage des frais, et elle n'a pas de bonne reponse
 * universelle : chacune des trois options a un cout, et le choix appartient a la banque.
 */
public enum InsufficientFundsPolicy {

    /**
     * Renoncer a la commission pour cette periode. Elle n'est pas reportee : la periode est
     * consignee comme non percue et ne sera jamais representee.
     *
     * <p>Cout : une perte de produit definitive, invisible dans les comptes puisqu'aucune ecriture
     * n'est passee. C'est pourquoi la periode est tout de meme enregistree, avec son montant : sans
     * cette trace, le manque a gagner serait inconnaissable.
     */
    REJECT,

    /**
     * Prelever malgre tout, en laissant le compte passer debiteur.
     *
     * <p>Cout : la banque cree elle-meme un decouvert non autorise, qui generera des agios que le
     * client contestera. Plusieurs juridictions encadrent cette pratique.
     */
    FORCE,

    /**
     * Reporter : la commission devient une creance, representee a chaque traitement suivant
     * jusqu'a encaissement ou abandon apres un delai parametre.
     *
     * <p>Cout : un suivi a tenir, et une creance qui vieillit. C'est le regime le plus fidele a la
     * realite economique — la prestation a ete servie, la contrepartie reste due — et celui qui
     * demande le plus de rigueur.
     */
    DEFER
}
