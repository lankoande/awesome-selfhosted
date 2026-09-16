package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.TfjContext;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Recensement des comptes concernes par les etapes de masse. */
final class Portfolio {

    private Portfolio() {}

    /**
     * Comptes rattaches a un produit a la date traitee.
     *
     * <p>Les comptes dormants sont inclus. Un compte dormant continue de porter des interets et,
     * selon le parametrage, des frais de tenue : l'exclure reviendrait a decider a la place du
     * produit. Les comptes clos ne le sont pas : leur rattachement est ferme.
     */
    static List<UUID> accountsWithProduct(Database database, TfjContext context) {
        return database.inTransaction(c -> {
            List<UUID> accounts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                // Les comptes de depot a terme sont ecartes : leur interet n'est pas la
                // remuneration d'un solde au bareme du jour, c'est l'execution d'un contrat a
                // taux fige, que son etape constate ; et leur cout pour la banque est ce taux,
                // pas une grille de commissions. Les laisser ici les paierait deux fois, et au
                // mauvais prix.
                //
                // L'ecart se lit sur le code du produit, sans condition de version : la famille
                // fait partie de l'identite d'un produit et ne change pas d'une version a
                // l'autre. Un rattachement dont aucune version ne se resout reste dans le lot —
                // c'est un defaut de parametrage, et il doit etre nomme par l'etape qui bute
                // dessus, pas ecarte en silence.
                "SELECT DISTINCT a.id FROM account a"
                + " JOIN account_product ap ON ap.account_id = a.id"
                + " WHERE a.legal_entity_id = ? AND a.status IN ('ACTIVE','DORMANT')"
                + "   AND ap.valid_from <= ? AND (ap.valid_to IS NULL OR ap.valid_to >= ?)"
                + "   AND NOT EXISTS (SELECT 1 FROM product_version pv"
                + "                    WHERE pv.legal_entity_id = a.legal_entity_id"
                + "                      AND pv.code = ap.product_code"
                + "                      AND pv.product_type = 'TERM_DEPOSIT')"
                + " ORDER BY a.id")) {
                ps.setObject(1, context.legalEntityId());
                ps.setObject(2, context.businessDate());
                ps.setObject(3, context.businessDate());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        accounts.add(rs.getObject(1, UUID.class));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recensement des comptes rattaches a un produit", e);
            }
            return accounts;
        });
    }
}
