package io.corebanking.ledger.store;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Montee de version du schema.
 *
 * <h2>Le defaut que cette classe ferme</h2>
 *
 * <p>Jusqu'ici, seul le premier script etait applique par le code ; les quatorze suivants l'etaient
 * a la main, par chaque base de test. Il n'existait donc <b>aucun moyen de monter une base hors des
 * tests</b>, et aucun moyen de prouver que deux environnements portaient le meme schema. Un socle
 * qui ne se deploie pas n'est pas un socle.
 *
 * <h2>Comment les scripts sont trouves</h2>
 *
 * <p>Chaque module livre ses scripts dans {@code db/} et les <b>declare</b> dans
 * {@code db/migrations.list}. Le runner reunit toutes les declarations presentes sur le classpath.
 * Declarer plutot que scanner n'est pas une commodite : l'enumeration d'un repertoire n'est pas
 * portable d'un jar a l'autre, et une declaration se relit. Lorsque le classpath est un
 * repertoire — le cas des tests —, le runner verifie que tout script present y est declare, de
 * sorte qu'un script oublie echoue dans les tests du module qui l'a ecrit.
 *
 * <h2>Ce qui est garanti</h2>
 *
 * <ul>
 *   <li><b>L'ordre.</b> Les versions s'appliquent en ordre croissant, et un script decouvert avec
 *       un numero inferieur a une version deja appliquee est refuse : c'est un script renumerote,
 *       ou un module ajoute apres coup, et dans les deux cas le schema ne serait plus celui que la
 *       numerotation decrit.</li>
 *   <li><b>L'integrite.</b> Chaque script applique laisse sa somme de controle. Un script modifie
 *       apres application est refuse a la montee suivante : un schema corrige en editant un script
 *       deja passe est un schema que plus personne ne sait reconstruire.</li>
 *   <li><b>La continuite</b>, par defaut : le classpath doit porter toutes les versions de 1 a la
 *       plus haute. Un module absent du deploiement se voit ici, pas au premier appel qui le
 *       demande. Les tests d'un module, qui ne voient que ses dependances, s'en dispensent
 *       explicitement.</li>
 *   <li><b>L'atomicite.</b> Tout ce qui est en attente s'applique dans une seule transaction,
 *       sous verrou consultatif. PostgreSQL rend le DDL transactionnel ; une montee de version qui
 *       echoue a mi-chemin ne laisse rien, et deux instances demarrees ensemble ne se marchent pas
 *       dessus.</li>
 * </ul>
 *
 * <p>La table de versions n'est pas protegee par un declencheur d'immuabilite, contrairement aux
 * journaux du socle : la reparer est un acte volontaire et rare — reprendre une base existante,
 * assumer une reecriture — qui doit rester possible, et qui se voit au journal de la base.
 */
public final class SchemaMigrator {

    /** Declaration des scripts d'un module : un nom de fichier par ligne. */
    public static final String MANIFEST = "db/migrations.list";

