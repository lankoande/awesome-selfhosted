package io.corebanking.loan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Ordre d'imputation d'un reglement sur les creances d'un credit.
 *
 * <h2>Pourquoi l'ordre est exige exhaustif</h2>
 *
 * <p>Un ordre qui omet une categorie rendrait la creance correspondante <b>impayable</b> : les
 * reglements passeraient a cote, la creance vieillirait, declencherait des penalites, puis un
 * declassement — sans qu'aucune erreur ne soit jamais signalee. Le parametrage est donc refuse s'il
 * ne cite pas chaque categorie exactement une fois. C'est la meme exigence que celle de la
 * politique d'habilitation : une table centrale n'est sure que si son exhaustivite est verifiee.
 *
 * <h2>Pourquoi il est parametre</h2>
 *
 * <p>Imputer sur le capital avant les penalites eteint la dette plus vite et coute moins cher au
 * client ; l'ordre inverse fait durer le capital et donc les interets. L'ecart est reel, et
 * plusieurs juridictions imposent leur propre ordre. Le coder en dur reviendrait a livrer une
 * version du logiciel par pays.
 */
public record AllocationOrder(List<DueCategory> order) {

    public AllocationOrder {
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        EnumSet<DueCategory> seen = EnumSet.noneOf(DueCategory.class);
        for (DueCategory category : order) {
            if (!seen.add(category)) {
                throw new IncompleteAllocationOrderException(
                    "la categorie " + category + " y figure deux fois");
            }
        }
        EnumSet<DueCategory> missing = EnumSet.allOf(DueCategory.class);
        missing.removeAll(seen);
        if (!missing.isEmpty()) {
            throw new IncompleteAllocationOrderException(
                "les categories " + missing + " n'y figurent pas. Les creances correspondantes ne "
                + "seraient jamais imputees : elles vieilliraient en silence jusqu'au declassement");
        }
    }

    /**
     * Ordre par defaut du dossier de conception : le recouvrement d'abord, le capital non echu en
     * dernier. Il n'a de valeur que de point de depart — chaque produit declare le sien.
     */
    public static AllocationOrder standard() {
        return new AllocationOrder(List.of(
            DueCategory.RECOVERY_FEES,
            DueCategory.PENALTIES,
            DueCategory.FEES_AND_INSURANCE,
            DueCategory.LATE_INTEREST,
            DueCategory.INTEREST,
            DueCategory.PRINCIPAL,
            DueCategory.FUTURE_PRINCIPAL));
    }

    /** Lit un ordre depuis un parametre produit : categories separees par des virgules. */
    public static AllocationOrder parse(String value) {
        Objects.requireNonNull(value, "value");
        List<DueCategory> categories = new ArrayList<>();
        for (String raw : value.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                categories.add(DueCategory.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IncompleteAllocationOrderException(
                    "« " + name + " » n'est pas une categorie de creance connue");
            }
        }
        return new AllocationOrder(categories);
    }

    /** Ordre d'imputation refuse. */
    public static class IncompleteAllocationOrderException extends RuntimeException {
        public IncompleteAllocationOrderException(String detail) {
            super("Ordre d'imputation : " + detail + ".");
        }
    }
}
