package io.corebanking.deposits;

import io.corebanking.interest.service.CatalogTermsProvider;
import io.corebanking.interest.service.InterestAccrualService;
import io.corebanking.interest.service.InterestSettlementService;
import io.corebanking.interest.service.InterestTerms;
import io.corebanking.interest.service.TermsProvider;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.Party;
import io.corebanking.party.PartyService;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductFamilies;
import io.corebanking.product.ProductVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cycle de vie d'un compte de depot : ouverture, blocage, cloture. La dormance est prononcee par
 * l'arrete ({@link Dormancy}), les blocages de montant sont dans {@link Holds}.
 *
 * <h2>Ouverture</h2>
 *
 * <p>Un compte s'ouvre a un tiers dont la connaissance client est verifiee, sur un produit de la
 * famille des depots et dans sa devise, a la date comptable de l'entite, a deux : celui qui ouvre
 * n'est pas celui qui approuve. Le compte nait rattache a son produit et a son titulaire, avec le
 * controle du disponible : un compte de depot ne passe debiteur que dans la limite de son
 * autorisation.
 *
 * <h2>Blocage</h2>
 *
 * <p>Un blocage — opposition, saisie, gel — ne change pas le statut du compte : c'est un etat
 * superpose, tenu par {@code account_block} et applique par le ledger lui-meme. Il le faut, parce
 * qu'un blocage en debit laisse entrer les fonds, et qu'un blocage total laisse passer les
 * operations de la banque : interets capitalises, contre-passation d'une erreur. Un statut qui
 * refuserait tout ferait perdre au client des fonds qui lui arrivent, et a la banque la
 * possibilite de corriger. Pose et levee se font a deux.
 *
 * <h2>Cloture</h2>
 *
 * <p>Une cloture n'est pas un changement de statut : c'est un solde de tout compte. Dans l'ordre,
 * et dans une seule transaction : rien ne s'oppose a la cloture (aucun blocage, aucun blocage de
 * montant, aucun credit adosse au compte) ; les interets des deux cotes sont calcules jusqu'a la
 * veille et regles ce jour ; le solde restant est verse au compte de reversement designe — caisse,
 * ou compte d'attente des comptes clos — et un solde debiteur refuse la cloture ; enfin le compte
 * passe {@code CLOSED}, son produit et ses titulaires sont fermes a la date de cloture. Le jour de
 * la cloture n'est pas remunere : le versement du solde porte la meme date de valeur qu'un
 * retrait.
 *
 * <p>Un compte clos ne se rouvre pas et n'accepte plus aucune ecriture, contre-passation comprise :
 * la correction d'une ecriture anterieure a la cloture passe par un compte d'attente, et par le
 * comptable.
 */
