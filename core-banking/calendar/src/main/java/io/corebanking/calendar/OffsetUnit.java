package io.corebanking.calendar;

/**
 * Unite du decalage entre date comptable et date de valeur.
 *
 * <p>La distinction est financierement significative. « Deux jours ouvres » a partir d'un vendredi
 * donne le mardi ; « deux jours calendaires ajustes au suivant » donne le lundi. Une journee
 * d'ecart, et cette journee se facture.
 */
public enum OffsetUnit {
    CALENDAR_DAYS,
    BUSINESS_DAYS
}
