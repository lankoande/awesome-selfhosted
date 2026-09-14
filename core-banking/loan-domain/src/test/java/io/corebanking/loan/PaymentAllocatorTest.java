package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAllocatorTest {

    private static final LocalDate OCTOBRE = LocalDate.of(2026, 10, 15);
    private static final LocalDate NOVEMBRE = LocalDate.of(2026, 11, 15);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static Receivable creance(int echeance, DueCategory nature, LocalDate echeanceDate,
                                      String montant) {
        return new Receivable(UUID.randomUUID(), echeance, nature, echeanceDate, xof(montant));
    }

    // ------------------------------------------------------------------ ordre d'imputation

    @Test
    @DisplayName("un ordre qui omet une categorie est refuse : la creance deviendrait impayable")
    void ordreIncomplet() {
        assertThatThrownBy(() -> new AllocationOrder(List.of(
            DueCategory.PENALTIES, DueCategory.INTEREST, DueCategory.PRINCIPAL)))
            .isInstanceOf(AllocationOrder.IncompleteAllocationOrderException.class)
            .hasMessageContaining("RECOVERY_FEES");
    }

    @Test
    @DisplayName("une categorie citee deux fois est refusee")
    void ordreAvecDoublon() {
        assertThatThrownBy(() -> AllocationOrder.parse(
            "RECOVERY_FEES,PENALTIES,PENALTIES,FEES_AND_INSURANCE,LATE_INTEREST,INTEREST,"
            + "PRINCIPAL,FUTURE_PRINCIPAL"))
            .isInstanceOf(AllocationOrder.IncompleteAllocationOrderException.class)
            .hasMessageContaining("deux fois");
    }

    @Test
    @DisplayName("une categorie inconnue dans le parametrage est nommee, pas ignoree")
    void categorieInconnue() {
        assertThatThrownBy(() -> AllocationOrder.parse("PENALITES,INTEREST"))
            .isInstanceOf(AllocationOrder.IncompleteAllocationOrderException.class)
            .hasMessageContaining("PENALITES");
    }

    @Test
    @DisplayName("l'ordre standard se relit depuis un parametre produit")
    void ordreRelu() {
        AllocationOrder relu = AllocationOrder.parse(
            "RECOVERY_FEES, PENALTIES, FEES_AND_INSURANCE, LATE_INTEREST, INTEREST, PRINCIPAL,"
            + " FUTURE_PRINCIPAL");
        assertThat(relu).isEqualTo(AllocationOrder.standard());
    }

    // ------------------------------------------------------------------ imputation

    @Test
    @DisplayName("le reglement suit l'ordre parametre et s'impute partiellement sur la derniere creance atteinte")
    void imputationPartielle() {
        List<Receivable> creances = List.of(
            creance(1, DueCategory.PENALTIES, OCTOBRE, "3000"),
            creance(1, DueCategory.INTEREST, OCTOBRE, "5000"),
            creance(1, DueCategory.PRINCIPAL, OCTOBRE, "20000"));

        var resultat = PaymentAllocator.allocate(xof("10000"), creances,
                                                 AllocationOrder.standard());

        assertThat(resultat.allocations()).extracting(a -> a.receivable().category())
            .containsExactly(DueCategory.PENALTIES, DueCategory.INTEREST, DueCategory.PRINCIPAL);
        assertThat(resultat.allocations()).extracting(Allocation::amount)
            .containsExactly(xof("3000"), xof("5000"), xof("2000"));
        assertThat(resultat.unallocated().isZero()).isTrue();
        assertThat(resultat.settled()).hasSize(2);
        assertThat(resultat.allocations().get(2).remaining()).isEqualTo(xof("18000"));
    }

    @Test
    @DisplayName("l'ordre d'imputation decide de ce qui reste du, et le test le chiffre")
    void lOrdreChangeLeResteDu() {
        List<Receivable> creances = List.of(
            creance(1, DueCategory.PENALTIES, OCTOBRE, "12000"),
            creance(1, DueCategory.PRINCIPAL, OCTOBRE, "50000"));

        var penalitesDAbord = PaymentAllocator.allocate(
            xof("20000"), creances, AllocationOrder.standard());
        var capitalDAbord = PaymentAllocator.allocate(xof("20000"), creances, AllocationOrder.parse(
            "PRINCIPAL,RECOVERY_FEES,PENALTIES,FEES_AND_INSURANCE,LATE_INTEREST,INTEREST,"
            + "FUTURE_PRINCIPAL"));

        // Penalites d'abord : 12 000 de penalites soldees, 8 000 sur le capital, qui reste du a
        // 42 000 et continue de porter interet.
        assertThat(penalitesDAbord.allocations().get(1).remaining()).isEqualTo(xof("42000"));
        // Capital d'abord : 20 000 sur le capital, qui tombe a 30 000. Douze mille francs de
        // capital en moins portant interet — c'est exactement l'enjeu du parametre, et la raison
        // pour laquelle plusieurs juridictions imposent leur ordre.
        assertThat(capitalDAbord.allocations().get(0).remaining()).isEqualTo(xof("30000"));
    }

    @Test
    @DisplayName("dans une meme categorie, la creance la plus ancienne est soldee la premiere")
    void laPlusAncienneDAbord() {
        List<Receivable> creances = List.of(
            creance(2, DueCategory.PRINCIPAL, NOVEMBRE, "20000"),
            creance(1, DueCategory.PRINCIPAL, OCTOBRE, "20000"));

        var resultat = PaymentAllocator.allocate(xof("25000"), creances,
                                                 AllocationOrder.standard());

        // C'est la creance d'octobre qui compte les jours de retard : c'est elle qui declassera le
        // credit et declenchera le provisionnement. Solder novembre d'abord laisserait le compteur
        // courir alors meme que le client paie.
        assertThat(resultat.allocations().get(0).receivable().dueDate()).isEqualTo(OCTOBRE);
        assertThat(resultat.allocations().get(0).settles()).isTrue();
        assertThat(resultat.allocations().get(1).amount()).isEqualTo(xof("5000"));
    }

    @Test
    @DisplayName("l'excedent est restitue a l'appelant, jamais consomme en silence")
    void excedentRestitue() {
        List<Receivable> creances = List.of(
            creance(1, DueCategory.INTEREST, OCTOBRE, "5000"),
            creance(1, DueCategory.PRINCIPAL, OCTOBRE, "20000"));

        var resultat = PaymentAllocator.allocate(xof("30000"), creances,
                                                 AllocationOrder.standard());

        assertThat(resultat.allocated()).isEqualTo(xof("25000"));
        // Remboursement anticipe, avoir sur le compte ou rejet : la decision depend du canal et du
        // produit, elle n'appartient pas a l'arithmetique de l'imputation.
        assertThat(resultat.unallocated()).isEqualTo(xof("5000"));
    }

    @Test
    @DisplayName("le capital non echu vient apres tout l'exigible")
    void capitalNonEchuEnDernier() {
        List<Receivable> creances = List.of(
            creance(0, DueCategory.FUTURE_PRINCIPAL, NOVEMBRE, "500000"),
            creance(1, DueCategory.PENALTIES, OCTOBRE, "4000"));

        var resultat = PaymentAllocator.allocate(xof("10000"), creances,
                                                 AllocationOrder.standard());

        // Imputer l'excedent sur du capital non echu avant d'avoir solde l'impaye laisserait courir
        // des penalites sur un compte qui a paye.
        assertThat(resultat.allocations().get(0).receivable().category())
            .isEqualTo(DueCategory.PENALTIES);
        assertThat(resultat.allocations().get(1).amount()).isEqualTo(xof("6000"));
    }

    @Test
    @DisplayName("aucune creance : tout le reglement est restitue")
    void aucuneCreance() {
        var resultat = PaymentAllocator.allocate(xof("10000"), List.of(),
                                                 AllocationOrder.standard());

        assertThat(resultat.allocations()).isEmpty();
        assertThat(resultat.unallocated()).isEqualTo(xof("10000"));
    }

    @Test
    @DisplayName("un reglement nul ou negatif est refuse")
    void reglementNonPositif() {
        assertThatThrownBy(() -> PaymentAllocator.allocate(xof("0"), List.of(),
                                                           AllocationOrder.standard()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("n'est pas un reglement");
    }

    @Test
    @DisplayName("regler une creance dans une autre devise est refuse, jamais converti au passage")
    void deviseDifferente() {
        Receivable enEuros = new Receivable(UUID.randomUUID(), 1, DueCategory.PRINCIPAL, OCTOBRE,
                                            Money.of("100", Currencies.EUR));

        assertThatThrownBy(() -> PaymentAllocator.allocate(xof("10000"), List.of(enEuros),
                                                           AllocationOrder.standard()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("operation de change");
    }

    @Test
    @DisplayName("une creance soldee n'est pas une creance")
    void creanceNulle() {
        assertThatThrownBy(() -> creance(1, DueCategory.PRINCIPAL, OCTOBRE, "0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("n'en est pas une");
    }
}
