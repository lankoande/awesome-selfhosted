package io.corebanking.ledger.store;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.error.UnbalancedEntryException;
import io.corebanking.ledger.domain.journal.JournalEntry;
import io.corebanking.ledger.domain.journal.Reversals;
import io.corebanking.ledger.domain.posting.EntryValidator;
import io.corebanking.ledger.domain.posting.InterbranchBridging;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingContext;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.domain.posting.PostingSource;
import io.corebanking.ledger.domain.posting.ValidatedEntry;
import io.corebanking.ledger.domain.posting.ValidatedLine;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comptabilisation sur PostgreSQL.
 *
 * <p>Une commande est traitee dans une transaction unique, dans cet ordre :
 *
 * <ol>
 *   <li>reservation de la cle d'idempotence — premiere ecriture de la transaction, ce qui detecte
 *       un rejeu avant tout travail ;</li>
 *   <li>controle de la periode comptable ;</li>
 *   <li>chargement des comptes et validation des invariants d'ecriture ;</li>
 *   <li>verrouillage des soldes, <b>par identifiant de compte croissant</b> ;</li>
 *   <li>controle du disponible sur les comptes concernes ;</li>
 *   <li>insertion de l'ecriture et de ses lignes ;</li>
 *   <li>mise a jour des soldes.</li>
 * </ol>
 *
 * <p>L'ordre de verrouillage total de l'etape 4 est ce qui rend impossible l'interblocage sur des
 * virements croises : deux transactions qui touchent les memes comptes les prennent dans le meme
 * ordre. Sans cet ordre, deux virements A vers B et B vers A simultanes se bloquent mutuellement.
 */
public final class JdbcPostingService implements PostingService {

    private final Database database;

    public JdbcPostingService(Database database) {
        this.database = database;
    }

    @Override
    public PostingResult post(PostingCommand command) {
        return database.inTransaction(connection -> postWithin(connection, command, null));
    }

    @Override
    public PostingResult reverse(UUID entryId, LocalDate bookingDate, LocalDate reversalBookingDate,
                                 IdempotencyKey key, String reason) {
        return database.inTransaction(connection -> {
            JournalEntry original = loadEntry(connection, entryId, bookingDate);
            var originalLines = Journal.loadPostingLines(connection, entryId, bookingDate);

            PostingCommand reversal = Reversals.reverse(
                original, originalLines, reversalBookingDate, key, original.createdBy(), reason);

            claimReversal(connection, original, reason);
            return postWithin(connection, reversal, original.id());
        });
    }

    @Override
    public JournalEntry findEntry(UUID entryId, LocalDate bookingDate) {
        return database.inTransaction(connection -> loadEntry(connection, entryId, bookingDate));
    }

    // ------------------------------------------------------------------ traitement

    private PostingResult postWithin(Connection c, PostingCommand command, UUID reversalOf) {
        UUID entryId = Ids.newId();

        PostingResult replay = reserveIdempotencyKey(c, command, entryId);
        if (replay != null) {
            return replay;
        }

        if (!Entities.isPeriodOpen(c, command.legalEntityId(), command.bookingDate())) {
            throw new InvalidPostingException(
                "Aucune periode comptable ouverte pour l'entite " + command.legalEntityId()
                + " a la date du " + command.bookingDate());
        }

        Set<UUID> accountIds = new LinkedHashSet<>();
        command.lines().forEach(line -> accountIds.add(line.accountId()));

        CurrencyRef functional = Entities.functionalCurrency(c, command.legalEntityId());
        Map<UUID, Account> accounts = Accounts.loadAll(c, accountIds);
        Branches.Network network = Branches.network(c, command.legalEntityId());
        PostingContext context = new PostingContext(functional, accounts, network.headOfficeId(),
                                                    network.liaisonAccountIds());
        ValidatedEntry entry = EntryValidator.validate(command, context);
        // Treizieme invariant : equilibree agence par agence, lignes de liaison comprises. Le
        // hors bilan s'equilibre dans son agence, sans liaison : la liaison est un compte de bilan.
        if (entry.offBalance()) {
            try {
                EntryValidator.requireBalancedPerBranch(entry.lines());
            } catch (UnbalancedEntryException e) {
                throw new InvalidPostingException(
                    "Un engagement de hors bilan s'equilibre dans son agence : la liaison est un "
                    + "compte de bilan, elle ne lui est pas offerte. Inscrire l'engagement et sa "
                    + "contrepartie dans la meme agence. " + e.getMessage(), e);
            }
        } else {
            entry = InterbranchBridging.complete(entry, context,
                (branch, currency) -> liaisonAccount(c, network, branch, currency));
        }

        // Le cours applique se confronte au referentiel — apres que l'ecriture est structurellement
        // complete, liaisons comprises : ce qu'elle a de mal forme se dit d'abord. Hors
        // contre-passation, qui reprend le cours d'origine, et hors reprise de donnees.
        if (reversalOf == null && command.source() != PostingSource.MIGRATION
            && command.source() != PostingSource.CORRECTION) {
            FxRates.requireAppliedRates(c, command.legalEntityId(), command.bookingDate(),
                                        entry.lines(), functional);
        }

        List<AccountDelta> deltas = aggregateDeltas(entry);
        refuseBlocked(c, deltas, command.source());
        lockAndCheck(c, deltas, command.bookingDate());

        Instant knowledgeTime = insertEntry(c, command, entryId, reversalOf,
                                            entry.operationBranchId());
        long entryNumber = insertLines(c, command, entry, entryId, knowledgeTime);
        applyDeltas(c, deltas);

        return new PostingResult(entryId, entryNumber, command.bookingDate(), knowledgeTime,
                                 false, readBalances(c, deltas));
    }

