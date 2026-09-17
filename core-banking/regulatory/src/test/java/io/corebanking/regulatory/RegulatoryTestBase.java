package io.corebanking.regulatory;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Decor commun : une entite, ses comptes, ses clients, et de quoi produire un etat. */
abstract class RegulatoryTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static PartyService parties;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    /** Un 30 septembre : une fin de mois et une fin de trimestre, ce qui sert aux deux cas. */
    protected static final LocalDate FIN = LocalDate.of(2026, 9, 30);

    protected static Account caisse;

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, FIN.minusMonths(6), FIN.plusMonths(3));
        postingService = new JdbcPostingService(database);
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, FIN);
            Entities.openPeriod(c, ENTITY, FIN.minusMonths(6), FIN.plusMonths(3));
            return null;
        });
        parties = new PartyService(database, Screening.NONE);
        caisse = account("CAISSE-REG", AccountKind.GL, NormalBalance.DEBIT);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account account(String code, AccountKind kind, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, FIN.minusMonths(6));
            return null;
        });
        return account;
    }

    protected static UUID client(String reference) {
        UUID id = parties.create(new PartyService.Draft(
            ENTITY, reference, PartyKind.NATURAL_PERSON, "Client " + reference, null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-" + reference)), ACTOR));
        parties.verifyKyc(id, RiskRating.MEDIUM, FIN.minusMonths(3), ACTOR, APPROVER);
        return id;
    }

    protected static Account compte(String code, UUID titulaire) {
        Account account = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER,
                                  FIN.minusMonths(6), ACTOR);
            return null;
        });
        return account;
    }

    /** Un credit debloque : le compte de pret porte l'encours, c'est lui que l'etat lit. */
    protected static UUID credit(String reference, UUID customer, String montant,
                                 LocalDate debloqueLe) {
        return credit(reference, customer, montant, montant, debloqueLe);
    }

    /**
     * Un credit dont une partie seulement est versee : le reste est un engagement de
     * financement, porte au hors bilan tant qu'il n'est pas tire.
     */
    protected static UUID credit(String reference, UUID customer, String accorde, String verse,
                                 LocalDate debloqueLe) {
        Account pret = account("PRET-" + reference, AccountKind.CUSTOMER, NormalBalance.DEBIT);
        Account reglement = compte("CC-" + reference, customer);
        UUID id = UUID.randomUUID();
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO loan_contract(id, legal_entity_id, reference, product_code, currency,"
                + " loan_account_id, settlement_account_id, principal, disbursed_on, status,"
                + " customer_id, created_by, approved_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, ENTITY);
                ps.setString(3, reference);
                ps.setString(4, "PRET-STD");
                ps.setString(5, "XOF");
                ps.setObject(6, pret.id());
                ps.setObject(7, reglement.id());
                ps.setBigDecimal(8, new java.math.BigDecimal(accorde));
                ps.setObject(9, debloqueLe);
                ps.setObject(10, customer);
                ps.setObject(11, ACTOR);
                ps.setObject(12, APPROVER);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Contrat de test", e);
            }
            return null;
        });
        // Le deblocage : la caisse est creditee, le compte de pret debite de ce qui est verse.
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("disb-" + reference), ENTITY, debloqueLe, "LOAN_DISBURSEMENT", ACTOR,
            List.of(PostingLine.debit(pret.id(), xof(verse), debloqueLe, null),
                    PostingLine.credit(caisse.id(), xof(verse), debloqueLe, null))));
        return id;
    }

    /** Une ligne de mobilisation ouverte : la banque s'engage a mettre des fonds a disposition. */
    protected static void mobilisation(UUID contractId, LocalDate delaiDeTirage) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO loan_mobilisation(contract_id, drawdown_deadline, instalment_count,"
                + " grace_instalments, first_due_date, interim_billed_through, opened_by,"
                + " approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, contractId);
                ps.setObject(2, delaiDeTirage);
                ps.setInt(3, 12);
                ps.setInt(4, 0);
                ps.setObject(5, delaiDeTirage.plusMonths(1));
                ps.setObject(6, delaiDeTirage.minusMonths(6));
                ps.setObject(7, ACTOR);
                ps.setObject(8, APPROVER);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Mobilisation de test", e);
            }
            return null;
        });
    }

    /**
     * Une tranche du plan de deblocage. Non debloquee, c'est l'engagement porte au hors bilan ;
     * la base exige que les tranches totalisent le capital accorde, et elle a raison — l'ecart
     * serait debloque hors plan ou perdu.
     */
    protected static UUID tranche(Connection c, UUID contractId, int numero, String montant,
                                  LocalDate prevue, LocalDate debloqueeLe) {
        UUID id = UUID.randomUUID();
        {
            try (var ps = c.prepareStatement(
                "INSERT INTO loan_tranche(id, contract_id, number, planned_on, planned_amount,"
                + " status, released_on, released_amount, released_by, approved_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, contractId);
                ps.setInt(3, numero);
                ps.setObject(4, prevue);
                ps.setBigDecimal(5, new java.math.BigDecimal(montant));
                ps.setString(6, debloqueeLe == null ? "PLANNED" : "RELEASED");
                ps.setObject(7, debloqueeLe);
                if (debloqueeLe == null) {
                    ps.setNull(8, java.sql.Types.NUMERIC);
                    ps.setNull(9, java.sql.Types.OTHER);
                    ps.setNull(10, java.sql.Types.OTHER);
                } else {
                    ps.setBigDecimal(8, new java.math.BigDecimal(montant));
                    ps.setObject(9, ACTOR);
                    ps.setObject(10, APPROVER);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Tranche de test", e);
            }
        }
        return id;
    }

    /** Le deblocage de la tranche : elle cesse d'etre un engagement ce jour-la. */
    protected static void debloquer(UUID trancheId, LocalDate on) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_tranche SET status = 'RELEASED', released_on = ?,"
                + " released_amount = planned_amount, released_by = ?, approved_by = ?"
                + " WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, ACTOR);
                ps.setObject(3, APPROVER);
                ps.setObject(4, trancheId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Deblocage de test", e);
            }
            return null;
        });
    }

    /** Une classification du credit a une date : c'est la derniere connue qui se declare. */
    protected static void classer(UUID contractId, LocalDate on, int rang, int joursDeRetard) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO loan_classification(id, contract_id, classified_on, days_past_due,"
                + " bucket_code, bucket_ordinal, performing, reason, exposure, collateral,"
                + " provision_base, provision_rate_percent, provision_amount)"
                + " VALUES (?,?,?,?,?,?,?,'AGEING',0,0,0,0,0)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, contractId);
                ps.setObject(3, on);
                ps.setInt(4, joursDeRetard);
                ps.setString(5, "CLASSE-" + rang);
                ps.setInt(6, rang);
                ps.setBoolean(7, rang <= 1);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Classification de test", e);
            }
            return null;
        });
    }

    /** Un incident de paiement sur un cheque : c'est le fait qui se declare, pas le montant. */
    protected static void incident(Account compte, long numero, String montant, LocalDate on) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO cheque_incident(id, legal_entity_id, account_id, number, amount,"
                + " currency, occurred_on, reason, created_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, ENTITY);
                ps.setObject(3, compte.id());
                ps.setLong(4, numero);
                ps.setBigDecimal(5, new java.math.BigDecimal(montant));
                ps.setString(6, "XOF");
                ps.setObject(7, on);
                ps.setString(8, "SANS_PROVISION");
                ps.setObject(9, ACTOR);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Incident de test", e);
            }
            return null;
        });
    }

    protected static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }
}