    private static final Pattern SCRIPT_NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_]+)\\.sql");

    /** Cle du verrou consultatif de montee de version, la meme pour toutes les instances. */
    private static final long LOCK_KEY = 0x434F5245424B4E47L;

    private SchemaMigrator() {}

    /** Tolerance aux versions manquantes sur le classpath. */
    public enum Gaps {
        /** Le classpath doit porter toutes les versions de 1 a la plus haute : deploiement. */
        REFUSED,
        /** Un sous-ensemble suffit : tests d'un module, qui ne voit que ses dependances. */
        TOLERATED
    }

    /** Un script tel qu'il est decouvert sur le classpath. */
    public record Migration(int version, String description, String resource, String sql,
                            String checksum) {}

    /** Ce qu'une montee de version a fait. */
    public record Report(List<Integer> applied, int alreadyApplied, int highest) {
        public Report {
            applied = List.copyOf(applied);
        }
    }

    // ------------------------------------------------------------------ montee de version

    /** Applique, en ordre, tout script declare sur le classpath et non encore applique. */
    public static Report migrate(Database database) {
        return migrate(database, Gaps.REFUSED);
    }

    public static Report migrate(Database database, Gaps gaps) {
        Map<Integer, Migration> available = discover();
        if (gaps == Gaps.REFUSED) {
            requireContiguous(available.keySet());
        }
        try (Connection c = database.dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                lock(c);
                createVersionTable(c);
                Map<Integer, String> applied = appliedChecksums(c);
                requireConsistent(available, applied);

                int highestApplied = applied.keySet().stream().max(Integer::compare).orElse(0);
                List<Integer> done = new ArrayList<>();
                for (Migration migration : available.values()) {
                    if (applied.containsKey(migration.version())) {
                        continue;
                    }
                    if (migration.version() < highestApplied) {
                        throw new MigrationException(
                            "ordre rompu : le script V" + migration.version() + " ("
                            + migration.description() + ") n'est pas applique alors que la base "
                            + "est en version " + highestApplied + ". Un script renumerote ou un "
                            + "module ajoute apres coup ne se rattrape pas en silence.");
                    }
                    apply(c, migration);
                    done.add(migration.version());
                }
                c.commit();
                int highest = available.keySet().stream().max(Integer::compare).orElse(0);
                return new Report(done, applied.size(), highest);
            } catch (RuntimeException | Error e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new MigrationException("montee de version impossible : " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ decouverte

    /**
     * Scripts declares sur le classpath, par version croissante.
     *
     * <p>Chaque script est lu depuis le meme emplacement que la declaration qui le nomme, et non
     * par une recherche globale : deux modules qui livreraient le meme nom de fichier ne se
     * masqueraient pas l'un l'autre, ils seraient refuses comme doublon.
     */
    public static Map<Integer, Migration> discover() {
        Map<Integer, Migration> found = new TreeMap<>();
        Enumeration<URL> manifests;
        try {
            manifests = SchemaMigrator.class.getClassLoader().getResources(MANIFEST);
        } catch (IOException e) {
            throw new MigrationException("lecture des declarations de scripts impossible", e);
        }
        if (!manifests.hasMoreElements()) {
            throw new MigrationException(
                "aucune declaration " + MANIFEST + " sur le classpath : aucun module ne livre de "
                + "schema, ou les ressources ne sont pas empaquetees.");
        }
        while (manifests.hasMoreElements()) {
            URL manifest = manifests.nextElement();
            List<String> names = declaredNames(manifest);
            requireEverythingDeclared(manifest, names);
            for (String name : names) {
                Migration migration = read(manifest, name);
                Migration duplicate = found.put(migration.version(), migration);
                if (duplicate != null) {
                    throw new MigrationException(
                        "version V" + migration.version() + " declaree deux fois : "
                        + duplicate.resource() + " et " + migration.resource());
                }
            }
        }
        return Collections.unmodifiableMap(found);
    }

    private static List<String> declaredNames(URL manifest) {
        List<String> names = new ArrayList<>();
        for (String line : text(manifest).split("\n")) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            if (!SCRIPT_NAME.matcher(name).matches()) {
                throw new MigrationException(
                    "nom de script invalide dans " + manifest + " : « " + name
                    + "», attendu V<numero>__<description>.sql");
            }
            names.add(name);
        }
        return names;
    }

    /**
     * Sur un classpath en repertoire, tout script present doit etre declare.
     *
     * <p>Un script ecrit et oublie de la declaration ne serait jamais applique, et la base
     * suivrait le code de loin sans que rien ne le signale. Le controle n'est possible que sur un
     * repertoire ; dans un jar, la declaration a deja ete eprouvee par les tests du module.
     */
    private static void requireEverythingDeclared(URL manifest, List<String> declared) {
        if (!"file".equals(manifest.getProtocol())) {
            return;
        }
        Path directory;
        try {
            directory = Path.of(manifest.toURI()).getParent();
        } catch (Exception e) {
            return;
        }
        Set<String> present = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.map(p -> p.getFileName().toString())
                 .filter(n -> SCRIPT_NAME.matcher(n).matches())
                 .forEach(present::add);
        } catch (IOException e) {
            throw new MigrationException("lecture du repertoire " + directory, e);
        }
        present.removeAll(declared);
        if (!present.isEmpty()) {
            throw new MigrationException(
                "scripts presents dans " + directory + " mais absents de " + MANIFEST + " : "
                + new TreeMap<>(Map.of("scripts", present)).get("scripts")
                + ". Un script non declare ne serait jamais applique.");
        }
    }

    private static Migration read(URL manifest, String name) {
        Matcher m = SCRIPT_NAME.matcher(name);
        m.matches();
        String base = manifest.toString();
        String location = base.substring(0, base.length() - MANIFEST.length()) + "db/" + name;
        URL script;
        try {
            script = new URL(location);
        } catch (IOException e) {
            throw new MigrationException("emplacement de script invalide : " + location, e);
        }
        String sql = text(script).replace("\r\n", "\n");
        return new Migration(Integer.parseInt(m.group(1)), m.group(2), location, sql, sha256(sql));
    }

    private static String text(URL url) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException("lecture impossible : " + url + " — " + e.getMessage(), e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    // ------------------------------------------------------------------ controles

    static void requireContiguous(Set<Integer> versions) {
        int highest = versions.stream().max(Integer::compare).orElse(0);
        List<Integer> missing = new ArrayList<>();
        for (int version = 1; version <= highest; version++) {
            if (!versions.contains(version)) {
                missing.add(version);
            }
        }
        if (!missing.isEmpty()) {
            throw new MigrationException(
                "versions absentes du classpath : " + missing + " (la plus haute est V" + highest
                + "). Un module manque au deploiement, ou un script a ete numerote trop loin. "
                + "Les tests d'un module tolerent ce trou explicitement, un deploiement non.");
        }
    }

    private static void requireConsistent(Map<Integer, Migration> available,
                                          Map<Integer, String> applied) {
        for (Map.Entry<Integer, String> entry : applied.entrySet()) {
            Migration migration = available.get(entry.getKey());
            if (migration == null) {
                throw new MigrationException(
                    "la base est en version V" + entry.getKey() + " mais aucun script de cette "
                    + "version n'est sur le classpath : un module a disparu du deploiement.");
            }
            if (!migration.checksum().equals(entry.getValue())) {
                throw new MigrationException(
                    "le script V" + entry.getKey() + " (" + migration.description()
                    + ") a ete modifie apres son application. Un schema corrige en editant un "
                    + "script deja passe est un schema que plus personne ne sait reconstruire : "
                    + "ecrire un nouveau script.");
            }
        }
    }

    // ------------------------------------------------------------------ base

    private static void lock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
        }
    }

    private static void createVersionTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS schema_version ("
                + " version     INTEGER PRIMARY KEY,"
                + " description TEXT NOT NULL,"
                + " checksum    TEXT NOT NULL,"
                + " applied_at  TIMESTAMPTZ NOT NULL DEFAULT now(),"
                + " duration_ms INTEGER NOT NULL)");
        }
    }

    private static Map<Integer, String> appliedChecksums(Connection c) throws SQLException {
        Map<Integer, String> applied = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT version, checksum FROM schema_version ORDER BY version")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    applied.put(rs.getInt(1), rs.getString(2));
                }
            }
        }
        return applied;
    }

    private static void apply(Connection c, Migration migration) {
        long start = System.nanoTime();
        try (Statement st = c.createStatement()) {
            st.execute(migration.sql());
        } catch (SQLException e) {
            throw new MigrationException(
                "echec du script V" + migration.version() + " (" + migration.description()
                + ") : " + e.getMessage() + ". Rien n'a ete applique.", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO schema_version(version, description, checksum, duration_ms)"
            + " VALUES (?,?,?,?)")) {
            ps.setInt(1, migration.version());
            ps.setString(2, migration.description());
            ps.setString(3, migration.checksum());
            ps.setInt(4, (int) ((System.nanoTime() - start) / 1_000_000));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MigrationException(
                "enregistrement de la version V" + migration.version() + " impossible", e);
        }
    }

    // ------------------------------------------------------------------ partitions

    /**
     * Cree les partitions mensuelles du journal sur une plage de dates.
     *
     * <p>Une partition manquante fait echouer toute comptabilisation sur la date concernee. C'est
     * volontaire — mieux vaut un refus explicite qu'une partition par defaut qui accueillerait
     * silencieusement des annees d'ecritures et rendrait l'archivage impossible. C'est le
     * traitement de fin de journee qui les anticipe, a chaque bascule.
     */
    public static void ensurePartitions(Database database, LocalDate from, LocalDate to) {
        database.inTransaction(connection -> {
            ensurePartitions(connection, from, to);
            return null;
        });
    }

    public static void ensurePartitions(Connection connection, LocalDate from, LocalDate to) {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT ledger_ensure_partitions(?, ?)")) {
            ps.setObject(1, from);
            ps.setObject(2, to);
            ps.execute();
        } catch (SQLException e) {
            throw new LedgerStoreException("Echec de creation des partitions", e);
        }
    }

    /** Montee de version refusee ou impossible. */
    public static class MigrationException extends LedgerStoreException {
        public MigrationException(String detail) {
            super("Schema : " + detail);
        }

        public MigrationException(String detail, Throwable cause) {
            super("Schema : " + detail, cause);
        }
    }
}
