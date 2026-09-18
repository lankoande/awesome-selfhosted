package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Referentiel des comptes et amorcage de leurs sous-soldes. */
public final class Accounts {

    private Accounts() {}

    /**
     * Cree un compte et <b>toutes</b> ses lignes de solde, une par stripe.
     *
     * <p>Les lignes sont creees ici plutot qu'a la premiere imputation : la comptabilisation n'a
     * alors qu'a faire un {@code UPDATE}, sans {@code INSERT ... ON CONFLICT} qui, sur un compte
     * chaud, redeviendrait un point de contention.
     */
    public static void create(Connection c, Account account) {
        create(c, account, businessDateOf(c, account.legalEntityId()));
    }

    /**
     * Cree un compte ouvert a une date donnee.
     *
     * <p>La date d'ouverture est une donnee de gestion, pas un horodatage : une reprise de
     * portefeuille ouvre des comptes anterieurs a la migration, et une saisie d'agence enregistre
     * parfois l'ouverture de la veille. Elle decide de la proratisation des commissions et du
     * premier jour de calcul des interets ; la prendre a l'horloge fausserait les deux.
     */
    public static void create(Connection c, Account account, LocalDate openedAt) {
        UUID branch = branchFor(c, account);
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account(id, legal_entity_id, code, account_kind, normal_balance, currency,"
            + " gl_account_id, contract_id, postable, control_available, stripe_count, status,"
            + " opened_at, branch_id, nature) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, account.id());
            ps.setObject(2, account.legalEntityId());
            ps.setString(3, account.code());
            ps.setString(4, account.kind().name());
            ps.setString(5, account.normalBalance().name());
            ps.setString(6, account.currency().code());
            ps.setObject(7, null);
            ps.setObject(8, null);
            ps.setBoolean(9, account.postable());
            ps.setBoolean(10, account.controlAvailable());
            ps.setInt(11, account.stripeCount());
            ps.setString(12, account.status().name());
            ps.setObject(13, openedAt);
            ps.setObject(14, branch);
            ps.setString(15, account.nature().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du compte " + account.code(), e);
        }

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_balance(account_id, stripe_id) VALUES (?,?)")) {
            for (int stripe = 0; stripe < account.stripeCount(); stripe++) {
                ps.setObject(1, account.id());
                ps.setInt(2, stripe);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Amorcage des soldes du compte " + account.code(), e);
        }
    }

    /** Date comptable courante de l'entite : c'est elle qui date l'ouverture, pas l'horloge. */
    /**
     * Agence du compte a la creation : celle qu'il precise ; le siege pour un compte client ou
     * interne qui n'en precise pas ; aucune pour un compte general, dont le solde se tient par
     * agence en dimension de ligne — lui en donner une serait une erreur de modele, pas un choix.
     */
    private static UUID branchFor(Connection c, Account account) {
        boolean managed = account.kind() == AccountKind.CUSTOMER
                          || account.kind() == AccountKind.INTERNAL;
        if (managed) {
            return account.branchId() != null ? account.branchId()
                                              : Branches.headOffice(c, account.legalEntityId());
        }
        if (account.branchId() != null && account.kind() != AccountKind.SUSPENSE) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " est un compte " + account.kind() + " : il n'a "
                + "pas d'agence, son solde se tient par agence sur chacune de ses lignes");
        }
        return account.branchId();
    }

    private static LocalDate businessDateOf(Connection c, java.util.UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la date comptable de l'entite", e);
        }
    }

    /** Charge en une requete tous les comptes references par une commande. */
    public static Map<UUID, Account> loadAll(Connection c, Collection<UUID> ids) {
        Map<UUID, Account> accounts = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return accounts;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.legal_entity_id, a.code, a.account_kind, a.normal_balance,"
            + " cur.code, cur.scale, cur.rounding_mode,"
            + " a.postable, a.control_available, a.stripe_count, a.status, a.branch_id, a.nature"
            + " FROM account a JOIN currency cur ON cur.code = a.currency"
            + " WHERE a.id = ANY (?)")) {
            Array array = c.createArrayOf("uuid", ids.toArray());
            ps.setArray(1, array);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Account account = new Account(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        AccountKind.valueOf(rs.getString(4)),
                        NormalBalance.valueOf(rs.getString(5)),
                        new CurrencyRef(rs.getString(6), rs.getInt(7),
                                        RoundingMode.valueOf(rs.getString(8))),
                        rs.getBoolean(9),
                        rs.getBoolean(10),
                        rs.getInt(11),
                        AccountStatus.valueOf(rs.getString(12)),
                        rs.getObject(13, UUID.class),
                        io.corebanking.ledger.domain.account.AccountNature.valueOf(
                            rs.getString(14)));
                    accounts.put(account.id(), account);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Chargement des comptes", e);
        }
        return accounts;
    }

    // ------------------------------------------------------------------ recherche

    /**
     * Un compte client tel qu'un ecran le presente : ce qu'il faut pour le reconnaitre et
     * l'ouvrir, pas plus. Le solde n'y est pas — il se lit compte par compte, et il est trace.
     *
     * @param branchCode code de l'agence gestionnaire, pour dire ou le compte est tenu
     * @param holderReference reference du titulaire, telle que le referentiel la donne
     */
    public record Summary(UUID id, String code, String currency, String status, UUID branchId,
                          String branchCode, String productCode, UUID holderId,
                          String holderReference, String holderName, LocalDate openedOn) {}

    private static final String SEARCH_SELECT = """
        SELECT a.id, a.code, a.currency, a.status, a.branch_id, b.code, p.product_code,
               h.party_id, t.reference, t.display_name, a.opened_at
          FROM account a
          JOIN branch b ON b.id = a.branch_id
          LEFT JOIN account_product p ON p.account_id = a.id AND p.valid_to IS NULL
          LEFT JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                                    AND h.valid_to IS NULL
          LEFT JOIN party t ON t.id = h.party_id
         WHERE a.legal_entity_id = ? AND a.account_kind = 'CUSTOMER'
           AND (?::uuid IS NULL OR h.party_id = ?::uuid)
           AND (?::uuid IS NULL OR a.branch_id = ?::uuid)
           AND (?::text IS NULL OR a.code ILIKE ? OR t.display_name ILIKE ?
                OR t.reference ILIKE ?)
        """;

    /**
     * Les comptes clients de l'entite, filtres par titulaire, par agence, ou par un fragment de
     * numero, de nom ou de reference client.
     *
     * <p>L'ordre est total — numero de compte — pour qu'une pagination ne rende pas deux fois la
     * meme ligne ni n'en saute une.
     *
     * <p>Seuls les comptes <b>clients</b> sont rendus. Les comptes generaux, internes, nostro, de
     * suspens et de position sont de la comptabilite : ils se lisent par la balance et le grand
     * livre, pas par un ecran de guichet.
     */
    public static List<Summary> search(Connection c, UUID legalEntityId, UUID partyId,
                                       UUID branchId, String text, int offset, int limit) {
        String motif = text == null || text.isBlank() ? null : "%" + text.trim() + "%";
        try (PreparedStatement ps = c.prepareStatement(
            SEARCH_SELECT + " ORDER BY a.code OFFSET ? LIMIT ?")) {
            bindSearch(ps, legalEntityId, partyId, branchId, motif);
            ps.setInt(10, Math.max(0, offset));
            ps.setInt(11, Math.max(1, limit));
            List<Summary> comptes = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    comptes.add(new Summary(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6),
                        rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9),
                        rs.getString(10), rs.getObject(11, LocalDate.class)));
                }
            }
            return List.copyOf(comptes);
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche des comptes clients", e);
        }
    }

    /** Le nombre de comptes que la meme recherche rend, pour la pagination. */
    public static long countSearch(Connection c, UUID legalEntityId, UUID partyId, UUID branchId,
                                   String text) {
        String motif = text == null || text.isBlank() ? null : "%" + text.trim() + "%";
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM (" + SEARCH_SELECT + ") q")) {
            bindSearch(ps, legalEntityId, partyId, branchId, motif);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Comptage des comptes clients", e);
        }
    }

    private static void bindSearch(PreparedStatement ps, UUID legalEntityId, UUID partyId,
                                   UUID branchId, String motif) throws SQLException {
        ps.setObject(1, legalEntityId);
        ps.setObject(2, partyId);
        ps.setObject(3, partyId);
        ps.setObject(4, branchId);
        ps.setObject(5, branchId);
        ps.setString(6, motif);
        ps.setString(7, motif);
        ps.setString(8, motif);
        ps.setString(9, motif);
    }
}
