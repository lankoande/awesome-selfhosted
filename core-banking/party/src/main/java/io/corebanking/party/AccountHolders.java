package io.corebanking.party;

import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Titulaires d'un compte.
 *
 * <p>Un compte a au moins un titulaire, et un titulaire est un tiers de la meme entite. C'est le
 * lien qui rend possibles la vue client, l'agregation des engagements et le blocage d'un dossier
 * qui s'applique a tous ses comptes.
 */
public final class AccountHolders {

    private AccountHolders() {}

    public record Holder(UUID partyId, HolderRole role, LocalDate validFrom, LocalDate validTo) {}

    public static void attach(Connection c, UUID accountId, UUID partyId, HolderRole role,
                              LocalDate from, UUID actorId) {
        Party party = Parties.require(c, partyId);
        UUID accountEntity = entityOf(c, accountId);
        if (!party.legalEntityId().equals(accountEntity)) {
            throw new IllegalArgumentException(
                "Le tiers " + party.reference() + " releve d'une autre entite que le compte "
                + accountId + " : un titulaire est un tiers de la meme entite.");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_holder(account_id, party_id, role, valid_from, created_by)"
            + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, accountId);
            ps.setObject(2, partyId);
            ps.setString(3, role.name());
            ps.setObject(4, from);
            ps.setObject(5, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rattachement du titulaire " + party.reference(), e);
        }
    }

    /** Clot tous les roles en cours d'un compte a une date : cloture du compte. */
    public static int endAll(Connection c, UUID accountId, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account_holder SET valid_to = ? WHERE account_id = ? AND valid_to IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, accountId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Fin des titulaires du compte " + accountId, e);
        }
    }

    public static List<Holder> holdersOf(Connection c, UUID accountId, LocalDate at) {
        List<Holder> holders = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT party_id, role, valid_from, valid_to FROM account_holder"
            + " WHERE account_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)"
            + " ORDER BY role, valid_from")) {
            ps.setObject(1, accountId);
            ps.setObject(2, at);
            ps.setObject(3, at);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    holders.add(new Holder(rs.getObject(1, UUID.class),
                                           HolderRole.valueOf(rs.getString(2)),
                                           rs.getObject(3, LocalDate.class),
                                           rs.getObject(4, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Titulaires du compte " + accountId, e);
        }
        return holders;
    }

    /** Comptes dont le tiers est titulaire ou co-titulaire a une date. */
    public static List<UUID> accountsOf(Connection c, UUID partyId, LocalDate at) {
        List<UUID> accounts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT account_id FROM account_holder"
            + " WHERE party_id = ? AND role IN ('HOLDER','JOINT_HOLDER')"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, partyId);
            ps.setObject(2, at);
            ps.setObject(3, at);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    accounts.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Comptes du tiers " + partyId, e);
        }
        return accounts;
    }

    /**
     * Les titulaires d'un compte peuvent operer : au moins un titulaire, et aucun titulaire ni
     * co-titulaire bloque. Un mandataire bloque ne bloque pas le compte.
     */
    public static List<Holder> requireOperableHolders(Connection c, UUID accountId, LocalDate at) {
        List<Holder> holders = holdersOf(c, accountId, at);
        if (holders.stream().noneMatch(h -> h.role() == HolderRole.HOLDER)) {
            throw new IllegalStateException(
                "Le compte " + accountId + " n'a aucun titulaire : aucune operation n'y est "
                + "possible avant qu'un tiers en reponde.");
        }
        for (Holder holder : holders) {
            if (holder.role() == HolderRole.HOLDER || holder.role() == HolderRole.JOINT_HOLDER) {
                PartyService.requireOperable(c, holder.partyId());
            }
        }
        return holders;
    }

    private static UUID entityOf(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT legal_entity_id FROM account WHERE id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Compte inconnu : " + accountId);
                }
                return rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Entite du compte " + accountId, e);
        }
    }
}
