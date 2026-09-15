package io.corebanking.api.usecase;

import java.util.List;
import java.util.Objects;

/**
 * Pagination par pages numerotees, bornee.
 *
 * <p>Une liste sans borne est une requete qui finira par ramener toute la banque : la taille
 * d'une page est plafonnee, et une demande au-dela du plafond est refusee, pas ramenee en
 * silence au plafond — un client qui demande dix mille lignes doit le savoir. Chaque liste
 * paginee est lue dans un ordre total, sans quoi deux pages successives pourraient montrer deux
 * fois la meme ligne, ou n'en montrer aucune.
 */
public final class Paging {

    private Paging() {}

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /** @param number numero de page, a partir de zero */
    public record PageRequest(int number, int size) {

        public PageRequest {
            if (number < 0) {
                throw new InvalidPageException("Le numero de page commence a zero : " + number);
            }
            if (size < 1 || size > MAX_SIZE) {
                throw new InvalidPageException(
                    "La taille d'une page va de 1 a " + MAX_SIZE + " : " + size);
            }
        }

        public static PageRequest first() {
            return new PageRequest(0, DEFAULT_SIZE);
        }

        /** Depuis les parametres de requete, absents ou non. */
        public static PageRequest parse(String page, String size) {
            return new PageRequest(integer(page, 0, "page"), integer(size, DEFAULT_SIZE, "size"));
        }

        public int offset() {
            return number * size;
        }

        private static int integer(String value, int fallback, String name) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new InvalidPageException("Entier attendu pour " + name + " : " + value);
            }
        }
    }

    /** Une page d'une liste, avec ce qu'il faut pour en connaitre les bornes. */
    public record Paged<T>(List<T> items, PageRequest request, long total) {

        public Paged {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            Objects.requireNonNull(request, "request");
            if (total < 0) {
                throw new IllegalArgumentException("Total negatif : " + total);
            }
        }

        public int totalPages() {
            return (int) Math.ceil((double) total / request.size());
        }

        public boolean hasNext() {
            return (long) (request.number() + 1) * request.size() < total;
        }

        /** Une page decoupee en memoire dans une liste deja filtree — pour les listes courtes. */
        public static <T> Paged<T> slice(List<T> all, PageRequest request) {
            int from = Math.min(request.offset(), all.size());
            int to = Math.min(from + request.size(), all.size());
            return new Paged<>(all.subList(from, to), request, all.size());
        }
    }

    public static class InvalidPageException extends RuntimeException {
        public InvalidPageException(String detail) {
            super(detail);
        }
    }
}
