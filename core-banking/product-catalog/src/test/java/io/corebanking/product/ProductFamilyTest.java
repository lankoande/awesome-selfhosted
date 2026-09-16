package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le contrat de paramétrage d'une famille de produit.
 *
 * <p>Ce qui est verifie ici l'etait auparavant par personne : le paramétrage etait un sac de
 * couples cle/valeur, et son incompletude ne se decouvrait qu'au premier traitement de nuit qui en
 * avait besoin.
 */
class ProductFamilyTest {

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, String> epargneComplete() {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "3");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, "CREDITOR");
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, id());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, id());
        return parametres;
    }

    private static void valide(String famille, Map<String, String> parametres,
                               Set<String> baremes) {
        ProductFamilies.require(famille).validate("P-TEST", parametres, baremes);
    }

    // ------------------------------------------------------------------ le catalogue lui-meme

    @Test
    @DisplayName("le catalogue se charge et declare les familles que le code sait traiter")
    void catalogueCharge() {
        assertThat(ProductFamilies.codes())
            .containsExactlyInAnyOrder("CURRENT_ACCOUNT", "SAVINGS_ACCOUNT", "TERM_DEPOSIT",
                                       "TERM_LOAN");
        assertThat(ProductFamilies.require("TERM_LOAN").label()).isEqualTo("Credit amortissable");
    }

    @Test
    @DisplayName("un type de produit hors catalogue est nomme, avec la liste de ce qui existe")
    void familleInconnue() {
        // Un type libre laisserait un produit sans contrat de paramétrage : ses manques ne se
        // decouvriraient qu'au premier traitement qui les demande.
        assertThatThrownBy(() -> ProductFamilies.require("PLAN_EPARGNE_LOGEMENT"))
            .isInstanceOf(ProductFamilies.UnknownFamilyException.class)
            .hasMessageContaining("PLAN_EPARGNE_LOGEMENT")
            .hasMessageContaining("SAVINGS_ACCOUNT");
    }

    // ------------------------------------------------------------------ exigences simples

    @Test
    @DisplayName("un paramétrage complet passe")
    void parametrageComplet() {
        valide("SAVINGS_ACCOUNT", epargneComplete(), Set.of());
    }

    @Test
    @DisplayName("tous les manques sont nommes d'un coup, pas le premier seulement")
    void tousLesManquesRestitues() {
        Map<String, String> presqueVide = Map.of(ProductCatalog.P_DAY_COUNT, "ACT_365");

        // S'arreter au premier obligerait a redeployer autant de fois qu'il manque de lignes, et
        // celui qui parametre finirait par desactiver le controle.
        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", presqueVide, Set.of()))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .extracting(e -> ((ProductFamily.IncompleteProductException) e).problems())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .hasSize(5);
    }

    @Test
    @DisplayName("un bareme par tranches tient lieu de taux, et reciproquement")
    void tauxOuBareme() {
        Map<String, String> sansTaux = epargneComplete();
        sansTaux.remove(ProductCatalog.P_RATE);

        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", sansTaux, Set.of()))
            .hasMessageContaining("aucun de [interest.rate, tier:INTEREST]");

        // Le meme produit avec un bareme d'interets passe : l'exigence porte sur le fait qu'un
        // taux existe, pas sur la forme qu'il prend.
        valide("SAVINGS_ACCOUNT", sansTaux, Set.of("INTEREST"));
    }

    @Test
    @DisplayName("un parametre que la famille ne declare pas est refuse")
    void parametreEtranger() {
        Map<String, String> avecIntrus = epargneComplete();
        avecIntrus.put("loan.penalty_rate", "10");

        // Rien n'empechait jusqu'ici un produit d'epargne de porter une penalite de credit. Le
        // parametre n'etait jamais lu : il donnait a son auteur la certitude d'avoir parametre une
        // penalite qui ne s'appliquerait jamais.
        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", avecIntrus, Set.of()))
            .hasMessageContaining("parametre inconnu de la famille SAVINGS_ACCOUNT")
            .hasMessageContaining("loan.penalty_rate");
    }

    // ------------------------------------------------------------------ exigences conditionnelles

    @Test
    @DisplayName("un compte de penalites sans mode de penalite est refuse")
    void penaliteSansMode() {
        Map<String, String> credit = creditComplet();
        credit.put("loan.penalty_income", id());

        assertThatThrownBy(() -> valide("TERM_LOAN", credit, Set.of()))
            .hasMessageContaining("loan.penalty_mode est obligatoire")
            .hasMessageContaining("penalite jamais percue");
    }

    @Test
    @DisplayName("le mode de penalite commande le parametre qui le chiffre")
    void modeDePenaliteEtSonChiffre() {
        Map<String, String> forfait = creditComplet();
        forfait.put("loan.penalty_mode", "FLAT_PER_INSTALMENT");
        forfait.put("loan.late_interest_income", id());
        assertThatThrownBy(() -> valide("TERM_LOAN", forfait, Set.of()))
            .hasMessageContaining("loan.penalty_amount est obligatoire");

        Map<String, String> proportionnel = creditComplet();
        proportionnel.put("loan.penalty_mode", "PERCENT_OF_OVERDUE");
        proportionnel.put("loan.late_interest_income", id());
        assertThatThrownBy(() -> valide("TERM_LOAN", proportionnel, Set.of()))
            .hasMessageContaining("loan.penalty_rate est obligatoire");
    }

    @Test
    @DisplayName("une grille de risque sans compte de dotation est refusee")
    void grilleSansComptes() {
        Map<String, String> credit = creditComplet();
        credit.put("loan.risk_profile", "GRILLE-UEMOA");

        assertThatThrownBy(() -> valide("TERM_LOAN", credit, Set.of()))
            .hasMessageContaining("loan.provision_expense")
            .hasMessageContaining("loan.provision_allowance")
            .hasMessageContaining("loan.reserved_interest");
    }

    // ------------------------------------------------------------------ blocs repetes

    @Test
    @DisplayName("une commission declaree sans compte de produit est refusee")
    void commissionSansCompte() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE,CARTE");
        parametres.put("fee.TENUE.amount", "2000");
        parametres.put("fee.TENUE.income_account", id());
        parametres.put("fee.CARTE.amount", "10000");
        // Le compte de produit de la seconde manque : l'ecriture n'aurait nulle part ou aller.

        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", parametres, Set.of()))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .extracting(e -> ((ProductFamily.IncompleteProductException) e).problems())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .singleElement()
            .asString().contains("fee.CARTE.income_account");
    }

    @Test
    @DisplayName("l'assiette par defaut d'une commission est controlee comme une assiette declaree")
    void assietteParDefaut() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.income_account", id());
        // Ni assiette ni montant : le code retient le forfait par defaut, et le forfait vaut zero.
        // Une condition attachee a la seule valeur declaree ne verrait rien.
        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", parametres, Set.of()))
            .hasMessageContaining("fee.TENUE.amount est obligatoire")
            .hasMessageContaining("FLAT par defaut");
    }

    @Test
    @DisplayName("une commission au bareme sans bareme est refusee")
    void commissionSansBareme() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.income_account", id());
        parametres.put("fee.TENUE.basis", "TIERED_ON_CLOSING_BALANCE");

        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", parametres, Set.of()))
            .hasMessageContaining("aucun bareme par tranches FEE:TENUE");

        valide("SAVINGS_ACCOUNT", parametres, Set.of("FEE:TENUE"));
    }

    @Test
    @DisplayName("les parametres d'une commission absente de la liste sont refuses")
    void commissionNonDeclaree() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.amount", "2000");
        parametres.put("fee.TENUE.income_account", id());
        parametres.put("fee.CARTE.amount", "10000");

        // Une commission parametree mais jamais declaree ne sera jamais percue, et son auteur
        // croira le contraire jusqu'a la revue des produits.
        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", parametres, Set.of()))
            .hasMessageContaining("parametre inconnu")
            .hasMessageContaining("fee.CARTE.amount");
    }

    @Test
    @DisplayName("une taxe calculee sans compte de collecte est refusee")
    void taxeSansCompte() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.amount", "2000");
        parametres.put("fee.TENUE.income_account", id());
        parametres.put("fee.TENUE.tax_rate", "18");

        assertThatThrownBy(() -> valide("SAVINGS_ACCOUNT", parametres, Set.of()))
            .hasMessageContaining("fee.TENUE.tax_account")
            .hasMessageContaining("declaration serait fausse");
    }

    // ------------------------------------------------------------------ comptes

    @Test
    @DisplayName("les parametres declares comme comptes sont soumis au controleur, et a lui seul")
    void comptesSoumisAuControleur() {
        Map<String, String> parametres = epargneComplete();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.amount", "2000");
        parametres.put("fee.TENUE.income_account", "compte-frais");
        java.util.List<String> examines = new java.util.ArrayList<>();
        ProductFamily.AccountChecker traceur = (name, value) -> {
            examines.add(name + "=" + value);
            return name.endsWith("credit_account") ? java.util.Optional.of(name + " : compte inconnu")
                                                   : java.util.Optional.empty();
        };

        assertThatThrownBy(() -> ProductFamilies.require("SAVINGS_ACCOUNT")
            .validate("P-TEST", parametres, Set.of(), traceur))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .hasMessageContaining("interest.credit_account : compte inconnu");

        // Les comptes d'interets et le compte de produit de la commission, dans le bloc repete ;
        // ni le taux, ni le montant, qui ne sont pas des comptes.
        assertThat(examines).containsExactlyInAnyOrder(
            "interest.debit_account=" + parametres.get("interest.debit_account"),
            "interest.credit_account=" + parametres.get("interest.credit_account"),
            "fee.TENUE.income_account=compte-frais");
    }

    // ------------------------------------------------------------------ accord code / catalogue

    @Test
    @DisplayName("tout parametre d'interets lu par le code est declare par les familles de compte")
    void constantesDInteretsEtFamillesSAccordent() {
        Set<String> lus = constantes(ProductCatalog.class);
        assertThat(lus).isNotEmpty();
        // Le bloc d'interets est commun aux deux familles ; les agios n'existent que sur le compte
        // courant — un livret d'epargne ne se met pas a decouvert.
        Set<String> interets = new TreeSet<>();
        Set<String> agios = new TreeSet<>();
        for (String nom : lus) {
            (nom.startsWith("overdraft.") ? agios : interets).add(nom);
        }
        assertThat(agios).isNotEmpty();
        for (String famille : Set.of("CURRENT_ACCOUNT", "SAVINGS_ACCOUNT")) {
            assertThat(ProductFamilies.declaredParameters(famille))
                .as("famille " + famille)
                .containsAll(interets);
        }
        assertThat(ProductFamilies.declaredParameters("CURRENT_ACCOUNT")).containsAll(agios);
        assertThat(ProductFamilies.declaredParameters("SAVINGS_ACCOUNT"))
            .doesNotContainAnyElementsOf(agios);
    }

    /** Constantes {@code P_*} publiques d'une classe de lecture de paramétrage. */
    static Set<String> constantes(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                && field.getName().startsWith("P_")) {
                try {
                    field.setAccessible(true);
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(field.getName(), e);
                }
            }
        }
        return names;
    }

    private static Map<String, String> creditComplet() {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put("loan.accrued_receivable", id());
        parametres.put("loan.accrued_interest", id());
        parametres.put("loan.interest_income", id());
        parametres.put("loan.tax_account", id());
        return parametres;
    }
}
