package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.domain.error.InsufficientFundsException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Un blocage de montant reduit le disponible sans toucher au solde, et expire par date comptable. */
class HoldsIT extends DepositsTestBase {

    @Test
    @DisplayName("le disponible exclut les montants bloques ; la levee les rend")
    void disponible() {
        Decor decor = decor("HLD");
        produit(decor, "EP-HLD", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-HLD", "EP-HLD", client(decor.entityId(), "T-HLD"));
        verser(decor, compte, "1000000", "hld-1");

        UUID caution = database.inTransaction(c -> Holds.place(c, new Holds.Placement(
            compte, xof("300000"), "CAUTION", "BAIL-12", J, null, ACTOR)));

        assertThat(solde(compte)).isEqualTo(xof("1000000"));
        assertThat(disponible(compte, J)).isEqualTo(xof("700000"));
        assertThatThrownBy(() -> retirer(decor, compte, "800000", "hld-2"))
            .isInstanceOf(InsufficientFundsException.class);
        retirer(decor, compte, "600000", "hld-3");
        assertThat(disponible(compte, J)).isEqualTo(xof("100000"));

        database.inTransaction(c -> {
            Holds.release(c, caution, J, ACTOR);
            assertThat(Holds.activeOn(c, compte)).isEmpty();
            return null;
        });
        assertThat(disponible(compte, J)).isEqualTo(xof("400000"));
    }

    @Test
    @DisplayName("un blocage expire a sa date comptable ; l'annulation de l'arrete le repose")
    void expiration() {
        Decor decor = decor("EXP");
        produit(decor, "EP-EXP", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-EXP", "EP-EXP", client(decor.entityId(), "T-EXP"));
        verser(decor, compte, "100000", "exp-1");
        LocalDate echeance = LocalDate.of(2026, 9, 20);
        database.inTransaction(c -> Holds.place(c, new Holds.Placement(
            compte, xof("40000"), "CHEQUE_CERTIFIE", "CHQ-7", J, echeance, ACTOR)));

        // Avant l'echeance, le montant est indisponible ; des le lendemain, il ne l'est plus —
        // meme si l'arrete qui le leve n'a pas encore tourne.
        assertThat(disponible(compte, J)).isEqualTo(xof("60000"));
        assertThat(disponible(compte, echeance)).isEqualTo(xof("60000"));
        assertThat(disponible(compte, echeance.plusDays(1))).isEqualTo(xof("100000"));

        UUID run = UUID.randomUUID();
        database.inTransaction(c -> {
            assertThat(Holds.expire(c, decor.entityId(), echeance.minusDays(1), run, ACTOR))
                .isZero();
            assertThat(Holds.expire(c, decor.entityId(), echeance, run, ACTOR)).isEqualTo(1);
            assertThat(Holds.activeOn(c, compte)).isEmpty();
            assertThat(Holds.cancelRun(c, run)).isEqualTo(1);
            assertThat(Holds.activeOn(c, compte)).extracting(Holds.Hold::expiresOn)
                .containsExactly(echeance);
            return null;
        });
    }

    @Test
    @DisplayName("un blocage porte un montant positif, une nature, et n'expire pas avant d'etre pose")
    void placementInvalide() {
        UUID compte = UUID.randomUUID();
        assertThatThrownBy(() -> new Holds.Placement(compte, xof("0"), "CAUTION", null, J, null,
                                                     ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Holds.Placement(compte, xof("10"), " ", null, J, null, ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Holds.Placement(compte, xof("10"), "CAUTION", null, J,
                                                     J.minusDays(1), ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
