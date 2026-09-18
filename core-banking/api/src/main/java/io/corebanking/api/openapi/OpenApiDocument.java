package io.corebanking.api.openapi;

import io.corebanking.api.usecase.Paging;
import io.corebanking.api.web.RequestIds;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.security.Caller;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * Le contrat OpenAPI 3.1 de l'API, genere depuis les controleurs eux-memes : chemins, methodes,
 * parametres, corps, reponses et refus sont lus dans les signatures et les annotations, jamais
 * ecrits a la main — ce qui est publie est ce qui est servi. Le document est fige dans
 * {@code openapi/openapi.json}, verse avec le code, et un test le compare a cette generation :
 * une route qui change sans que le contrat ne bouge est un test rouge.
 *
 * <p>Deterministe par construction — chemins, methodes et composants tries — pour que deux
 * generations du meme code rendent le meme texte, et qu'une difference soit une difference.
 */
public final class OpenApiDocument {

    /** Version du contrat : celle de l'API, portee par le prefixe {@code /v1}. */
    public static final String VERSION = "1.0.0";
    public static final String PATH = "/v1/openapi.json";

    private OpenApiDocument() {}

    public static Map<String, Object> generate(Collection<Class<?>> controllers) {
        Generator generator = new Generator();
        controllers.stream().sorted(Comparator.comparing(Class::getName)).forEach(generator::describe);
        return generator.document();
    }

    private static final class Generator {
        private final Map<String, Object> schemas = new TreeMap<>();
        private final Map<Class<?>, String> names = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> paths = new TreeMap<>();

        Map<String, Object> document() {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("openapi", "3.1.0");
            document.put("info", Map.of(
                "title", "Core banking",
                "version", VERSION,
                "description", "Socle core banking : comptes, operations, credit, parametrage, "
                    + "arretes, restitutions et etats financiers. Toute reponse porte "
                    + "l'enveloppe data / page / error / meta ; les montants sont des chaines "
                    + "avec leur devise ; les actes a double validation repondent 202 puis "
                    + "s'approuvent par un second porteur."));
            document.put("servers", List.of(Map.of("url", "/")));
            document.put("security", List.of(Map.of("bearerAuth", List.of())));
            document.put("paths", paths);
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("securitySchemes", Map.of("bearerAuth", Map.of(
                "type", "http", "scheme", "bearer", "bearerFormat", "JWT",
                "description", "Jeton d'acces Keycloak : l'entite, l'agence et les roles de "
                    + "l'appelant en sont derives, jamais d'un parametre.")));
            components.put("parameters", parameters());
            components.put("responses", errorResponses());
            standardSchemas();
            components.put("schemas", schemas);
            document.put("components", components);
            return document;
        }

        // ------------------------------------------------------------------ routes