public final class AccountLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AccountLifecycle.class);

    public static final String TYPE_CLOSURE_PAYOUT = "ACCOUNT_CLOSURE_PAYOUT";

    /** Familles de produit qu'un compte de depot peut porter. */
    static final Set<String> DEPOSIT_FAMILIES = Set.of("CURRENT_ACCOUNT", "SAVINGS_ACCOUNT");

    private final Database database;
    private final PostingService postingService;
    private final InterestAccrualService accrualService;
    private final InterestSettlementService settlementService;

    public AccountLifecycle(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
        this.accrualService = new InterestAccrualService(database, postingService);
        this.settlementService = new InterestSettlementService(database, postingService);
    }

    // ------------------------------------------------------------------ ouverture

    /**
     * Demande d'ouverture. La date d'ouverture est la date comptable de l'entite : elle ne se
     * fournit pas.
     */
    public record Opening(UUID legalEntityId, String code, UUID holderPartyId, String productCode,
                          CurrencyRef currency, UUID branchId, UUID actorId, UUID approverId) {
        public Opening {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(holderPartyId, "holderPartyId");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(branchId,
                "branchId : un compte s'ouvre dans une agence, qui en repond ; celle de "
                + "l'appelant, jamais celle que le corps de la requete propose");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Numero de compte obligatoire");
            }
            if (productCode == null || productCode.isBlank()) {
                throw new IllegalArgumentException("Un compte de depot s'ouvre sur un produit");
            }
            requireTwoPersons(actorId, approverId, "L'ouverture d'un compte");
        }
    }

    /** Ouvre le compte et rend son identifiant. */
    public UUID open(Opening opening) {
        return database.inTransaction(c -> {
            LocalDate on = OperationsService.businessDate(c, opening.legalEntityId());
            Party holder = PartyService.requireOnboardable(c, opening.holderPartyId());
            if (!holder.legalEntityId().equals(opening.legalEntityId())) {
                throw new IllegalArgumentException(
                    "Le tiers " + holder.reference() + " releve d'une autre entite juridique");
            }
            ProductVersion product = ProductCatalog.resolveAt(c, opening.legalEntityId(),
                                                              opening.productCode(), on);
            if (!DEPOSIT_FAMILIES.contains(product.productType())) {
                throw new IllegalArgumentException(
                    "Le produit " + product.code() + " est de la famille " + product.productType()
                    + " (" + ProductFamilies.require(product.productType()).label() + ") : un "
                    + "compte de depot ne se rattache qu'a un compte courant ou d'epargne");
            }
            ProductCatalog.requireProductCurrency(c, opening.legalEntityId(), opening.productCode(),
                                                  opening.currency().code(),
                                                  "ouverture du compte " + opening.code());
            Branches.Branch branch = Branches.require(c, opening.branchId());
            if (!branch.legalEntityId().equals(opening.legalEntityId())) {
                throw new IllegalArgumentException(
                    "L'agence " + branch.code() + " releve d'une autre entite juridique");
            }
            if (!"ACTIVE".equals(branch.status())) {
                throw new IllegalArgumentException(
                    "L'agence " + branch.code() + " est fermee : rien ne s'y ouvre");
            }

            UUID id = Ids.newId();
            Accounts.create(c, new Account(id, opening.legalEntityId(), opening.code(),
                                           AccountKind.CUSTOMER, NormalBalance.CREDIT,
                                           opening.currency(), true, true, 1, AccountStatus.ACTIVE,
                                           branch.id()),
                            on);
            ProductCatalog.assignProduct(c, id, opening.productCode(), on, null);
            AccountHolders.attach(c, id, opening.holderPartyId(), HolderRole.HOLDER, on,
                                  opening.actorId());
            event(c, id, "OPENED", on, opening.actorId(), opening.approverId(),
                  "produit " + opening.productCode() + ", titulaire " + holder.reference()
                  + ", agence " + branch.code(), null);
            return id;
        });
    }

    // ------------------------------------------------------------------ blocage

    /** Demande de blocage d'un compte. */
    public record Block(UUID accountId, BlockKind kind, String reason, String reference,
                        UUID actorId, UUID approverId) {
        public Block {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(kind, "kind");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Un blocage se motive");
            }
            requireTwoPersons(actorId, approverId, "Un blocage de compte");
        }
    }

    /** Blocage en cours sur un compte. */
    public record ActiveBlock(UUID id, UUID accountId, BlockKind kind, String reason,
                              String reference, LocalDate placedOn) {}

    /** Pose le blocage et rend son identifiant. */
    public UUID block(Block block) {
        return database.inTransaction(c -> {
            Account account = requireCustomerAccount(c, block.accountId());
            if (!account.status().acceptsPosting()) {
                throw new OperationsService.AccountNotOperableException(account,
                    "compte " + account.status() + " : rien n'y est plus a bloquer");
            }
            LocalDate on = OperationsService.businessDate(c, account.legalEntityId());
            UUID id = Ids.newId();
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account_block(id, account_id, kind, reason, reference, placed_on,"
                + " placed_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, account.id());
                ps.setString(3, block.kind().name());
                ps.setString(4, block.reason());
                ps.setString(5, block.reference());
                ps.setObject(6, on);
                ps.setObject(7, block.actorId());
                ps.setObject(8, block.approverId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Pose du blocage sur le compte " + account.code(), e);
            }
            event(c, account.id(), "BLOCKED", on, block.actorId(), block.approverId(),
                  block.kind() + " — " + block.reason(), null);
            return id;
        });
    }

    /** Leve un blocage. */
    public void unblock(UUID blockId, String reason, UUID actorId, UUID approverId) {
        Objects.requireNonNull(blockId, "blockId");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Une levee de blocage se motive");
        }
        requireTwoPersons(actorId, approverId, "La levee d'un blocage");
        database.inTransaction(c -> {
            UUID accountId;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT account_id, lifted_on FROM account_block WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, blockId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Blocage inconnu : " + blockId);
                    }
                    accountId = rs.getObject(1, UUID.class);
                    if (rs.getObject(2, LocalDate.class) != null) {
                        throw new IllegalStateException(
                            "Le blocage " + blockId + " a deja ete leve le "
                            + rs.getObject(2, LocalDate.class));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture du blocage " + blockId, e);
            }
            Account account = requireCustomerAccount(c, accountId);
            LocalDate on = OperationsService.businessDate(c, account.legalEntityId());
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account_block SET lifted_on = ?, lifted_by = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, actorId);
                ps.setObject(3, blockId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Levee du blocage " + blockId, e);
            }
            event(c, accountId, "UNBLOCKED", on, actorId, approverId, reason, null);
            return null;
        });
    }

    /** Blocages en cours d'un compte, du plus ancien au plus recent. */
    public static List<ActiveBlock> activeBlocks(Connection c, UUID accountId) {
        List<ActiveBlock> blocks = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, kind, reason, reference, placed_on FROM account_block"
            + " WHERE account_id = ? AND lifted_on IS NULL ORDER BY placed_on, created_at")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    blocks.add(new ActiveBlock(rs.getObject(1, UUID.class), accountId,
                                               BlockKind.valueOf(rs.getString(2)),
                                               rs.getString(3), rs.getString(4),
                                               rs.getObject(5, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des blocages du compte " + accountId, e);
        }
        return blocks;
    }

    // ------------------------------------------------------------------ cloture

    /**
     * Demande de cloture.
     *
     * @param payoutAccountId compte interne de l'entite qui recoit le solde — caisse pour un
     *                        versement au guichet, compte d'attente des comptes clos sinon ;
     *                        peut etre absent si le compte est a zero
     */
    public record Closing(UUID accountId, UUID payoutAccountId, UUID actorId, UUID approverId) {
        public Closing {
            Objects.requireNonNull(accountId, "accountId");
            requireTwoPersons(actorId, approverId, "La cloture d'un compte");
        }
    }

    /**
     * Ce que la cloture a produit.
     *
     * @param interestPaid    interets crediteurs bruts regles au client a la cloture
     * @param interestCharged agios bruts preleves a la cloture
     * @param paidOut         solde verse au compte de reversement, zero si le compte etait a zero
     * @param payoutEntryId   ecriture du versement, absente si rien n'a ete verse
     */
    public record Closure(UUID accountId, LocalDate closedOn, Money interestPaid,
                          Money interestCharged, Money paidOut, UUID payoutEntryId) {}

    /** Clot le compte, ou refuse en disant pourquoi. */
    public Closure close(Closing closing) {
        return database.inTransaction(c -> {
            Account account = requireCustomerAccount(c, closing.accountId());
            if (!account.status().acceptsPosting()) {
                throw new OperationsService.AccountNotOperableException(account,
                    "compte " + account.status() + " : il n'y a plus rien a clore");
            }
            UUID entity = account.legalEntityId();
            LocalDate on = OperationsService.businessDate(c, entity);
            refuseIfEncumbered(c, account);

            // Les interets d'abord : le solde a verser les comprend.
            Money[] interest = settleInterest(c, account, on, closing.actorId());

            Money balance = Balances.current(c, account.id());
            if (balance.isNegative()) {
                throw new ClosureRefusedException(account,
                    "solde debiteur de " + balance.negate() + " : le client regularise avant que "
                    + "le compte ne soit clos");
            }
            UUID payoutEntry = null;
            if (balance.isPositive()) {
                if (closing.payoutAccountId() == null) {
                    throw new ClosureRefusedException(account,
                        "solde de " + balance + " a verser et aucun compte de reversement designe");
                }
                Account payout = OperationsService.requireInternal(c, entity,
                                                                   closing.payoutAccountId(),
                                                                   account.currency());
                String label = "Cloture du compte " + account.code();
                PostingResult result = postingService.post(PostingCommand.online(
                    IdempotencyKey.of("ACCOUNT_CLOSURE|" + account.id()), entity, on,
                    TYPE_CLOSURE_PAYOUT, closing.actorId(),
                    List.of(PostingLine.debit(account.id(), balance, on, label),
                            PostingLine.credit(payout.id(), balance, on, label)))
                    .withBranch(payout.branchId()));
                payoutEntry = result.entryId();
            }

            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account SET status = 'CLOSED', closed_at = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, account.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Cloture du compte " + account.code(), e);
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account_product SET valid_to = ? WHERE account_id = ? AND valid_to IS NULL")) {
                ps.setObject(1, on);
                ps.setObject(2, account.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Fermeture du rattachement produit", e);
            }
            AccountHolders.endAll(c, account.id(), on);
            event(c, account.id(), "CLOSED", on, closing.actorId(), closing.approverId(),
                  "solde verse " + balance, null);
            return new Closure(account.id(), on, interest[0], interest[1], balance, payoutEntry);
        });
    }

    /** Rien ne doit s'opposer a la cloture ; tout ce qui s'y oppose est nomme d'un coup. */
    private static void refuseIfEncumbered(Connection c, Account account) {
        List<String> obstacles = new ArrayList<>();
        for (ActiveBlock block : activeBlocks(c, account.id())) {
            obstacles.add("blocage " + block.kind() + " du " + block.placedOn() + " ("
                          + block.reason() + ")");
        }
        List<Holds.Hold> holds = Holds.activeOn(c, account.id());
        if (!holds.isEmpty()) {
            Money held = holds.stream().map(Holds.Hold::amount)
                .reduce(Money.zero(account.currency()), Money::plus);
            obstacles.add(holds.size() + " blocage(s) de montant en cours pour " + held);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT reference, status FROM loan_contract"
            + " WHERE (loan_account_id = ? OR settlement_account_id = ?)"
            + "   AND status IN ('DRAFT','ACTIVE') ORDER BY reference")) {
            ps.setObject(1, account.id());
            ps.setObject(2, account.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    obstacles.add("credit " + rs.getString(1) + " " + rs.getString(2)
                                  + " adosse au compte");
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche des credits adosses au compte "
                                           + account.code(), e);
        }
        if (!obstacles.isEmpty()) {
            throw new ClosureRefusedException(account, String.join(" ; ", obstacles));
        }
    }

    /**
     * Calcule les interets des deux cotes jusqu'a la veille et les regle a la date de cloture.
     * Rend {@code [crediteurs bruts, agios bruts]}.
     */
    private Money[] settleInterest(Connection c, Account account, LocalDate on, UUID actorId) {
        UUID entity = account.legalEntityId();
        LocalDate through = on.minusDays(1);
        // Le lot de la cloture : il ne se confond avec aucun arrete, et n'est defait par aucun.
        UUID run = Ids.newId();
        CatalogTermsProvider provider = CatalogTermsProvider.forAccounts(
            database, entity, List.of(account.id()), on);
        Money zero = Money.zero(account.currency());
        Money paid = zero;
        Money charged = zero;

        InterestTerms primary = provider.termsFor(account.id(), on);
        accrualService.accrueThrough(entity, account.id(), through,
                                     date -> provider.termsFor(account.id(), date), on, actorId,
                                     run);
        Optional<InterestSettlementService.Settlement> settled = settlementService.settleThrough(
            entity, account.id(), through, primary, on, actorId, run);
        Money gross = settled.map(InterestSettlementService.Settlement::gross).orElse(zero);
        if (primary.side() == io.corebanking.interest.accrual.AccrualSide.CREDITOR) {
            paid = paid.plus(gross);
        } else {
            charged = charged.plus(gross);
        }

        Optional<InterestTerms> overdraft = provider.overdraftTermsFor(account.id(), on);
        if (overdraft.isPresent()) {
            TermsProvider agios = provider.overdraft();
            accrualService.accrueThrough(entity, account.id(), through,
                                         date -> agios.termsFor(account.id(), date), on, actorId,
                                         run);
            charged = charged.plus(settlementService.settleThrough(
                entity, account.id(), through, overdraft.get(), on, actorId, run)
                .map(InterestSettlementService.Settlement::gross).orElse(zero));
        }
        return new Money[] {paid, charged};
    }

    // ------------------------------------------------------------------ historique

    /**
     * Trace une transition de statut : en base, ou l'historique fait foi, et au journal, ou
     * l'exploitation la lit.
     */
    public static void event(Connection c, UUID accountId, String kind, LocalDate on, UUID actorId,
                             UUID approverId, String detail, UUID batchRunId) {
        LOG.info("compte {} : {} le {} par {}{}{}", accountId, kind, on, actorId,
                 approverId == null ? "" : ", approuve par " + approverId,
                 detail == null ? "" : " — " + detail);
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_event(account_id, kind, occurred_on, actor_id, approver_id, detail,"
            + " batch_run_id) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, accountId);
            ps.setString(2, kind);
            ps.setObject(3, on);
            ps.setObject(4, actorId);
            ps.setObject(5, approverId);
            ps.setString(6, detail);
            ps.setObject(7, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Historique du compte " + accountId, e);
        }
    }

    /** Transition de statut, telle que l'historique la conserve. */
    public record Event(String kind, LocalDate occurredOn, UUID actorId, UUID approverId,
                        String detail, UUID batchRunId) {}

    /** Historique d'un compte, du plus ancien au plus recent. */
    public static List<Event> history(Connection c, UUID accountId) {
        List<Event> events = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind, occurred_on, actor_id, approver_id, detail, batch_run_id"
            + " FROM account_event WHERE account_id = ? ORDER BY id")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new Event(rs.getString(1), rs.getObject(2, LocalDate.class),
                                         rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                                         rs.getString(5), rs.getObject(6, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Historique du compte " + accountId, e);
        }
        return events;
    }

    // ------------------------------------------------------------------ interne

    private static Account requireCustomerAccount(Connection c, UUID accountId) {
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Compte inconnu : " + accountId);
        }
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " n'est pas un compte client");
        }
        return account;
    }

    private static void requireTwoPersons(UUID actorId, UUID approverId, String what) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(approverId, "approverId");
        if (actorId.equals(approverId)) {
            throw new IllegalArgumentException(
                what + " se fait a deux : l'approbateur n'est pas celui qui agit");
        }
    }

    /** La cloture est refusee, et la raison est complete. */
    public static class ClosureRefusedException extends RuntimeException {
        public ClosureRefusedException(Account account, String detail) {
            super("Cloture du compte " + account.code() + " refusee : " + detail);
        }
    }
}
