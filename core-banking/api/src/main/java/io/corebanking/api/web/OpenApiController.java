package io.corebanking.api.web;

import io.corebanking.api.openapi.OpenApiDocument;
import io.corebanking.api.openapi.Raw;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le contrat OpenAPI, publie par le service qui le tient : le document verse avec le code,
 * qu'un test tient egal a ce que les controleurs decrivent. Servi sans jeton — un contrat
 * n'est pas une donnee — et tel quel, hors enveloppe.
 */
@RestController
public class OpenApiController {

    private final String document;

    public OpenApiController() {
        try (InputStream in = OpenApiController.class.getResourceAsStream("/openapi/openapi.json")) {
            if (in == null) {
                throw new IllegalStateException(
                    "Contrat OpenAPI absent du classpath : openapi/openapi.json");
            }
            this.document = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture du contrat OpenAPI", e);
        }
    }

    @Raw
    @GetMapping(value = OpenApiDocument.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String contract() {
        return document;
    }
}
