package io.corebanking.security;

/**
 * Operation metier protegee.
 *
 * <p>Chaque cas d'usage declare l'operation qu'il realise et sait deduire de sa commande l'objet
 * sur lequel elle porte. Il ne verifie <b>jamais</b> lui-meme une habilitation : c'est
 * {@link UseCaseExecutor} qui applique la politique, en un seul endroit.
 *
 * @param <C> type de la commande
 * @param <R> type du resultat
 */
public interface UseCase<C, R> {

    /** Operation du catalogue realisee par ce cas d'usage. */
    Operation operation();

    /**
     * Objet vise par la commande : entite, agence, montant, auteur d'origine.
     *
     * <p>C'est ici que se fait le lien entre le metier et la politique. Le controle fin — l'agence
     * du compte, le montant en jeu, l'auteur de l'ecriture a contre-passer — a besoin de donnees
     * que seul le cas d'usage connait ; les <b>regles</b>, elles, restent dans
     * {@link SecurityConfig}.
     */
    AccessTarget targetOf(C command);

    R execute(C command);
}
