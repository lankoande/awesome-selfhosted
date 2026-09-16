package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le treizieme invariant : une ecriture est equilibree agence par agence, et c'est le moteur qui
 * la complete par ses lignes de liaison, via le siege.
 */
class InterbranchIT extends LedgerTestBase {

    private static UUID siege;
    private static UUID agenceA;
    private static UUID agenceB;
    private static Account liaisonA;
    private static Account liaisonB;

    @BeforeAll
    static void reseau() {
        liaisonA = newGlAccount("LIAISON-A", Currencies.XOF, NormalBalance.DEBIT, 1);
        liaisonB = newGlAccount("LIAISON-B", Currencies.XOF, NormalBalance.DEBIT, 1);
        database.inTransaction(c -> {
            siege = Branches.headOffice(c, ENTITY);
            agenceA = Branches.create(c, ENTITY, "A", "Agence A", Branches.Kind.BRANCH, null,
                                      BUSINESS_DATE, Map.of(Currencies.XOF, liaisonA.id()));
            agenceB = Branches.create(c, ENTITY, "B", "Agence B", Branches.Kind.BRANCH, null,
                                      BUSINESS_DATE, Map.of(Currencies.XOF, liaisonB.id()));
            return null;
        });
    }

    /** Ligne telle qu'elle est en base : compte, sens, montant, agence comptable, nature. */
    private record Ligne(UUID accountId, Direction direction, BigDecimal amount, UUID branchId,
                         String kind) {}

