package io.corebanking.loan.service;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.expr.EvaluationContext;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Passage en perte et recouvrement.
 *
 * <p><b>Le passage en perte n'eteint pas la creance.</b> C'est la premiere chose que dit un
 * controleur et la premiere qu'oublie un progiciel : la sortie de l'actif est une decision
 * comptable, pas une remise de dette. Ce qui sort du bilan entre au hors bilan, et tout ce qui est
 * encaisse ensuite est un <b>produit de recuperation</b> — pas un remboursement, puisqu'il n'y a
 * plus de creance a l'actif a diminuer. L'imputer sur l'encours ferait reapparaitre un credit
 * solde et rendrait le capital negatif.
 *
 * <p><b>L'ordre d'absorption n'est pas une commodite.</b> Ce qui sort est absorbe d'abord par les
 * <b>interets reserves</b> — ces produits ont deja ete sortis du resultat a la suspension, et les
 * passer en perte une seconde fois constaterait une charge pour un produit jamais pris —, puis par
 * la <b>provision</b> constituee, qui est faite pour cela. Le reliquat seul est une perte. Un
 * dossier sur-provisionne rend l'excedent au resultat : la provision n'a plus d'objet.
 */
public final class LoanWriteOffService {

    private final Database database;
    private final PostingService postingService;

    public LoanWriteOffService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    public record WriteOff(UUID id, UUID contractId, String contractReference, UUID legalEntityId,
                           LocalDate writtenOffOn, Money principalWritten, Money receivablesWritten,
                           Money provisionUsed, Money reservedUsed, Money provisionReleased,
                           Money lossRecognised, Money recovered, String reason, String bucketCode,
                           Integer daysPastDue, UUID createdBy, UUID approvedBy) {

        /** Ce qui reste du au hors bilan : sorti de l'actif, pas encore recouvre. */
        public Money outstanding() {
            return principalWritten.plus(receivablesWritten).minus(recovered);
        }
    }

    public record Recovery(UUID id, UUID writeOffId, LocalDate recoveredOn, Money amount,
                           UUID channelAccountId, UUID recordedBy) {}

    /** Un credit qui ne peut pas etre passe en perte, ou un recouvrement qui depasse. */
    public static class WriteOffRefusedException extends RuntimeException {
        public WriteOffRefusedException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ passage en perte

    /**
     * Passe un credit en perte, a deux.
     *
     * <p>Le contrat sort de l'etat actif : plus aucune echeance ne devient exigible, plus aucun
     * interet ne court. Ses creances ouvertes sont annulees en meme temps que le compte est solde
     * — sans quoi le sous-livre porterait une dette que le grand livre ne porte plus, et la
     * reconciliation de la nuit le dirait.
     */
    public WriteOff writeOff(UUID contractId, LocalDate on, String reason, UUID actorId,
                             UUID approverId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Un passage en perte porte son motif : c'est la "
                + "piece que lira le controle, des annees plus tard");
        }
        if (actorId == null || approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException("Un passage en perte se decide a deux : il sort un "
                + "actif des livres");
        }
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            if (contract.status() != LoanContract.Status.ACTIVE) {
                throw new WriteOffRefusedException("Le contrat " + contract.reference() + " est "
                    + contract.status() + " : seul un credit actif se passe en perte");
            }
            if (find(c, contractId).isPresent()) {
                throw new WriteOffRefusedException("Le contrat " + contract.reference()
                    + " est deja passe en perte");
            }
            ProductVersion product = product(c, contract, on);
            CurrencyRef currency = contract.currency();

            Money principal = Balances.current(c, contract.loanAccountId());
            Money receivables = openReceivables(c, contractId, currency);
            Money exposure = principal.plus(receivables);
            if (!exposure.isPositive()) {
                throw new WriteOffRefusedException("Le contrat " + contract.reference()
                    + " ne porte plus d'encours : il se cloture, il ne se passe pas en perte");
            }

            LoanStore.ClassificationState state =
                LoanStore.lastClassification(c, contractId, currency).orElse(null);
            Money provision = state == null ? Money.zero(currency) : state.provisioned();
            Money reserved = reservedFor(c, contract, product, state, receivables);

            Money reservedUsed = min(reserved, exposure);
            Money provisionUsed = min(provision, exposure.minus(reservedUsed));
            Money released = provision.minus(provisionUsed);
            Money loss = exposure.minus(reservedUsed).minus(provisionUsed);

