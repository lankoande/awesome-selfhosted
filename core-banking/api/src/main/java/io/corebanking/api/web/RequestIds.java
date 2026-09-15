package io.corebanking.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * L'identifiant d'une requete : celui que le client a fourni dans {@code X-Request-Id}, s'il est
 * sain, sinon un identifiant attribue. Il figure dans l'enveloppe et dans l'en-tete de reponse,
 * et c'est par lui qu'un incident se retrouve dans les journaux.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";

    private static final String ATTRIBUTE = RequestIds.class.getName();
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private RequestIds() {}

    public static String of(HttpServletRequest request) {
        Object known = request.getAttribute(ATTRIBUTE);
        if (known instanceof String id) {
            return id;
        }
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE.matcher(supplied.trim()).matches()
            ? supplied.trim() : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}
