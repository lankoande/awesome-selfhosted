package io.corebanking.api.web;

import io.corebanking.kernel.money.Money;
import io.corebanking.security.AuthorizationService;
import io.corebanking.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ce que le socle dit de l'appelant : qui il est, et ce que ses roles admettent.
 *
 * <h2>Pourquoi ces deux routes existent</h2>
 *
 * <p>Sans elles, un poste de travail n'a que deux facons de savoir ce qu'il peut proposer :
 * tout montrer et laisser l'API refuser, ou <b>deduire les droits du jeton</b>. La seconde est
 * la mauvaise : elle ferait du navigateur une source d'habilitation, alors que les roles Keycloak
 * servent au fournisseur d'identite et que la politique, elle, vit dans {@code SecurityConfig}.
 * Un poste qui se croit autorise face a un socle qui refuse — ou pire, l'inverse — est le defaut
 * que ces deux routes suppriment.
 *
 * <h2>Pourquoi elles ne portent pas d'operation</h2>
 *
 * <p>La regle de ce socle est que toute operation protegee passe par {@code require()}. Ces deux
 * lectures n'en sont pas : elles ne rendent <b>rien que le jeton ne porte deja</b>, et exiger une
 * habilitation pour lire ses propres habilitations serait circulaire — il faudrait un droit pour
 * savoir si l'on a des droits. Le cloisonnement est assure par construction : tout vient du
 * {@link Caller}, jamais d'un parametre de requete, donc un appelant ne peut pas lire l'identite
 * d'un autre.
 *
 * <p>Corollaire a tenir : <b>ce que rend {@code /permissions} n'est pas une decision d'acces.</b>
 * L'agence, le montant et l'objet vise n'y sont pas connus. Le socle refuse toujours au moment
 * d'agir ; l'interface s'en sert pour ne pas proposer une porte qu'elle sait fermee.
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final AuthorizationService authorization;

    public MeController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    /** L'identite de l'appelant telle que le socle l'a etablie depuis le jeton. */
    @GetMapping
    public Identity me(Caller caller) {
        return new Identity(caller.subjectId(), caller.username(), caller.roles(),
                            caller.legalEntityId(), caller.branchId());
    }

    /**
     * Les operations que les roles de l'appelant admettent, avec ce que la politique en dit.
     * Une operation absente est certainement refusee ; une operation presente ne l'est pas
     * certainement autorisee.
     */
    @GetMapping("/permissions")
    public List<Permission> permissions(Caller caller) {
        return authorization.grants(caller).stream()
            .map(grant -> new Permission(
                grant.operation().name(), grant.scope().name(), grant.dualControl(),
                grant.remoteAllowed(), grant.ceilings(), grant.remoteCeilings()))
            .toList();
    }

    /**
     * Qui appelle, et d'ou.
     *
     * @param subjectId     identifiant stable du porteur, revendication {@code sub}
     * @param username      identifiant de connexion, celui qui figure dans la piste d'audit
     * @param roles         roles applicatifs portes par le jeton
     * @param legalEntityId entite juridique de rattachement ; aucune identite n'est valable sans
     * @param branchId      agence de rattachement, nulle pour un profil siege
     */
    public record Identity(String subjectId, String username, Set<String> roles,
                           UUID legalEntityId, UUID branchId) {}

    /**
     * Une operation ouverte aux roles de l'appelant.
     *
     * @param operation      nom de l'operation au catalogue du socle
     * @param scope          {@code OWN_BRANCH}, {@code OWN_ENTITY} ou {@code ANY_ENTITY}
     * @param dualControl    l'operation part a la validation d'un second, distinct de l'auteur
     * @param remoteAllowed  l'operation peut se faire hors de l'agence gestionnaire de l'objet
     * @param ceilings       plafond par devise, le plus favorable des roles de l'appelant ; vide
     *                       quand aucun plafond ne s'applique
     * @param remoteCeilings plafond par devise en operation deplacee, quand il differe
     */
    public record Permission(String operation, String scope, boolean dualControl,
                             boolean remoteAllowed, Map<String, Money> ceilings,
                             Map<String, Money> remoteCeilings) {}
}
