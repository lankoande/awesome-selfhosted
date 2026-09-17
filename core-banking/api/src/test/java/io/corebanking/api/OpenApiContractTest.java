package io.corebanking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.corebanking.api.openapi.OpenApiDocument;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le contrat publie est celui que les controleurs decrivent. Ce test genere le document depuis
 * les controleurs et le compare au fichier verse : une route qui change sans que le contrat ne
 * bouge est un test rouge. Pour reprendre une evolution voulue :
 * {@code mvn -pl api test -Dtest=OpenApiContractTest -Dopenapi.update=true}.
 */
class OpenApiContractTest {

    private static final Path PUBLISHED = Path.of("src/main/resources/openapi/openapi.json");

    @Test
    @DisplayName("le contrat publie est celui que les controleurs decrivent ; toute derive se voit ici, et se met a jour en le disant")
    void the_published_contract_matches_the_controllers() throws Exception {
        List<Class<?>> controllers = controllers();
        assertThat(controllers).hasSizeGreaterThan(15);
        // Les cles sont ordonnees : le contrat verse se relit dans un diff, et une route ajoutee
        // se voit a sa place au lieu de deplacer le document entier.
        ObjectMapper json = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
        String rendered = json.writeValueAsString(OpenApiDocument.generate(controllers)) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(PUBLISHED.getParent());
            Files.writeString(PUBLISHED, rendered);
            fail("Contrat mis a jour dans " + PUBLISHED + " : le relire, puis relancer sans "
                 + "-Dopenapi.update pour le tenir");
        }
        assertThat(PUBLISHED).as("le contrat publie existe ; le generer avec -Dopenapi.update=true")
            .exists();
        JsonNode published = json.readTree(Files.readString(PUBLISHED));
        JsonNode generated = json.readTree(rendered);
        if (!published.equals(generated)) {
            fail("Le contrat publie ne decrit plus les controleurs.\n"
                 + differences(published, generated)
                 + "Reprendre l'evolution avec -Dopenapi.update=true, et verser le contrat.");
        }

        // Ce que tout contrat porte : la securite, l'enveloppe, les refus sur chaque route.
        assertThat(generated.get("openapi").asString()).isEqualTo("3.1.0");
        assertThat(generated.get("paths").size()).isGreaterThan(40);
        for (Iterator<Map.Entry<String, JsonNode>> paths = generated.get("paths").properties()
                .iterator(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            assertThat(path.getKey()).startsWith("/v1/");
            for (Iterator<Map.Entry<String, JsonNode>> operations = path.getValue().properties()
                    .iterator(); operations.hasNext();) {
                JsonNode operation = operations.next().getValue();
                assertThat(operation.has("operationId")).as(path.getKey()).isTrue();
                JsonNode responses = operation.get("responses");
                if (!OpenApiDocument.PATH.equals(path.getKey())) {
                    assertThat(responses.has("401")).as(path.getKey()).isTrue();
                    assertThat(responses.has("403")).as(path.getKey()).isTrue();
                }
            }
        }
    }

    private static String differences(JsonNode published, JsonNode generated) {
        StringBuilder out = new StringBuilder();
        for (String section : List.of("paths", "schemas")) {
            JsonNode before = "paths".equals(section) ? published.get("paths")
                : published.get("components").get("schemas");
            JsonNode after = "paths".equals(section) ? generated.get("paths")
                : generated.get("components").get("schemas");
            Set<String> gone = new HashSet<>(keys(before));
            gone.removeAll(keys(after));
            Set<String> added = new HashSet<>(keys(after));
            added.removeAll(keys(before));
            Set<String> changed = new HashSet<>();
            for (String key : keys(before)) {
                if (after.has(key) && !after.get(key).equals(before.get(key))) {
                    changed.add(key);
                }
            }
            out.append(section).append(" — ajoutes : ").append(new java.util.TreeSet<>(added))
               .append(" ; retires : ").append(new java.util.TreeSet<>(gone))
               .append(" ; modifies : ").append(new java.util.TreeSet<>(changed)).append('\n');
        }
        return out.toString();
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    /** Les controleurs, tels que compiles : tout ce qui est expose, sans liste a tenir. */
    private static List<Class<?>> controllers() throws Exception {
        URL directory = OpenApiContractTest.class.getResource("/io/corebanking/api/web");
        assertThat(directory).isNotNull();
        List<Class<?>> controllers = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            for (Path file : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                Class<?> type = Class.forName("io.corebanking.api.web." + name.replace(".class", ""));
                if (type.isAnnotationPresent(RestController.class)) {
                    controllers.add(type);
                }
            }
        }
        return controllers;
    }
}
