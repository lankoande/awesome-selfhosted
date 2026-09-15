package io.corebanking.party;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Filtrage d'un tiers contre les listes — sanctions, personnes politiquement exposees, listes
 * internes.
 *
 * <p>Le moteur de correspondance approximative est externalise : un developpement maison produit
 * des taux de faux negatifs inacceptables. Le socle expose l'interface, appelle le filtrage a la
 * creation et a chaque modification d'identite, et bloque le dossier sur correspondance jusqu'a
 * la levee de doute. Sans fournisseur branche, {@link #NONE} ne filtre rien — et le dit.
 */
@FunctionalInterface
public interface Screening {

    /** Ce qui est soumis au filtrage : ce que les listes savent comparer. */
    record Subject(String displayName, LocalDate birthOrRegistrationDate, String countryCode,
                   List<PartyIdentifier> identifiers) {}

    /** Une correspondance : la liste, la reference sur cette liste, le detail. */
    record Match(String list, String reference, String detail) {}

    Optional<Match> screen(Subject subject);

    /** Aucun filtrage : a n'employer qu'en l'absence de fournisseur, jamais en production. */
    Screening NONE = subject -> Optional.empty();
}