    @Test
    @DisplayName("un retrait deplace est complete par quatre lignes de liaison, et chaque agence est equilibree")
    void retraitDeplace() {
        Account client = compte("CLI-DEP-A", AccountKind.CUSTOMER, NormalBalance.CREDIT, agenceA);
        Account caisseA = compte("CAISSE-DEP-A", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceA);
        Account caisseB = compte("CAISSE-DEP-B", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceB);
        Account frais = newGlAccount("FRAIS-DEP", Currencies.XOF, NormalBalance.CREDIT, 1);
        Account taxe = newGlAccount("TAXE-DEP", Currencies.XOF, NormalBalance.CREDIT, 1);
        poster("dep-0", null, List.of(debit(caisseA, "100000"), credit(client, "100000")));

        // Le client de A retire a la caisse de B ; le frais revient a l'agence qui sert.
        PostingResult result = poster("dep-1", agenceB, List.of(
            debit(client, "20590"), credit(caisseB, "20000"), credit(frais, "500"),
            credit(taxe, "90")));

        List<Ligne> lignes = lignes(result.entryId());
        assertThat(lignes).hasSize(8);
        assertThat(lignes.stream().filter(l -> l.kind().equals("LIAISON"))).hasSize(4);
        assertThat(lignes).filteredOn(l -> l.accountId().equals(frais.id()))
            .singleElement().extracting(Ligne::branchId).isEqualTo(agenceB);
        assertThat(lignes).filteredOn(l -> l.accountId().equals(client.id()))
            .singleElement().extracting(Ligne::branchId).isEqualTo(agenceA);
        assertThat(lignes).filteredOn(l -> l.accountId().equals(liaisonA.id()))
            .extracting(Ligne::branchId, Ligne::direction, Ligne::amount)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(agenceA, Direction.CREDIT, montant("20590")),
                org.assertj.core.groups.Tuple.tuple(siege, Direction.DEBIT, montant("20590")));
        assertThat(lignes).filteredOn(l -> l.accountId().equals(liaisonB.id()))
            .extracting(Ligne::branchId, Ligne::direction, Ligne::amount)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(agenceB, Direction.DEBIT, montant("20590")),
                org.assertj.core.groups.Tuple.tuple(siege, Direction.CREDIT, montant("20590")));
        assertThat(ecartsParAgence(result.entryId())).isEmpty();
        assertThat(agenceDeLOperation(result.entryId())).isEqualTo(agenceB);

        // Le siege ne garde rien : la liaison de A et celle de B s'y compensent, et chaque compte
        // de liaison est a zero pour l'entite.
        database.inTransaction(c -> {
            assertThat(Balances.current(c, liaisonA.id()).isZero()).isTrue();
            assertThat(Balances.current(c, liaisonB.id()).isZero()).isTrue();
            assertThat(Balances.current(c, client.id())).isEqualTo(xof("79410"));
            assertThat(Balances.current(c, caisseB.id())).isEqualTo(xof("-20000"));
            return null;
        });
    }

    @Test
    @DisplayName("la contre-passation reprend les lignes de liaison telles quelles, agence par agence")
    void contrePassationExacte() {
        Account client = compte("CLI-CP-A", AccountKind.CUSTOMER, NormalBalance.CREDIT, agenceA);
        Account caisseB = compte("CAISSE-CP-B", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceB);
        PostingResult depot = poster("cp-1", agenceB, List.of(
            debit(caisseB, "50000"), credit(client, "50000")));
        assertThat(lignes(depot.entryId())).hasSize(6);

        PostingResult extourne = postingService.reverse(depot.entryId(), BUSINESS_DATE,
            BUSINESS_DATE, IdempotencyKey.of("cp-1-extourne"), "erreur de caisse");

        List<Ligne> lignes = lignes(extourne.entryId());
        assertThat(lignes).hasSize(6);
        assertThat(lignes).filteredOn(l -> l.kind().equals("LIAISON")).hasSize(4);
        assertThat(ecartsParAgence(extourne.entryId())).isEmpty();
        assertThat(agenceDeLOperation(extourne.entryId())).isEqualTo(agenceB);
        // Chaque ligne de liaison de l'origine a son inverse exact, meme agence, meme compte.
        for (Ligne origine : lignes(depot.entryId())) {
            assertThat(lignes).anyMatch(l -> l.accountId().equals(origine.accountId())
                && l.branchId().equals(origine.branchId())
                && l.direction() == origine.direction().opposite()
                && l.amount().compareTo(origine.amount()) == 0);
        }
        database.inTransaction(c -> {
            assertThat(Balances.current(c, liaisonA.id()).isZero()).isTrue();
            assertThat(Balances.current(c, liaisonB.id()).isZero()).isTrue();
            assertThat(Balances.current(c, client.id()).isZero()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("une ecriture d'une seule agence ne porte aucune ligne de liaison, et ses comptes generaux prennent cette agence")
    void ecritureDUneSeuleAgence() {
        Account client = compte("CLI-MONO-A", AccountKind.CUSTOMER, NormalBalance.CREDIT, agenceA);
        Account caisseA = compte("CAISSE-MONO-A", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceA);
        Account frais = newGlAccount("FRAIS-MONO", Currencies.XOF, NormalBalance.CREDIT, 1);
        poster("mono-0", null, List.of(debit(caisseA, "10000"), credit(client, "10000")));

        PostingResult result = poster("mono-1", null, List.of(
            debit(client, "1500"), credit(caisseA, "1000"), credit(frais, "500")));

        List<Ligne> lignes = lignes(result.entryId());
        assertThat(lignes).hasSize(3);
        assertThat(lignes).extracting(Ligne::branchId).containsOnly(agenceA);
        assertThat(agenceDeLOperation(result.entryId())).isEqualTo(agenceA);
    }

    @Test
    @DisplayName("sans compte de liaison dans la devise, une operation deplacee est refusee — jamais equilibree a defaut")
    void deviseSansLiaison() {
        Account client = compte("CLI-XAF-A", AccountKind.CUSTOMER, NormalBalance.CREDIT, agenceA,
                                Currencies.XAF);
        Account caisseB = compte("CAISSE-XAF-B", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceB,
                                 Currencies.XAF);

        assertThatThrownBy(() -> poster("xaf-1", agenceB, List.of(
                PostingLine.debit(caisseB.id(), Money.of("1000", Currencies.XAF), BUSINESS_DATE, null)
                    .withFxRate(BigDecimal.ONE),
                PostingLine.credit(client.id(), Money.of("1000", Currencies.XAF), BUSINESS_DATE, null)
                    .withFxRate(BigDecimal.ONE))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("liaison")
            .hasMessageContaining("XAF");
    }

    @Test
    @DisplayName("un compte client ou interne sans agence prend le siege ; un compte general n'en a pas")
    void siegeParDefaut() {
        Account client = newCustomerAccount("CLI-SIEGE", Currencies.XOF);
        database.inTransaction(c -> {
            assertThat(Accounts.loadAll(c, List.of(client.id())).get(client.id()).branchId())
                .isEqualTo(siege);
            return null;
        });
        Account general = new Account(UUID.randomUUID(), ENTITY, "GL-AGENCE", AccountKind.GL,
                                      NormalBalance.DEBIT, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE, agenceA);
        assertThatThrownBy(() -> database.inTransaction(c -> { Accounts.create(c, general); return null; }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("n'a pas d'agence");
    }

    @Test
    @DisplayName("l'agence d'un compte client ne se choisit pas a l'ecriture")
    void agenceContraireRefusee() {
        Account client = compte("CLI-CONTRAIRE", AccountKind.CUSTOMER, NormalBalance.CREDIT, agenceA);
        Account caisseA = compte("CAISSE-CONTRAIRE", AccountKind.INTERNAL, NormalBalance.DEBIT, agenceA);
        assertThatThrownBy(() -> poster("contraire-1", null, List.of(
                debit(caisseA, "100"), credit(client, "100").withBranch(agenceB))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("ne se choisit pas");
    }

    @Test
    @DisplayName("la base elle-meme refuse une ecriture desequilibree pour une agence, quelle que soit la voie d'entree")
    void desequilibreParAgenceRefuseParLaBase() {
        Account frais = newGlAccount("FRAIS-BRUT", Currencies.XOF, NormalBalance.CREDIT, 1);
        Account taxe = newGlAccount("TAXE-BRUT", Currencies.XOF, NormalBalance.CREDIT, 1);
        UUID entry = UUID.randomUUID();

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try {
                try (var ps = c.prepareStatement(
                    "INSERT INTO journal_entry(id, booking_date, legal_entity_id, entry_number,"
                    + " transaction_type, source, idempotency_key, created_by)"
                    + " VALUES (?,?,?, nextval('journal_entry_number_seq'), 'MANUAL', 'ONLINE', ?, ?)")) {
                    ps.setObject(1, entry);
                    ps.setObject(2, BUSINESS_DATE);
                    ps.setObject(3, ENTITY);
                    ps.setString(4, "brut-" + entry);
                    ps.setObject(5, ACTOR);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement(
                    "INSERT INTO journal_line(id, booking_date, entry_id, legal_entity_id, line_number,"
                    + " account_id, direction, amount, currency, functional_amount, value_date,"
                    + " branch_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ligneBrute(ps, entry, 1, frais.id(), "DEBIT", agenceA);
                    ligneBrute(ps, entry, 2, taxe.id(), "CREDIT", agenceB);
                    ps.executeBatch();
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Insertion brute", e);
            }
            return null;
        }))
            .isInstanceOf(LedgerStoreException.class)
            .cause().isInstanceOf(SQLException.class)
            .hasMessageContaining("agence");
    }

    // ------------------------------------------------------------------ outillage

    @Test
    @DisplayName("un engagement de hors bilan s'equilibre dans son agence : entre deux agences, aucune liaison ne lui est offerte, et le refus le dit")
    void offBalanceCommitmentsStayInTheirBranch() {
        Account engagement = horsBilan("ENG-HB", NormalBalance.DEBIT);
        Account contrepartie = horsBilan("CTR-HB", NormalBalance.CREDIT);

        // Dans une meme agence, l'engagement s'inscrit tel quel : deux lignes, aucune liaison.
        PostingResult dansA = poster("hb-1", agenceA, List.of(
            debit(engagement, "1000").withBranch(agenceA),
            credit(contrepartie, "1000").withBranch(agenceA)));
        assertThat(lignes(dansA.entryId())).hasSize(2)
            .allMatch(l -> l.branchId().equals(agenceA) && l.kind().equals("BUSINESS"));

        // Entre l'agence A et le siege, pas de liaison pour le hors bilan : refuse, en le disant.
        assertThatThrownBy(() -> poster("hb-2", agenceA, List.of(
                debit(engagement, "1000").withBranch(agenceA),
                credit(contrepartie, "1000").withBranch(siege))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("hors bilan")
            .hasMessageContaining("meme agence");
    }

    private static Account horsBilan(String code, NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, normal,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE)
            .withNature(AccountNature.OFF_BALANCE_SHEET);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Account compte(String code, AccountKind kind, NormalBalance normal, UUID branch) {
        return compte(code, kind, normal, branch, Currencies.XOF);
    }

    private static Account compte(String code, AccountKind kind, NormalBalance normal, UUID branch,
                                  io.corebanking.kernel.money.CurrencyRef currency) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normal, currency,
                                      true, false, 1, AccountStatus.ACTIVE, branch);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static PostingLine debit(Account account, String amount) {
        return PostingLine.debit(account.id(), xof(amount), BUSINESS_DATE, null);
    }

    private static PostingLine credit(Account account, String amount) {
        return PostingLine.credit(account.id(), xof(amount), BUSINESS_DATE, null);
    }

    private static PostingResult poster(String key, UUID branch, List<PostingLine> lines) {
        PostingCommand command = PostingCommand.online(IdempotencyKey.of(key), ENTITY,
                                                       BUSINESS_DATE, "TEST", ACTOR, lines);
        return postingService.post(branch == null ? command : command.withBranch(branch));
    }

    private static Money xof(String amount) {
        return Money.of(amount, Currencies.XOF);
    }

    private static BigDecimal montant(String amount) {
        return new BigDecimal(amount).setScale(5);
    }

    private static List<Ligne> lignes(UUID entryId) {
        return database.inTransaction(c -> {
            List<Ligne> lignes = new ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, direction, amount, branch_id, kind FROM journal_line"
                + " WHERE entry_id = ? ORDER BY line_number")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lignes.add(new Ligne(rs.getObject(1, UUID.class),
                                             Direction.valueOf(rs.getString(2)),
                                             rs.getBigDecimal(3), rs.getObject(4, UUID.class),
                                             rs.getString(5)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lignes", e);
            }
            return lignes;
        });
    }

    /** Agences dont les lignes de l'ecriture ne s'equilibrent pas. */
    private static List<UUID> ecartsParAgence(UUID entryId) {
        return database.inTransaction(c -> {
            List<UUID> ecarts = new ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT branch_id FROM journal_line WHERE entry_id = ? GROUP BY branch_id"
                + " HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ecarts.add(rs.getObject(1, UUID.class));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Ecarts", e);
            }
            return ecarts;
        });
    }

    private static UUID agenceDeLOperation(UUID entryId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT branch_id FROM journal_entry WHERE id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Ecriture", e);
            }
        });
    }

    private static void ligneBrute(java.sql.PreparedStatement ps, UUID entry, int number,
                                   UUID account, String direction, UUID branch) throws SQLException {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, BUSINESS_DATE);
        ps.setObject(3, entry);
        ps.setObject(4, ENTITY);
        ps.setInt(5, number);
        ps.setObject(6, account);
        ps.setString(7, direction);
        ps.setBigDecimal(8, new BigDecimal("100"));
        ps.setString(9, "XOF");
        ps.setBigDecimal(10, new BigDecimal("100"));
        ps.setObject(11, BUSINESS_DATE);
        ps.setObject(12, branch);
        ps.addBatch();
    }
}
