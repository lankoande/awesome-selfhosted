package io.corebanking.schema.expr;

/**
 * Expression inexploitable.
 *
 * <p>Ces erreurs sont levees au <b>chargement</b> du schema comptable, pas a son execution. Une
 * expression fautive doit etre refusee au deploiement du parametrage, jamais decouverte au milieu
 * d'un TFJ sur une operation client.
 */
public class ExpressionException extends RuntimeException {

    public ExpressionException(String message) {
        super(message);
    }

    public static ExpressionException at(String source, int position, String message) {
        return new ExpressionException(
            message + " — position " + position + " dans « " + source + " »");
    }
}