        void describe(Class<?> controller) {
            RequestMapping base = controller.getAnnotation(RequestMapping.class);
            String prefix = base == null ? "" : first(base.value(), base.path());
            String tag = controller.getSimpleName().replace("Controller", "");
            Method[] methods = controller.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName)
                .thenComparing(Method::toGenericString));
            for (Method method : methods) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                Route route = routeOf(method);
                if (route == null) {
                    continue;
                }
                paths.computeIfAbsent(prefix + route.path(), key -> new TreeMap<>())
                    .put(route.verb(), operation(method, tag));
            }
        }

        private record Route(String verb, String path) {}

        private static Route routeOf(Method method) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                return new Route("get", first(get.value(), get.path()));
            }
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (post != null) {
                return new Route("post", first(post.value(), post.path()));
            }
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                return new Route("put", first(put.value(), put.path()));
            }
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            if (delete != null) {
                return new Route("delete", first(delete.value(), delete.path()));
            }
            PatchMapping patch = method.getAnnotation(PatchMapping.class);
            if (patch != null) {
                return new Route("patch", first(patch.value(), patch.path()));
            }
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            if (mapping != null && mapping.method().length == 1) {
                RequestMethod verb = mapping.method()[0];
                return new Route(verb.name().toLowerCase(java.util.Locale.ROOT),
                                 first(mapping.value(), mapping.path()));
            }
            return null;
        }

        private static String first(String[] value, String[] path) {
            if (value.length > 0) {
                return value[0];
            }
            return path.length > 0 ? path[0] : "";
        }

        /** La methode soumet-elle a double validation : une View rendue avec un 202. */
        private static boolean submitsForApproval(Method method) {
            ResponseStatus status = method.getAnnotation(ResponseStatus.class);
            return io.corebanking.api.web.MakerChecker.View.class
                       .isAssignableFrom(method.getReturnType())
                   && status != null && status.value() == HttpStatus.ACCEPTED;
        }

        private Map<String, Object> operation(Method method, String tag) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("operationId", tag + "." + method.getName());
            operation.put("tags", List.of(tag));
            List<Object> parameters = new ArrayList<>();
            parameters.add(ref("#/components/parameters/RequestId"));
            // Une soumission a double validation porte sa cle dans l'en-tete, pas dans sa
            // signature : la mecanique est la meme pour les cinquante-huit routes concernees, et
            // c'est ici qu'elle se declare, une fois, pour toutes. Le critere est la soumission
            // elle-meme — une reponse 202 — et non le type rendu : lire, approuver et rejeter
            // une operation en attente rendent aussi une View sans rien soumettre, et annoncer
            // une cle qui n'y sert a rien ferait mentir le contrat.
            if (submitsForApproval(method)) {
                parameters.add(ref("#/components/parameters/OptionalIdempotencyKey"));
            }
            Map<String, Object> requestBody = null;
            for (Parameter parameter : method.getParameters()) {
                Class<?> type = parameter.getType();
                if (Caller.class.isAssignableFrom(type)) {
                    continue;                         // derive du jeton, jamais un parametre
                }
                if (IdempotencyKey.class.isAssignableFrom(type)) {
                    parameters.add(ref("#/components/parameters/IdempotencyKey"));
                    continue;
                }
                if (Paging.PageRequest.class.equals(type)) {
                    parameters.add(ref("#/components/parameters/Page"));
                    parameters.add(ref("#/components/parameters/PageSize"));
                    continue;
                }
                if (Paging.CursorRequest.class.equals(type)) {
                    parameters.add(ref("#/components/parameters/After"));
                    parameters.add(ref("#/components/parameters/PageSize"));
                    continue;
                }
                PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                if (pathVariable != null) {
                    parameters.add(parameter(nameOf(pathVariable.value(), pathVariable.name(),
                                                    parameter), "path", true,
                                             schema(parameter.getParameterizedType())));
                    continue;
                }
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                if (requestParam != null) {
                    boolean required = requestParam.required()
                        && ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue());
                    parameters.add(parameter(nameOf(requestParam.value(), requestParam.name(),
                                                    parameter), "query", required,
                                             schema(parameter.getParameterizedType())));
                    continue;
                }
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    requestBody = Map.of("required", true, "content", Map.of(
                        "application/json", Map.of(
                            "schema", schema(parameter.getParameterizedType()))));
                    continue;
                }
                throw new IllegalStateException(
                    "Parametre que le contrat ne sait pas decrire : " + parameter + " de "
                    + method + ". Le decrire ici avant de l'exposer.");
            }
            operation.put("parameters", parameters);
            if (requestBody != null) {
                operation.put("requestBody", requestBody);
            }
            operation.put("responses", responses(method));
            return operation;
        }

        private static String nameOf(String value, String name, Parameter parameter) {
            if (!value.isEmpty()) {
                return value;
            }
            return name.isEmpty() ? parameter.getName() : name;
        }

        private static Map<String, Object> parameter(String name, String in, boolean required,
                                                     Object schema) {
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", name);
            parameter.put("in", in);
            parameter.put("required", required);
            parameter.put("schema", schema);
            return parameter;
        }

        // ------------------------------------------------------------------ reponses

        private Map<String, Object> responses(Method method) {
            Map<String, Object> responses = new TreeMap<>();
            if (method.isAnnotationPresent(Raw.class)) {
                responses.put("200", Map.of("description", "Le document, tel quel, hors enveloppe",
                    "content", Map.of("application/json", Map.of("schema", Map.of("type", "object")))));
                return responses;
            }
            Type returned = method.getGenericReturnType();
            ResponseStatus status = method.getAnnotation(ResponseStatus.class);
            if (returned instanceof ParameterizedType parameterized
                && ResponseEntity.class.equals(parameterized.getRawType())) {
                Type body = parameterized.getActualTypeArguments()[0];
                responses.put("201", success("Operation comptabilisee : le recu", body));
                responses.put("200", success("Rejeu d'une cle d'idempotence deja traitee : le "
                                             + "premier recu, rien n'est comptabilise deux fois",
                                             body));
            } else {
                String code = status == null ? "200" : String.valueOf(status.value().value());
                responses.put(code, success(switch (code) {
                    case "201" -> "Cree";
                    case "202" -> "Soumis a double validation : l'operation en attente, a "
                                  + "approuver ou rejeter par un second porteur habilite";
                    default -> "Succes";
                }, returned));
            }
            for (String error : List.of("400", "401", "403", "404", "409", "422", "500")) {
                responses.put(error, ref("#/components/responses/" + error));
            }
            return responses;
        }

        private Map<String, Object> success(String description, Type body) {
            return Map.of("description", description,
                          "content", Map.of("application/json", Map.of("schema", envelope(body))));
        }

        /** L'enveloppe de toute reponse : la donnee, ou les elements d'une page, et les bornes. */
        private Map<String, Object> envelope(Type body) {
            Object data;
            Object page = Map.of("type", "null");
            if (body instanceof ParameterizedType parameterized
                && (Paging.Paged.class.equals(parameterized.getRawType())
                    || Paging.Slice.class.equals(parameterized.getRawType()))) {
                data = array(schema(parameterized.getActualTypeArguments()[0]));
                page = ref("#/components/schemas/Page");
            } else if (body == void.class || body == Void.class) {
                data = Map.of("type", "null");
            } else {
                data = schema(body);
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("data", data);
            properties.put("page", page);
            properties.put("error", Map.of("type", "null"));
            properties.put("meta", ref("#/components/schemas/Meta"));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "object");
            envelope.put("properties", properties);
            envelope.put("required", List.of("data", "page", "error", "meta"));
            return envelope;
        }

        private static Map<String, Object> errorResponses() {
            Map<String, Object> responses = new TreeMap<>();
            Map<String, String> descriptions = new LinkedHashMap<>();
            descriptions.put("400", "Requete invalide : page hors bornes, parametre mal forme, "
                                    + "cle d'idempotence absente, corps illisible, curseur invalide");
            descriptions.put("401", "Aucun jeton valide");
            descriptions.put("403", "Habilitation refusee, ou jeton insuffisant");
            descriptions.put("404", "Ressource inconnue — ou d'une autre entite, que le "
                                    + "cloisonnement ne montre pas");
            descriptions.put("409", "Conflit d'etat : compte bloque, disponible insuffisant, "
                                    + "traitement refuse, exercice ou maquette dans un autre etat");
            descriptions.put("422", "Requete que le socle ne peut pas honorer : devise, montant, "
                                    + "parametrage incomplet ou invalide");
            descriptions.put("500", "Erreur non prevue : rien n'a ete comptabilise");
            descriptions.forEach((code, description) -> responses.put(code, Map.of(
                "description", description,
                "content", Map.of("application/json", Map.of(
                    "schema", ref("#/components/schemas/ErrorEnvelope"))))));
            return responses;
        }

        private static Map<String, Object> parameters() {
            Map<String, Object> parameters = new TreeMap<>();
            parameters.put("RequestId", header(RequestIds.HEADER, false,
                Map.of("type", "string", "pattern", "^[A-Za-z0-9._:-]{1,64}$"),
                "Identifiant de requete du client, repris dans meta.requestId et en en-tete de "
                + "reponse ; attribue s'il est absent ou mal forme"));
            parameters.put("IdempotencyKey", header("Idempotency-Key", true,
                Map.of("type", "string"),
                "Cle d'idempotence : un rejeu repond 200 avec le premier recu"));
            parameters.put("OptionalIdempotencyKey", header("Idempotency-Key", false,
                Map.of("type", "string"),
                "Cle d'idempotence de la soumission. Envoyee, la meme cle avec la meme requete "
                + "rend la demande d'origine au lieu d'en creer une seconde ; avec une requete "
                + "differente, 409. Omise, un envoi rejoue cree une seconde demande."));
            parameters.put("Page", query("page", Map.of("type", "integer", "minimum", 0, "default", 0),
                "Numero de page, a partir de zero"));
            parameters.put("PageSize", query("size",
                Map.of("type", "integer", "minimum", 1, "maximum", Paging.MAX_SIZE,
                       "default", Paging.DEFAULT_SIZE),
                "Taille de page ; au-dela du maximum, 400"));
            parameters.put("After", query("after", Map.of("type", "string"),
                "Curseur rendu par la page precedente (page.nextCursor), tel quel ; absent pour "
                + "commencer"));
            return parameters;
        }

        private static Map<String, Object> header(String name, boolean required, Object schema,
                                                  String description) {
            Map<String, Object> parameter = parameter(name, "header", required, schema);
            parameter.put("description", description);
            return parameter;
        }

        private static Map<String, Object> query(String name, Object schema, String description) {
            Map<String, Object> parameter = parameter(name, "query", false, schema);
            parameter.put("description", description);
            return parameter;
        }

        private void standardSchemas() {
            schemas.put("Meta", object(Map.of(
                "timestamp", Map.of("type", "string", "format", "date-time"),
                "requestId", Map.of("type", "string")), List.of("timestamp", "requestId")));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("number", nullable("integer"));
            page.put("size", Map.of("type", "integer"));
            page.put("totalElements", nullable("integer"));
            page.put("totalPages", nullable("integer"));
            page.put("hasNext", Map.of("type", "boolean"));
            page.put("hasPrevious", Map.of("type", "boolean"));
            page.put("nextCursor", nullable("string"));
            schemas.put("Page", object(page, List.of("size", "hasNext", "hasPrevious")));
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", Map.of("type", "string"));
            error.put("title", Map.of("type", "string"));
            error.put("status", Map.of("type", "integer"));
            error.put("detail", Map.of("type", "string"));
            error.put("instance", Map.of("type", "string"));
            schemas.put("Error", object(error, List.of("type", "title", "status")));
            Map<String, Object> errorEnvelope = new LinkedHashMap<>();
            errorEnvelope.put("data", Map.of("type", "null"));
            errorEnvelope.put("page", Map.of("type", "null"));
            errorEnvelope.put("error", ref("#/components/schemas/Error"));
            errorEnvelope.put("meta", ref("#/components/schemas/Meta"));
            schemas.put("ErrorEnvelope", object(errorEnvelope, List.of("data", "page", "error", "meta")));
            Map<String, Object> money = new LinkedHashMap<>();
            money.put("amount", Map.of("type", "string",
                "description", "Montant en chaine, a l'echelle de la devise ; jamais un flottant"));
            money.put("currency", Map.of("type", "string", "description", "Code ISO 4217"));
            schemas.put("Money", object(money, List.of("amount", "currency")));
        }

        // ------------------------------------------------------------------ schemas

        private Object schema(Type type) {
            if (type instanceof ParameterizedType parameterized) {
                Class<?> raw = (Class<?>) parameterized.getRawType();
                Type[] arguments = parameterized.getActualTypeArguments();
                if (Collection.class.isAssignableFrom(raw)) {
                    return array(schema(arguments[0]));
                }
                if (Map.class.isAssignableFrom(raw)) {
                    return Map.of("type", "object", "additionalProperties", schema(arguments[1]));
                }
                if (Optional.class.equals(raw) || ResponseEntity.class.equals(raw)) {
                    return schema(arguments[0]);
                }
                if (Paging.Paged.class.equals(raw) || Paging.Slice.class.equals(raw)) {
                    return array(schema(arguments[0]));
                }
                return schema(raw);
            }
            if (type instanceof GenericArrayType generic) {
                return array(schema(generic.getGenericComponentType()));
            }
            if (type instanceof Class<?> c) {
                return classSchema(c);
            }
            return Map.of();                          // variable de type : n'importe quelle valeur
        }

        private Object classSchema(Class<?> c) {
            if (c == String.class || CharSequence.class.isAssignableFrom(c) || c == char.class
                || c == Character.class) {
                return Map.of("type", "string");
            }
            if (c == boolean.class || c == Boolean.class) {
                return Map.of("type", "boolean");
            }
            if (c == int.class || c == Integer.class || c == short.class || c == Short.class
                || c == byte.class || c == Byte.class) {
                return Map.of("type", "integer", "format", "int32");
            }
            if (c == long.class || c == Long.class) {
                return Map.of("type", "integer", "format", "int64");
            }
            if (c == double.class || c == Double.class || c == float.class || c == Float.class
                || c == BigDecimal.class || c == BigInteger.class) {
                return Map.of("type", "number");
            }
            if (c == UUID.class) {
                return Map.of("type", "string", "format", "uuid");
            }
            if (c == LocalDate.class) {
                return Map.of("type", "string", "format", "date");
            }
            if (c == Instant.class || c == OffsetDateTime.class || c == ZonedDateTime.class
                || c == LocalDateTime.class) {
                return Map.of("type", "string", "format", "date-time");
            }
            if (c == Duration.class) {
                return Map.of("type", "string", "format", "duration");
            }
            if (c == Money.class) {
                return ref("#/components/schemas/Money");
            }
            if (c.isEnum()) {
                List<String> values = Arrays.stream(c.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name()).toList();
                return Map.of("type", "string", "enum", values);
            }
            if (c == Object.class) {
                return Map.of();
            }
            if (c == void.class || c == Void.class) {
                return Map.of("type", "null");
            }
            if (c.isArray()) {
                return array(schema(c.getComponentType()));
            }
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                return Map.of("type", "object");
            }
            return ref("#/components/schemas/" + component(c));
        }

        /** Un type compose devient un composant nomme, refere ; la recursion s'arrete au nom. */
        private String component(Class<?> c) {
            String known = names.get(c);
            if (known != null) {
                return known;
            }
            String name = nameFor(c);
            names.put(c, name);
            Map<String, Object> properties = new LinkedHashMap<>();
            if (c.isRecord()) {
                for (RecordComponent component : c.getRecordComponents()) {
                    properties.put(component.getName(), schema(component.getGenericType()));
                }
            } else {
                Method[] methods = c.getMethods();
                Arrays.sort(methods, Comparator.comparing(Method::getName));
                for (Method method : methods) {
                    if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || method.getReturnType() == void.class
                        || method.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    String property = propertyOf(method.getName());
                    if (property != null) {
                        properties.put(property, schema(method.getGenericReturnType()));
                    }
                }
            }
            schemas.put(name, object(properties, List.of()));
            return name;
        }

        private static String propertyOf(String method) {
            if (method.startsWith("get") && method.length() > 3) {
                return decapitalise(method.substring(3));
            }
            if (method.startsWith("is") && method.length() > 2) {
                return decapitalise(method.substring(2));
            }
            return null;
        }

        private static String decapitalise(String name) {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        private String nameFor(Class<?> c) {
            String simple = c.getEnclosingClass() == null ? c.getSimpleName()
                : c.getEnclosingClass().getSimpleName() + "." + c.getSimpleName();
            if (!names.containsValue(simple) && !schemas.containsKey(simple)) {
                return simple;
            }
            return c.getName().replace('$', '.');
        }

        private static Map<String, Object> object(Map<String, Object> properties,
                                                  List<String> required) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }

        private static Map<String, Object> array(Object items) {
            return Map.of("type", "array", "items", items);
        }

        private static Map<String, Object> nullable(String type) {
            return Map.of("type", List.of(type, "null"));
        }

        private static Map<String, Object> ref(String target) {
            return Map.of("$ref", target);
        }
    }
}