            UUID entry = post(c, contract, product, LoanSchemas.writeOff(currency),
                EvaluationContext.builder()
                    .put("principal", principal)
                    .put("receivables", receivables)
                    .put("reserved", reservedUsed)
                    .put("provision", provisionUsed)
                    .put("loss", loss)
                    .put("released", released)
                    .build(),
                on, "LOAN_WRITE_OFF", IdempotencyKey.of("LOANWO|" + contractId), actorId, null);

            // Le hors bilan est une ecriture a part : une ecriture ne melange pas les deux mondes.
            UUID offBalance = post(c, contract, product,
                LoanSchemas.writeOffOffBalance(currency),
                EvaluationContext.builder().put("amount", exposure).build(), on,
                "LOAN_WRITE_OFF_OFF_BALANCE", IdempotencyKey.of("LOANWOHB|" + contractId), actorId,
                null);

            cancelOpenReceivables(c, contractId);
            LoanStore.writeOff(c, contractId, on);
            UUID id = insert(c, contract, on, principal, receivables, provisionUsed, reservedUsed,
                             released, loss, reason, state, entry, offBalance, actorId, approverId);
            return require(c, id);
        });
    }

    // ------------------------------------------------------------------ recouvrement

    /**
     * Encaisse sur une creance passee en perte.
     *
     * <p>Le versement est un produit de recuperation, et il sort du hors bilan d'autant. Il ne
     * peut pas depasser ce qui a ete sorti : au-dela, ce serait un enrichissement sans cause, et
     * le hors bilan deviendrait crediteur.
     */
    public Recovery recover(UUID writeOffId, Money amount, UUID channelAccountId, LocalDate on,
                            IdempotencyKey key, UUID actorId) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Un recouvrement porte un montant : " + amount);
        }
        return database.inTransaction(c -> {
            WriteOff writeOff = lockAndRequire(c, writeOffId);
            LoanContract contract = LoanStore.requireContract(c, writeOff.contractId());
            if (!amount.currency().equals(contract.currency())) {
                throw new IllegalArgumentException("Le credit est en "
                    + contract.currency().code() + " : " + amount);
            }
            Money outstanding = writeOff.outstanding();
            if (amount.isGreaterThan(outstanding)) {
                throw new WriteOffRefusedException("Recouvrement de " + amount + " sur "
                    + outstanding + " restant du au hors bilan pour " + writeOff.contractReference()
                    + " : on ne recouvre pas plus que ce qui a ete passe en perte");
            }
            ProductVersion product = product(c, contract, on);

            UUID entry = post(c, contract, product, LoanSchemas.recovery(contract.currency()),
                EvaluationContext.builder().put("amount", amount).build(), on, "LOAN_RECOVERY",
                key, actorId, channelAccountId);
            UUID offBalance = post(c, contract, product,
                LoanSchemas.recoveryOffBalance(contract.currency()),
                EvaluationContext.builder().put("amount", amount).build(), on,
                "LOAN_RECOVERY_OFF_BALANCE",
                IdempotencyKey.of("LOANRECHB|" + key.value()), actorId, channelAccountId);

            UUID id = Ids.newId();
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan_recovery(id, write_off_id, recovered_on, amount,"
                + " channel_account_id, entry_id, off_balance_entry_id, recorded_by)"
                + " VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, writeOffId);
                ps.setObject(3, on);
                ps.setBigDecimal(4, amount.amount());
                ps.setObject(5, channelAccountId);
                ps.setObject(6, entry);
                ps.setObject(7, offBalance);
                ps.setObject(8, actorId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Enregistrement du recouvrement", e);
            }
            return new Recovery(id, writeOffId, on, amount, channelAccountId, actorId);
        });
    }

    // ------------------------------------------------------------------ lecture

    public static Optional<WriteOff> find(Connection c, UUID contractId) {
        return one(c, SELECT + " WHERE w.contract_id = ?", contractId);
    }

    public static WriteOff require(Connection c, UUID id) {
        return one(c, SELECT + " WHERE w.id = ?", id).orElseThrow(
            () -> new IllegalArgumentException("Passage en perte inconnu : " + id));
    }

    /** Les credits passes en perte d'une entite, du plus recent au plus ancien. */
    public static List<WriteOff> writeOffs(Connection c, UUID legalEntityId) {
        List<WriteOff> found = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE w.legal_entity_id = ? ORDER BY w.written_off_on DESC, k.reference")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des credits passes en perte", e);
        }
        return found;
    }

    public static List<Recovery> recoveries(Connection c, UUID writeOffId, CurrencyRef currency) {
        List<Recovery> found = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, write_off_id, recovered_on, amount, channel_account_id, recorded_by"
            + "  FROM loan_recovery WHERE write_off_id = ? ORDER BY recovered_on, created_at")) {
            ps.setObject(1, writeOffId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(new Recovery(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                           rs.getObject(3, LocalDate.class),
                                           Money.of(rs.getBigDecimal(4), currency).roundToCurrency(),
                                           rs.getObject(5, UUID.class),
                                           rs.getObject(6, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des recouvrements", e);
        }
        return found;
    }

    /**
     * Ce que le hors bilan doit porter : tout ce qui a ete passe en perte, moins ce qui a ete
     * recouvre. Le rapprochement de la nuit le confronte au solde du compte.
     */
    public static Money outstandingWrittenOff(Connection c, UUID legalEntityId,
                                              CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(w.principal_written + w.receivables_written), 0)"
            + "     - COALESCE((SELECT SUM(r.amount) FROM loan_recovery r"
            + "                   JOIN loan_write_off x ON x.id = r.write_off_id"
            + "                  WHERE x.legal_entity_id = ?), 0)"
            + "  FROM loan_write_off w WHERE w.legal_entity_id = ?")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1), currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Encours hors bilan des creances passees en perte", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private static final String SELECT =
        "SELECT w.id, w.contract_id, k.reference, w.legal_entity_id, w.written_off_on,"
        + " w.principal_written, w.receivables_written, w.provision_used, w.reserved_used,"
        + " w.provision_released, w.loss_recognised, w.reason, w.bucket_code, w.days_past_due,"
        + " w.created_by, w.approved_by, cur.code, cur.scale, cur.rounding_mode,"
        + " COALESCE((SELECT SUM(r.amount) FROM loan_recovery r WHERE r.write_off_id = w.id), 0)"
        + "  FROM loan_write_off w JOIN loan_contract k ON k.id = w.contract_id"
        + "  JOIN currency cur ON cur.code = k.currency";

    private static Optional<WriteOff> one(Connection c, String sql, UUID key) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du passage en perte", e);
        }
    }

    private static WriteOff read(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(17), rs.getInt(18),
                                               java.math.RoundingMode.valueOf(rs.getString(19)));
        return new WriteOff(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getObject(4, UUID.class),
                            rs.getObject(5, LocalDate.class),
                            money(rs.getBigDecimal(6), currency), money(rs.getBigDecimal(7), currency),
                            money(rs.getBigDecimal(8), currency), money(rs.getBigDecimal(9), currency),
                            money(rs.getBigDecimal(10), currency),
                            money(rs.getBigDecimal(11), currency),
                            money(rs.getBigDecimal(20), currency), rs.getString(12),
                            rs.getString(13), rs.getObject(14, Integer.class),
                            rs.getObject(15, UUID.class), rs.getObject(16, UUID.class));
    }

    private static Money money(BigDecimal amount, CurrencyRef currency) {
        return Money.of(amount, currency).roundToCurrency();
    }

    private WriteOff lockAndRequire(Connection c, UUID writeOffId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM loan_write_off WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, writeOffId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Passage en perte inconnu : " + writeOffId);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou du passage en perte " + writeOffId, e);
        }
        return require(c, writeOffId);
    }

    private static Money openReceivables(Connection c, UUID contractId, CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(outstanding), 0) FROM loan_receivable"
            + " WHERE contract_id = ? AND NOT cancelled AND category <> 'PRINCIPAL'")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1), currency).roundToCurrency();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Creances ouvertes du contrat " + contractId, e);
        }
    }

    /**
     * Les interets du contrat deja sortis du resultat.
     *
     * <p>Un credit suspendu constate ses interets directement en reserves : les creances
     * d'interet ouvertes ont alors leur contrepartie au passif, et c'est elle qui absorbe leur
     * sortie. Le montant est borne par le solde du compte de reserves : on ne debite pas un
     * compte de contrepartie au-dela de ce qu'il porte.
     */
    private static Money reservedFor(Connection c, LoanContract contract, ProductVersion product,
                                     LoanStore.ClassificationState state, Money receivables) {
        if (state == null || !state.suspended()) {
            return Money.zero(contract.currency());
        }
        Money interestReceivables = interestReceivables(c, contract.id(), contract.currency());
        Money reservedBalance = Balances.current(c, LoanCatalog.reservedInterest(product));
        return min(min(interestReceivables, reservedBalance), receivables);
    }

    private static Money interestReceivables(Connection c, UUID contractId, CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(outstanding), 0) FROM loan_receivable"
            + " WHERE contract_id = ? AND NOT cancelled"
            + "   AND category IN ('INTEREST','LATE_INTEREST')")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1), currency).roundToCurrency();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Creances d'interet du contrat " + contractId, e);
        }
    }

    private static void cancelOpenReceivables(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_receivable SET cancelled = TRUE, outstanding = 0"
            + " WHERE contract_id = ? AND NOT cancelled")) {
            ps.setObject(1, contractId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des creances du contrat " + contractId, e);
        }
    }

    private UUID insert(Connection c, LoanContract contract, LocalDate on, Money principal,
                        Money receivables, Money provisionUsed, Money reservedUsed, Money released,
                        Money loss, String reason, LoanStore.ClassificationState state, UUID entry,
                        UUID offBalance, UUID actorId, UUID approverId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_write_off(id, contract_id, legal_entity_id, written_off_on,"
            + " principal_written, receivables_written, provision_used, reserved_used,"
            + " provision_released, loss_recognised, reason, bucket_code, days_past_due, entry_id,"
            + " off_balance_entry_id, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contract.id());
            ps.setObject(3, contract.legalEntityId());
            ps.setObject(4, on);
            ps.setBigDecimal(5, principal.amount());
            ps.setBigDecimal(6, receivables.amount());
            ps.setBigDecimal(7, provisionUsed.amount());
            ps.setBigDecimal(8, reservedUsed.amount());
            ps.setBigDecimal(9, released.amount());
            ps.setBigDecimal(10, loss.amount());
            ps.setString(11, reason);
            ps.setString(12, state == null ? null : state.bucketCode());
            ps.setObject(13, null);
            ps.setObject(14, entry);
            ps.setObject(15, offBalance);
            ps.setObject(16, actorId);
            ps.setObject(17, approverId);
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new WriteOffRefusedException("Le contrat " + contract.reference()
                    + " est deja passe en perte");
            }
            throw new LedgerStoreException("Enregistrement du passage en perte", e);
        }
        return id;
    }

    private UUID post(Connection c, LoanContract contract, ProductVersion product,
                      EventTemplate template, EvaluationContext input, LocalDate on,
                      String transactionType, IdempotencyKey key, UUID actorId,
                      UUID channelAccountId) {
        List<PostingLine> lines = SchemaEngine.linesFor(
            template, input, resolver(contract, product, channelAccountId), contract.currency(), on);
        PostingResult result = postingService.post(
            PostingCommand.online(key, contract.legalEntityId(), on, transactionType, actorId, lines)
                .withBranch(contract.branchId()));
        return result.entryId();
    }

    private AccountResolver resolver(LoanContract contract, ProductVersion product,
                                     UUID channelAccountId) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> contract.loanAccountId();
            case PARAMETER -> switch (reference.value()) {
                // Un recouvrement s'encaisse ou il se presente : au guichet, par virement, par
                // un tiers. Le compte de reglement du contrat n'est pas toujours celui-la.
                case LoanSchemas.ROLE_SETTLEMENT -> channelAccountId == null
                    ? contract.settlementAccountId() : channelAccountId;
                case LoanSchemas.ROLE_ACCRUED -> LoanCatalog.accruedReceivable(product);
                case LoanSchemas.ROLE_RESERVED_INTEREST -> LoanCatalog.reservedInterest(product);
                case LoanSchemas.ROLE_PROVISION_ALLOWANCE ->
                    LoanCatalog.provisionAllowance(product);
                case LoanSchemas.ROLE_PROVISION_RELEASE -> LoanCatalog.provisionRelease(product);
                case LoanSchemas.ROLE_WRITE_OFF_LOSS -> LoanCatalog.account(
                    product, LoanCatalog.P_WRITE_OFF_LOSS, "perte sur creance irrecouvrable");
                case LoanSchemas.ROLE_RECOVERY_INCOME -> LoanCatalog.account(
                    product, LoanCatalog.P_RECOVERY_INCOME, "recuperation sur creance amortie");
                case LoanSchemas.ROLE_WRITTEN_OFF -> LoanCatalog.account(
                    product, LoanCatalog.P_WRITTEN_OFF, "creances passees en perte (hors bilan)");
                case LoanSchemas.ROLE_WRITTEN_OFF_COUNTERPART -> LoanCatalog.account(
                    product, LoanCatalog.P_WRITTEN_OFF_COUNTERPART,
                    "contrepartie du hors bilan des creances passees en perte");
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage du produit " + product.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "reference non resolvable pour une ecriture de perte");
        };
    }

    private static ProductVersion product(Connection c, LoanContract contract, LocalDate on) {
        return ProductCatalog.resolveAt(c, contract.legalEntityId(), contract.productCode(), on);
    }

    private static Money min(Money a, Money b) {
        return a.isLessThan(b) ? a : b;
    }
}
