package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Referentiel : devises, entites juridiques, periodes comptables. */
public final class Entities {

    private Entities() {}

    public static void insertCurrency(Connection c, CurrencyRef currency, String name) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO currency(code, scale, name, rounding_mode) VALUES (?,?,?,?) "
            + "ON CONFLICT (code) DO NOTHING")) {
            ps.setString(1, currency.code());
            ps.setInt(2, currency.scale());
            ps.setString(3, name);
            ps.setString(4, currency.roundingMode().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion de la devise " + currency, e);
        }
    }

    public static void insertLegalEntity(Connection c, UUID id, String code, String name,
                                         String countryCode, CurrencyRef functionalCurrency,
                                         LocalDate businessDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO legal_entity(id, code, name, country_code, functional_currency, "
            + "current_business_date) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, countryCode);
            ps.setString(5, functionalCurrency.code());
            ps.setObject(6, businessDate);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion de l'entite " + code, e);
        }
        // Une entite nait avec son siege : sans lui, aucun compte client ne peut etre cree.
        Branches.createHeadOffice(c, id, "SIEGE", "Siege " + name, businessDate);
    }

    public static void openPeriod(Connection c, UUID entityId, LocalDate start, LocalDate end) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO accounting_period(id, legal_entity_id, start_date, end_date, status) "
            + "VALUES (?,?,?,?,'OPEN') ON CONFLICT (legal_entity_id, start_date) DO NOTHING")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, entityId);
            ps.setObject(3, start);
            ps.setObject(4, end);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Ouverture de periode", e);
        }
    }

    /**
     * Rouvre une periode close. C'est une decision comptable — l'annulation d'un arrete mensuel —
     * et elle laisse une trace : le statut REOPENED n'est pas OPEN.
     */
    public static void reopenPeriod(Connection c, UUID entityId, LocalDate start) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_period SET status = 'REOPENED' "
            + "WHERE legal_entity_id = ? AND start_date = ? AND status = 'CLOSED'")) {
            ps.setObject(1, entityId);
            ps.setObject(2, start);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture de periode", e);
        }
    }

    /** Bornes de la periode comptable couvrant une date, si elle existe. */
    public static Optional<LocalDate[]> periodBounds(Connection c, UUID entityId, LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT start_date, end_date FROM accounting_period "
            + "WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date")) {
            ps.setObject(1, entityId);
            ps.setObject(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                    ? Optional.of(new LocalDate[] {rs.getObject(1, LocalDate.class),
                                                   rs.getObject(2, LocalDate.class)})
                    : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des bornes de periode", e);
        }
    }

    public static void closePeriod(Connection c, UUID entityId, LocalDate start) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_period SET status = 'CLOSED' "
            + "WHERE legal_entity_id = ? AND start_date = ?")) {
            ps.setObject(1, entityId);
            ps.setObject(2, start);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de periode", e);
        }
    }

    /** Les devises dans lesquelles l'entite detient un solde non nul, devise de tenue comprise. */
    public static java.util.List<String> currenciesHeld(Connection c, UUID entityId) {
        java.util.List<String> currencies = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.currency FROM account a JOIN account_balance b ON b.account_id = a.id"
            + " WHERE a.legal_entity_id = ? GROUP BY a.currency"
            + " HAVING SUM(b.balance) <> 0 ORDER BY a.currency")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    currencies.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des devises detenues", e);
        }
        return currencies;
    }

    /** Une devise du referentiel, avec sa precision : une devise inconnue n'est pas une devise. */
    public static CurrencyRef requireCurrency(Connection c, String code) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT code, scale, rounding_mode FROM currency WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Devise inconnue du referentiel : " + code);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la devise " + code, e);
        }
    }

    public static CurrencyRef functionalCurrency(Connection c, UUID entityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode "
            + "FROM legal_entity e JOIN currency cur ON cur.code = e.functional_currency "
            + "WHERE e.id = ?")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite juridique inconnue : " + entityId);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la devise de tenue de compte", e);
        }
    }

    /** Statut de la periode couvrant une date, vide si aucune periode ne la couvre. */
    public static Optional<String> periodStatus(Connection c, UUID entityId, LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT status FROM accounting_period "
            + "WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date")) {
            ps.setObject(1, entityId);
            ps.setObject(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la periode comptable", e);
        }
    }

    /**
     * Garantit qu'une periode couvre la date : ouvre le mois civil si aucune ne la couvre.
     *
     * <p>Une periode deja presente est laissee telle quelle, <b>close comprise</b>. Rouvrir une
     * periode close est une decision comptable, jamais un effet de bord d'une bascule de journee ;
     * le controle prealable du traitement suivant la refusera, en la nommant.
     *
     * @return vrai si une periode a ete ouverte
     */
    public static boolean ensurePeriodCovering(Connection c, UUID entityId, LocalDate date) {
        if (periodStatus(c, entityId, date).isPresent()) {
            return false;
        }
        LocalDate start = date.withDayOfMonth(1);
        openPeriod(c, entityId, start, start.plusMonths(1).minusDays(1));
        return true;
    }

    /** Vrai si la date comptable tombe dans une periode ouverte de l'entite. */
    public static boolean isPeriodOpen(Connection c, UUID entityId, LocalDate bookingDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT status FROM accounting_period "
            + "WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date")) {
            ps.setObject(1, entityId);
            ps.setObject(2, bookingDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && ("OPEN".equals(rs.getString(1)) || "REOPENED".equals(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle de periode comptable", e);
        }
    }

    // ------------------------------------------------------------------ identite

    /**
     * L'identite de l'etablissement.
     *
     * <p>Elle etait implicite : un code, un nom, un pays, une devise de tenue poses a l'amorcage.
     * Ce qu'un etat reglementaire porte en en-tete — la denomination sociale, le numero
     * d'agrement, l'identifiant fiscal — et ce qu'un RIB porte en tete — le code banque —
     * n'existaient nulle part, et se retrouvaient recopies a la main dans chaque envoi.
     *
     * @param bankCode code banque attribue par la banque centrale, entete du RIB
     * @param legalName denomination sociale, quand elle differe du nom commercial
     * @param approvalNumber numero d'agrement bancaire
     * @param taxId identifiant fiscal unique
     * @param registryNumber numero au registre du commerce
     */
    public record Establishment(UUID id, String code, String name, String countryCode,
                                CurrencyRef functionalCurrency, LocalDate businessDate,
                                String status, String bankCode, String legalName,
                                String approvalNumber, String taxId, String registryNumber,
                                String address, String phone, String email) {}

    /** Ce qui se modifie apres coup. Le code, le pays et la devise de tenue n'en sont pas. */
    public record EstablishmentUpdate(String name, String bankCode, String legalName,
                                      String approvalNumber, String taxId, String registryNumber,
                                      String address, String phone, String email) {}

    private static final String SELECT_ESTABLISHMENT =
        "SELECT e.id, e.code, e.name, e.country_code, e.functional_currency, cur.scale,"
        + " cur.rounding_mode, e.current_business_date, e.status, e.bank_code, e.legal_name,"
        + " e.approval_number, e.tax_id, e.registry_number, e.address, e.phone, e.email"
        + "  FROM legal_entity e JOIN currency cur ON cur.code = e.functional_currency"
        + " WHERE e.id = ?";

    /** L'etablissement, ou un refus qui le nomme. */
    public static Establishment establishment(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_ESTABLISHMENT)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite juridique inconnue : " + id);
                }
                return new Establishment(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    new CurrencyRef(rs.getString(5), rs.getInt(6),
                                    RoundingMode.valueOf(rs.getString(7))),
                    rs.getObject(8, LocalDate.class), rs.getString(9), rs.getString(10),
                    rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                    rs.getString(15), rs.getString(16), rs.getString(17));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'etablissement " + id, e);
        }
    }

    /**
     * Met a jour l'identite de l'etablissement.
     *
     * <p>Le code, le pays et la devise de tenue ne se modifient pas : le premier est cite par le
     * parametrage, les deux autres sont poses dans chaque ecriture depuis le premier jour. Les
     * changer serait reecrire l'histoire comptable, pas corriger une fiche.
     *
     * <p>Le code banque, lui, se pose et se corrige — jusqu'au premier compte numerote avec lui.
     * Au-dela, le changer donnerait a deux comptes ouverts le meme jour deux RIB de banques
     * differentes ; le refus le dit.
     *
     * <p>Un champ <b>absent</b> reste ce qu'il etait ; un champ <b>vide</b> est efface. Sans cette
     * distinction, corriger un numero de telephone effacerait l'adresse.
     */
    public static void updateEstablishment(Connection c, UUID id, EstablishmentUpdate update) {
        Establishment before = establishment(c, id);
        String bankCode = merge(update.bankCode(), before.bankCode());
        if (!java.util.Objects.equals(bankCode, before.bankCode()) && before.bankCode() != null
            && numbersIssuedWithBankCode(c, id)) {
            throw new IllegalStateException(
                "Le code banque " + before.bankCode() + " a deja numerote des comptes : le "
                + "changer donnerait deux RIB de banques differentes a des comptes de la meme "
                + "banque. Un changement de code banque est une migration, pas une correction "
                + "de fiche.");
        }
        String name = merge(update.name(), before.name());
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Un etablissement porte un nom : il figure en "
                                               + "en-tete de chaque releve.");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE legal_entity SET name = ?, bank_code = ?, legal_name = ?,"
            + " approval_number = ?, tax_id = ?, registry_number = ?, address = ?, phone = ?,"
            + " email = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, bankCode);
            ps.setString(3, merge(update.legalName(), before.legalName()));
            ps.setString(4, merge(update.approvalNumber(), before.approvalNumber()));
            ps.setString(5, merge(update.taxId(), before.taxId()));
            ps.setString(6, merge(update.registryNumber(), before.registryNumber()));
            ps.setString(7, merge(update.address(), before.address()));
            ps.setString(8, merge(update.phone(), before.phone()));
            ps.setString(9, merge(update.email(), before.email()));
            ps.setObject(10, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de l'etablissement " + id, e);
        }
    }

    /** Absent, la valeur ne bouge pas ; vide, elle s'efface ; sinon, elle remplace. */
    private static String merge(String submitted, String current) {
        if (submitted == null) {
            return current;
        }
        return submitted.isBlank() ? null : submitted.trim();
    }

    private static boolean numbersIssuedWithBankCode(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM numbering_issue i JOIN numbering_segment s ON s.rule_id = i.rule_id"
            + " WHERE i.legal_entity_id = ? AND s.kind = 'BANK_CODE' LIMIT 1")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des numeros deja composes", e);
        }
    }
}
