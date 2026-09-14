package io.corebanking.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Schema comptable d'un produit : l'ensemble de ses traductions evenement vers ecritures.
 *
 * <p>C'est le pivot entre le metier et le ledger. Un module metier ne construit jamais d'ecriture :
 * il publie un evenement, le schema le traduit. Ajouter un produit, une commission ou une taxe
 * devient alors du parametrage, et la logique comptable reste en un seul endroit.
 */
public record AccountingSchema(String code, int version, Map<String, EventTemplate> templates) {

    public AccountingSchema {
        Objects.requireNonNull(code, "code");
        templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    public Optional<EventTemplate> templateFor(String eventType) {
        return Optional.ofNullable(templates.get(eventType));
    }

    public EventTemplate requireTemplate(String eventType) {
        return templateFor(eventType).orElseThrow(() -> new UnknownEventException(code, eventType));
    }

    public static Builder of(String code, int version) {
        return new Builder(code, version);
    }

    /**
     * Evenement sans traduction dans le schema.
     *
     * <p>Refus explicite : un evenement metier non comptabilise passerait inapercu jusqu'a l'arrete,
     * ou il apparaitrait comme un ecart sans origine identifiable.
     */
    public static class UnknownEventException extends RuntimeException {
        public UnknownEventException(String schemaCode, String eventType) {
            super("L'evenement « " + eventType + " » n'a aucune traduction dans le schema "
                  + schemaCode + ". Un evenement non comptabilise devient un ecart sans origine.");
        }
    }

    public static final class Builder {
        private final String code;
        private final int version;
        private final Map<String, EventTemplate> templates = new LinkedHashMap<>();

        private Builder(String code, int version) {
            this.code = code;
            this.version = version;
        }

        public Builder on(EventTemplate template) {
            templates.put(template.eventType(), template);
            return this;
        }

        public AccountingSchema build() {
            return new AccountingSchema(code, version, templates);
        }
    }
}
