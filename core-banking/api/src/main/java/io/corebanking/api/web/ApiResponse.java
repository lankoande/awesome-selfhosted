package io.corebanking.api.web;

import io.corebanking.api.usecase.Paging;
import java.time.Instant;
import java.util.List;

/**
 * L'enveloppe de toute reponse de l'API, succes ou refus : un client ne lit qu'une forme.
 *
 * <ul>
 *   <li>{@code data} : la donnee, ou la liste des elements de la page ; nulle sur un refus ;</li>
 *   <li>{@code page} : les bornes de la page quand la donnee est une liste paginee — numero
 *       et decompte par pages numerotees, {@code nextCursor} par curseur ;</li>
 *   <li>{@code error} : le refus, avec les champs de RFC 9457 (type, title, status, detail,
 *       instance) ; nul sur un succes ;</li>
 *   <li>{@code meta} : l'horodatage et l'identifiant de requete, repris de l'en-tete
 *       {@code X-Request-Id} ou attribue — ce que le support demande en premier.</li>
 * </ul>
 *
 * <p>Le statut HTTP reste porteur de sens : l'enveloppe le repete dans {@code error.status},
 * elle ne le remplace pas.
 */
public record ApiResponse<T>(T data, Page page, Error error, Meta meta) {

    /**
     * Les bornes d'une liste. Par pages numerotees : numero, decompte et nombre de pages. Par
     * curseur : ni numero ni decompte, mais {@code nextCursor}, a rendre tel quel pour la page
     * suivante ; nul sur la derniere.
     */
    public record Page(Integer number, int size, Long totalElements, Integer totalPages,
                       boolean hasNext, boolean hasPrevious, String nextCursor) {
        static Page of(Paging.Paged<?> paged) {
            return new Page(paged.request().number(), paged.request().size(), paged.total(),
                            paged.totalPages(), paged.hasNext(), paged.request().number() > 0,
                            null);
        }

        static Page of(Paging.Slice<?> slice) {
            return new Page(null, slice.request().size(), null, null, slice.hasNext(),
                            slice.hasPrevious(), slice.nextCursor());
        }
    }

    public record Error(String type, String title, int status, String detail, String instance) {}

    public record Meta(Instant timestamp, String requestId) {}

    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, null, null, meta(requestId));
    }

    public static <T> ApiResponse<List<T>> of(Paging.Paged<T> paged, String requestId) {
        return new ApiResponse<>(paged.items(), Page.of(paged), null, meta(requestId));
    }

    public static <T> ApiResponse<List<T>> of(Paging.Slice<T> slice, String requestId) {
        return new ApiResponse<>(slice.items(), Page.of(slice), null, meta(requestId));
    }

    public static ApiResponse<Void> error(int status, String title, String detail, String instance,
                                          String requestId) {
        return new ApiResponse<>(null, null,
                                 new Error("urn:corebanking:problem:" + status, title, status,
                                           detail, instance),
                                 meta(requestId));
    }

    private static Meta meta(String requestId) {
        return new Meta(Instant.now(), requestId);
    }
}