    /**
     * Reserve la cle d'idempotence. Renvoie le resultat initial si la commande a deja ete traitee,
     * {@code null} sinon.
     *
     * <p>C'est la premiere instruction de la transaction, et ce n'est pas un detail : si deux
     * requetes concurrentes portent la meme cle, la seconde attend sur ce conflit d'unicite au
     * lieu de derouler un traitement complet qui serait ensuite rejete.
     */
    private PostingResult reserveIdempotencyKey(Connection c, PostingCommand command, UUID entryId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO posting_idempotency(legal_entity_id, idempotency_key, entry_id, booking_date)"
            + " VALUES (?,?,?,?) ON CONFLICT (legal_entity_id, idempotency_key) DO NOTHING")) {
            ps.setObject(1, command.legalEntityId());
            ps.setString(2, command.idempotencyKey().value());
            ps.setObject(3, entryId);
            ps.setObject(4, command.bookingDate());
            if (ps.executeUpdate() == 1) {
                return null;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reservation de la cle d'idempotence", e);
        }
        return replayOf(c, command);
    }

    private PostingResult replayOf(Connection c, PostingCommand command) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT entry_id, booking_date FROM posting_idempotency"
            + " WHERE legal_entity_id = ? AND idempotency_key = ?")) {
            ps.setObject(1, command.legalEntityId());
            ps.setString(2, command.idempotencyKey().value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException(
                        "Cle d'idempotence en conflit mais introuvable : " + command.idempotencyKey());
                }
                UUID entryId = rs.getObject(1, UUID.class);
                LocalDate bookingDate = rs.getObject(2, LocalDate.class);
                JournalEntry existing = loadEntry(c, entryId, bookingDate);
                return new PostingResult(existing.id(), existing.entryNumber(), existing.bookingDate(),
                                         existing.knowledgeTime(), true, Map.of());
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Relecture d'une commande deja traitee", e);
        }
    }

    private void claimReversal(Connection c, JournalEntry original, String reason) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO journal_reversal(reversed_entry_id, reversal_entry_id, reason, reversed_by)"
            + " VALUES (?,?,?,?) ON CONFLICT (reversed_entry_id) DO NOTHING")) {
            ps.setObject(1, original.id());
            ps.setObject(2, Ids.newId());
            ps.setString(3, reason);
            ps.setObject(4, original.createdBy());
            if (ps.executeUpdate() == 0) {
                throw new InvalidPostingException(
                    "L'ecriture " + original.id() + " a deja ete contre-passee. "
                    + "Une ecriture ne se contre-passe qu'une fois ; corriger en reemettant "
                    + "l'ecriture juste.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de la contre-passation", e);
        }
    }

    // ------------------------------------------------------------------ soldes

    private record AccountDelta(Account account, Money delta, Money functionalDelta, int stripeId) {}

    /**
     * Agrege les mouvements par compte. Une ecriture qui debite et credite le meme compte produit
     * une seule mise a jour de solde, et un seul verrou.
     */
    private List<AccountDelta> aggregateDeltas(ValidatedEntry entry) {
        Map<UUID, Money> byAccount = new LinkedHashMap<>();
        Map<UUID, Money> functionalByAccount = new LinkedHashMap<>();
        Map<UUID, Account> accounts = new LinkedHashMap<>();

        for (ValidatedLine line : entry.lines()) {
            UUID id = line.account().id();
            accounts.put(id, line.account());
            byAccount.merge(id, line.signedAmount(), Money::plus);
            functionalByAccount.merge(id, line.signedFunctionalAmount(), Money::plus);
        }

        List<AccountDelta> deltas = new ArrayList<>(accounts.size());
        accounts.forEach((id, account) -> deltas.add(new AccountDelta(
            account, byAccount.get(id), functionalByAccount.get(id), chooseStripe(account))));

        // L'ordre total sur l'identifiant de compte est ce qui evite l'interblocage.
        deltas.sort(Comparator.comparing(d -> d.account().id()));
        return deltas;
    }

    private int chooseStripe(Account account) {
        return account.stripeCount() == 1
            ? 0
            : ThreadLocalRandom.current().nextInt(account.stripeCount());
    }

    private void lockAndCheck(Connection c, List<AccountDelta> deltas, LocalDate bookingDate) {
        for (AccountDelta delta : deltas) {
            if (!delta.account().controlAvailable()) {
                continue;
            }
            lockBalance(c, delta.account().id());
            if (delta.delta().isNegative()) {
                Money available = availableBalance(c, delta.account(), bookingDate);
                if (available.plus(delta.delta()).isNegative()) {
                    throw new InsufficientFundsException(
                        delta.account().id(), available, delta.delta().abs());
                }
            }
        }
    }

    /**
     * Un compte sous blocage n'accepte aucun debit, quelle qu'en soit l'origine — un prelevement
     * de commission ou d'echeance n'echappe pas a une saisie. Sous blocage total, il n'accepte
     * pas non plus de credit venu d'une operation de guichet ou de canal : seule la banque
     * elle-meme — interets capitalises, contre-passation — continue d'y ecrire.
     */
    private void refuseBlocked(Connection c, List<AccountDelta> deltas, PostingSource source) {
        for (AccountDelta delta : deltas) {
            if (delta.account().kind() != io.corebanking.ledger.domain.account.AccountKind.CUSTOMER) {
                continue;
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT kind, reason FROM account_block"
                + " WHERE account_id = ? AND lifted_on IS NULL ORDER BY kind")) {
                ps.setObject(1, delta.account().id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String kind = rs.getString(1);
                        boolean refused = delta.delta().isNegative()
                            || ("TOTAL".equals(kind) && source == PostingSource.ONLINE);
                        if (refused) {
                            throw new AccountBlockedException(delta.account().id(), kind,
                                                              rs.getString(2));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des blocages du compte "
                                               + delta.account().code(), e);
            }
        }
    }

    private void lockBalance(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT stripe_id FROM account_balance WHERE account_id = ? ORDER BY stripe_id FOR UPDATE")) {
            ps.setObject(1, accountId);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrouillage du solde du compte " + accountId, e);
        }
    }

    private Money availableBalance(Connection c, Account account, LocalDate asOf) {
        try (PreparedStatement ps = c.prepareStatement("SELECT available_balance(?, ?)")) {
            ps.setObject(1, account.id());
            ps.setObject(2, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                BigDecimal value = rs.getBigDecimal(1);
                return Money.of(value == null ? BigDecimal.ZERO : value, account.currency());
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Calcul du disponible du compte " + account.code(), e);
        }
    }

    private void applyDeltas(Connection c, List<AccountDelta> deltas) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account_balance"
            + "   SET balance = balance + ?, functional_balance = functional_balance + ?,"
            + "       version = version + 1, updated_at = now()"
            + " WHERE account_id = ? AND stripe_id = ?")) {
            for (AccountDelta delta : deltas) {
                ps.setBigDecimal(1, delta.delta().amount());
                ps.setBigDecimal(2, delta.functionalDelta().amount());
                ps.setObject(3, delta.account().id());
                ps.setInt(4, delta.stripeId());
                ps.addBatch();
            }
            int[] updated = ps.executeBatch();
            for (int count : updated) {
                if (count != 1) {
                    throw new LedgerStoreException(
                        "Ligne de solde absente : les soldes doivent etre amorces a la creation "
                        + "du compte, pour toutes ses stripes.");
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour des soldes", e);
        }
    }

    private Map<UUID, Money> readBalances(Connection c, List<AccountDelta> deltas) {
        Map<UUID, Money> balances = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(balance), 0) FROM account_balance WHERE account_id = ?")) {
            for (AccountDelta delta : deltas) {
                ps.setObject(1, delta.account().id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    balances.put(delta.account().id(),
                                 Money.of(rs.getBigDecimal(1), delta.account().currency()));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des soldes apres imputation", e);
        }
        return balances;
    }

    // ------------------------------------------------------------------ journal

    /**
     * Compte de liaison d'une agence dans une devise. Une agence creee sur une autre instance
     * n'est pas encore en memoire ici : une relecture avant de refuser.
     */
    private static Account liaisonAccount(Connection c, Branches.Network network, UUID branch,
                                          CurrencyRef currency) {
        Optional<UUID> id = network.liaisonAccount(branch, currency.code());
        if (id.isEmpty()) {
            id = Branches.reload(c, network.legalEntityId()).liaisonAccount(branch, currency.code());
        }
        UUID accountId = id.orElseThrow(() -> new InvalidPostingException(
            "Aucun compte de liaison en " + currency.code() + " pour l'agence "
            + network.require(branch).code() + " : l'ecriture met en jeu deux agences et ne peut "
            + "pas etre equilibree agence par agence. Declarer le compte de liaison, ne pas "
            + "contourner."));
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null) {
            throw new LedgerStoreException("Compte de liaison introuvable : " + accountId);
        }
        return account;
    }

    private Instant insertEntry(Connection c, PostingCommand command, UUID entryId, UUID reversalOf,
                                UUID operationBranchId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO journal_entry(id, booking_date, legal_entity_id, entry_number,"
            + " transaction_type, source, batch_run_id, reversal_of, idempotency_key, narrative,"
            + " metadata, created_by, branch_id)"
            + " VALUES (?,?,?, nextval('journal_entry_number_seq'), ?,?,?,?,?,?,?::jsonb,?,?)"
            + " RETURNING knowledge_time")) {
            ps.setObject(1, entryId);
            ps.setObject(2, command.bookingDate());
            ps.setObject(3, command.legalEntityId());
            ps.setString(4, command.transactionType());
            ps.setString(5, command.source().name());
            ps.setObject(6, command.batchRunId());
            ps.setObject(7, reversalOf);
            ps.setString(8, command.idempotencyKey().value());
            ps.setString(9, command.metadata().get("narrative"));
            ps.setString(10, Json.of(command.metadata()));
            ps.setObject(11, command.actorId());
            ps.setObject(12, operationBranchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1).toInstant();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion de l'ecriture", e);
        }
    }

    /**
     * Insere les lignes en portant <b>l'instant de connaissance de l'ecriture</b>, et non un
     * horodatage propre a chaque ligne.
     *
     * <p>Ce detail decide de la justesse de tout l'axe de connaissance. {@code clock_timestamp()}
     * avance a l'interieur d'une meme transaction : laisser chaque ligne prendre le sien placerait
     * les lignes apres l'ecriture qui les porte, et une requete « tel que connu au moment de
     * l'ecriture E » exclurait les lignes de E elle-meme. Une ecriture entre dans le systeme d'un
     * seul tenant ; elle porte donc un seul instant.
     */
    private long insertLines(Connection c, PostingCommand command, ValidatedEntry entry, UUID entryId,
                             Instant knowledgeTime) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO journal_line(id, booking_date, entry_id, legal_entity_id, line_number,"
            + " account_id, direction, amount, currency, functional_amount, fx_rate, value_date,"
            + " stripe_id, label, knowledge_time, branch_id, kind)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int lineNumber = 1;
            for (ValidatedLine line : entry.lines()) {
                ps.setObject(1, Ids.newId());
                ps.setObject(2, command.bookingDate());
                ps.setObject(3, entryId);
                ps.setObject(4, command.legalEntityId());
                ps.setInt(5, lineNumber++);
                ps.setObject(6, line.account().id());
                ps.setString(7, line.line().direction().name());
                ps.setBigDecimal(8, line.line().amount().amount());
                ps.setString(9, line.line().amount().currency().code());
                ps.setBigDecimal(10, line.functionalAmount().amount());
                ps.setBigDecimal(11, line.line().fxRate());
                ps.setObject(12, line.line().valueDate());
                ps.setInt(13, 0);
                ps.setString(14, line.line().label());
                ps.setTimestamp(15, Timestamp.from(knowledgeTime));
                ps.setObject(16, line.branchId());
                ps.setString(17, line.kind().name());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion des lignes d'ecriture", e);
        }
        return readEntryNumber(c, entryId, command.bookingDate());
    }

    private long readEntryNumber(Connection c, UUID entryId, LocalDate bookingDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT entry_number FROM journal_entry WHERE id = ? AND booking_date = ?")) {
            ps.setObject(1, entryId);
            ps.setObject(2, bookingDate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du numero d'ecriture", e);
        }
    }

    private JournalEntry loadEntry(Connection c, UUID entryId, LocalDate bookingDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, entry_number, booking_date, transaction_type, source,"
            + " batch_run_id, reversal_of, idempotency_key, narrative, created_by, knowledge_time,"
            + " branch_id"
            + " FROM journal_entry WHERE id = ? AND booking_date = ?")) {
            ps.setObject(1, entryId);
            ps.setObject(2, bookingDate);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new InvalidPostingException(
                        "Ecriture introuvable : " + entryId + " au " + bookingDate);
                }
                Timestamp knowledge = rs.getTimestamp(12);
                return new JournalEntry(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getObject(4, LocalDate.class),
                    rs.getString(5),
                    PostingSource.valueOf(rs.getString(6)),
                    rs.getObject(7, UUID.class),
                    rs.getObject(8, UUID.class),
                    rs.getString(9),
                    rs.getString(10),
                    Map.of(),
                    rs.getObject(11, UUID.class),
                    knowledge.toInstant(),
                    rs.getObject(13, UUID.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'ecriture " + entryId, e);
        }
    }
}
