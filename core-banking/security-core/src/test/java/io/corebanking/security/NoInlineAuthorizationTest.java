package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Interdit les annotations d'habilitation dans tout le code de production.
 *
 * <p>La decision d'architecture est que la politique vit dans {@link SecurityConfig} et nulle part
 * ailleurs. Une decision de ce type ne tient pas par la discipline : elle tient parce qu'une
 * infraction casse la construction. Sans ce test, la premiere annotation reapparait a la premiere
 * livraison pressee, et la table centrale cesse d'etre la source de verite sans que personne ne le
 * remarque — ce qui est pire que de ne jamais l'avoir centralisee, car on continue de faire
 * confiance a la matrice imprimee.
 */
class NoInlineAuthorizationTest {

    private static final List<String> INTERDITES = List.of(
        "@PreAuthorize", "@PostAuthorize", "@Secured", "@RolesAllowed", "@PermitAll", "@DenyAll");

    @Test
    @DisplayName("aucune annotation d'habilitation dans le code de production")
    void no_authorization_annotation_anywhere() throws IOException {
        Path racine = projectRoot();
        List<String> infractions = new ArrayList<>();

        try (Stream<Path> fichiers = Files.walk(racine)) {
            fichiers.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                .forEach(path -> {
                    String contenu = stripComments(read(path));
                    for (String annotation : INTERDITES) {
                        if (contenu.contains(annotation)) {
                            infractions.add(racine.relativize(path) + " : " + annotation);
                        }
                    }
                });
        }

        assertThat(infractions)
            .as("La politique d'habilitation est centralisee dans SecurityConfig. "
                + "Toute annotation d'habilitation la contourne et la rend fausse.")
            .isEmpty();
    }

    @Test
    @DisplayName("le test sait detecter une infraction, sinon il ne prouverait rien")
    void the_detector_actually_detects() {
        String faux = "public class X { @PreAuthorize(\"hasRole('X')\") void f() {} }";
        assertThat(INTERDITES.stream().anyMatch(stripComments(faux)::contains)).isTrue();
    }

    @Test
    @DisplayName("une annotation citee dans un commentaire n'est pas une infraction")
    void an_annotation_quoted_in_a_comment_is_not_a_breach() {
        // Les javadoc de SecurityConfig expliquent precisement pourquoi ces annotations sont
        // proscrites ; les citer ne doit pas declencher le garde-fou.
        String commente = """
            /** Aucune annotation @PreAuthorize n'est utilisee ici. */
            public class X {
                // ni @Secured, ni @RolesAllowed
                void f() {}
            }
            """;
        assertThat(INTERDITES.stream().anyMatch(stripComments(commente)::contains)).isFalse();
    }

    /**
     * Retire commentaires de bloc et de ligne. Une annotation citee dans une javadoc — ce que font
     * precisement les classes qui expliquent pourquoi elles sont proscrites — n'est pas une
     * infraction.
     */
    static String stripComments(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) {
                int fin = source.indexOf("*/", i + 2);
                i = fin < 0 ? source.length() : fin + 2;
            } else if (source.startsWith("//", i)) {
                int fin = source.indexOf('\n', i);
                i = fin < 0 ? source.length() : fin;
            } else {
                sb.append(source.charAt(i++));
            }
        }
        return sb.toString();
    }

    private static Path projectRoot() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null && !Files.exists(courant.resolve("pom.xml").normalize())) {
            courant = courant.getParent();
        }
        // Remonter jusqu'au pom agregateur, qui declare les modules.
        Path candidat = courant;
        while (candidat != null && candidat.getParent() != null
               && Files.exists(candidat.getParent().resolve("pom.xml"))) {
            candidat = candidat.getParent();
        }
        return candidat == null ? Path.of("").toAbsolutePath() : candidat;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de " + path, e);
        }
    }
}
