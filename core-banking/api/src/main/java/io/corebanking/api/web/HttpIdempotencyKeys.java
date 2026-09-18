package io.corebanking.api.web;

import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * La cle d'idempotence de la requete HTTP en cours, pour les soumissions a double validation.
 *
 * <p>Pourquoi lire l'en-tete ici plutot que de l'ajouter en parametre des cinquante-huit methodes
 * qui soumettent : la cle ne change rien a ce que chaque controleur exprime — elle est la meme
 * mecanique de transport pour toutes. La porter cinquante-huit fois dans une signature aurait
 * surtout garanti qu'elle finisse par manquer a l'une d'elles, sans que rien ne le dise. Le
 * contrat, lui, l'annonce sur chaque route concernee : {@code OpenApiDocument} la declare des
 * qu'une methode rend une {@link MakerChecker.View}.
 *
 * <p>Hors d'une requete HTTP — un lot, un test — il n'y a pas de cle, et la soumission se fait
 * sans protection de rejeu, ce qui est le comportement d'avant.
 */
public final class HttpIdempotencyKeys implements MakerChecker.Keys {

    @Override
    public Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return Optional.empty();
        }
        return Optional.ofNullable(servlet.getRequest()
            .getHeader(WebConfiguration.IDEMPOTENCY_HEADER));
    }
}
