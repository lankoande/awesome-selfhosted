package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.ledger.store.Accounts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La liste des comptes clients : ce qu'un ecran peut enfin proposer au lieu de faire saisir un
 * identifiant technique.
 */
class AccountSearchIT extends DepositsTestBase {

    private List<Accounts.Summary> chercher(Decor decor, UUID partyId, UUID branchId,
                                            String texte) {
        return database.inTransaction(
            c -> Accounts.search(c, decor.entityId(), partyId, branchId, texte, 0, 50));
    }

    private long compter(Decor decor, UUID partyId, UUID branchId, String texte) {
        return database.inTransaction(
            c -> Accounts.countSearch(c, decor.entityId(), partyId, branchId, texte));
    }

    @Test
    @DisplayName("les comptes d'un titulaire, avec ce qu'il faut pour les reconnaitre")
    void the_accounts_of_a_holder() {
        Decor decor = decor("SEARCH-1");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID client = client(decor.entityId(), "CLI-0001");
        UUID courant = ouvrir(decor, "00110001", "CPTE-CHQ", client);
        UUID epargne = ouvrir(decor, "00110002", "CPTE-CHQ", client);

        List<Accounts.Summary> comptes = chercher(decor, client, null, null);

        assertThat(comptes).extracting(Accounts.Summary::id)
            .containsExactly(courant, epargne);
        assertThat(comptes.getFirst().code()).isEqualTo("00110001");
        assertThat(comptes.getFirst().currency()).isEqualTo("XOF");
        assertThat(comptes.getFirst().productCode()).isEqualTo("CPTE-CHQ");
        assertThat(comptes.getFirst().holderReference()).isEqualTo("CLI-0001");
        assertThat(comptes.getFirst().holderName()).isEqualTo("Client CLI-0001");
        assertThat(comptes.getFirst().branchCode()).isNotBlank();
    }

    @Test
    @DisplayName("le compte d'un autre client ne remonte pas sur le titulaire demande")
    void another_holder_is_not_returned() {
        Decor decor = decor("SEARCH-2");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID premier = client(decor.entityId(), "CLI-0010");
        UUID second = client(decor.entityId(), "CLI-0011");
        ouvrir(decor, "00120001", "CPTE-CHQ", premier);
        UUID autre = ouvrir(decor, "00120002", "CPTE-CHQ", second);

        assertThat(chercher(decor, second, null, null)).extracting(Accounts.Summary::id)
            .containsExactly(autre);
        assertThat(compter(decor, second, null, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("la recherche porte sur le numero, le nom du titulaire et sa reference")
    void the_text_search_covers_number_name_and_reference() {
        Decor decor = decor("SEARCH-3");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID client = client(decor.entityId(), "CLI-7788");
        UUID compte = ouvrir(decor, "00130042", "CPTE-CHQ", client);

        assertThat(chercher(decor, null, null, "0042")).extracting(Accounts.Summary::id)
            .contains(compte);
        assertThat(chercher(decor, null, null, "7788")).extracting(Accounts.Summary::id)
            .contains(compte);
        assertThat(chercher(decor, null, null, "Client CLI-7788"))
            .extracting(Accounts.Summary::id).contains(compte);
        assertThat(chercher(decor, null, null, "introuvable")).isEmpty();
    }

    @Test
    @DisplayName("seuls les comptes clients sont rendus : la caisse et les comptes generaux non")
    void only_customer_accounts_are_returned() {
        Decor decor = decor("SEARCH-4");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID client = client(decor.entityId(), "CLI-0020");
        UUID compte = ouvrir(decor, "00140001", "CPTE-CHQ", client);

        List<Accounts.Summary> comptes = chercher(decor, null, null, null);

        // Le decor a pose une caisse, un compte d'attente, des comptes de produits : ils
        // appartiennent a la comptabilite, et se lisent par la balance, pas par un ecran de
        // guichet.
        assertThat(comptes).extracting(Accounts.Summary::id).containsExactly(compte);
    }

    @Test
    @DisplayName("le filtre d'agence ne rend que les comptes qu'elle tient")
    void the_branch_filter_returns_only_its_accounts() {
        Decor decor = decor("SEARCH-5");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID client = client(decor.entityId(), "CLI-0030");
        UUID auSiege = ouvrir(decor, "00150001", "CPTE-CHQ", client);

        assertThat(chercher(decor, null, siege(decor), null)).extracting(Accounts.Summary::id)
            .containsExactly(auSiege);
        assertThat(chercher(decor, null, UUID.randomUUID(), null)).isEmpty();
    }

    @Test
    @DisplayName("la pagination rend un ordre total : aucune ligne deux fois, aucune sautee")
    void pagination_is_totally_ordered() {
        Decor decor = decor("SEARCH-6");
        produit(decor, "CPTE-CHQ", "CURRENT_ACCOUNT", java.util.Map.of());
        UUID client = client(decor.entityId(), "CLI-0040");
        for (int i = 1; i <= 5; i++) {
            ouvrir(decor, "0016000" + i, "CPTE-CHQ", client);
        }

        List<String> premiere = database.inTransaction(
            c -> Accounts.search(c, decor.entityId(), null, null, null, 0, 2))
            .stream().map(Accounts.Summary::code).toList();
        List<String> seconde = database.inTransaction(
            c -> Accounts.search(c, decor.entityId(), null, null, null, 2, 2))
            .stream().map(Accounts.Summary::code).toList();

        assertThat(premiere).containsExactly("00160001", "00160002");
        assertThat(seconde).containsExactly("00160003", "00160004");
        assertThat(compter(decor, null, null, null)).isEqualTo(5);
    }
}
