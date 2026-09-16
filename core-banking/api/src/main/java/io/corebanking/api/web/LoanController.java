package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.LoanUseCases;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.service.LoanService;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit. Le contrat se cree et se consulte ; le reglement d'echeance s'impute au guichet sous sa
 * cle d'idempotence ; le deblocage et le remboursement anticipe sont soumis, puis approuves par
 * un second porteur.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/loans")
public class LoanController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final LoanUseCases.Create create;
    private final LoanUseCases.Repay repay;
    private final LoanUseCases.Read read;
    private final LoanUseCases.List_ list;
    private final Database database;
    private final LoanUseCases.Recover recover;
    private final LoanUseCases.ReadWriteOff readWriteOff;

    public LoanController(UseCaseExecutor executor, Database database, AccountDirectory accounts,
                          LoanService loans, MakerChecker makerChecker,
                          io.corebanking.loan.service.LoanWriteOffService writeOffs) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.create = new LoanUseCases.Create(database, accounts);
        this.repay = new LoanUseCases.Repay(database, loans);
        this.read = new LoanUseCases.Read(database, loans);
        this.list = new LoanUseCases.List_(database);
        this.database = database;
        this.recover = new LoanUseCases.Recover(database, writeOffs);
        this.readWriteOff = new LoanUseCases.ReadWriteOff(database);
    }

    /** Les contrats de l'entite, par pages ; le statut est un filtre facultatif. */
    @GetMapping
    public io.corebanking.api.usecase.Paging.Paged<io.corebanking.loan.service.LoanContract> list(
            Caller caller, @PathVariable UUID legalEntityId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            io.corebanking.api.usecase.Paging.PageRequest page) {
        io.corebanking.loan.service.LoanContract.Status filter = status == null ? null
            : io.corebanking.loan.service.LoanContract.Status.valueOf(status);
        return executor.run(caller, list,
                            new LoanUseCases.LoanQuery(legalEntityId, filter, page));
    }

    /** Rechelonner, c'est modifier ce que le client devra : propose par l'un, approuve par un autre. */
    @PostMapping("/{contractId}/rescheduling")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View reschedule(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID contractId,
                                        @RequestBody Requests.Rescheduling body) {
        return makerChecker.submit(caller, legalEntityId, "LOAN_RESCHEDULE", Payloads.of(
            "contractId", contractId, "instalments", body.instalments(),
            "firstDueDate", body.firstDueDate(), "effectiveFrom", body.effectiveFrom(),
            "reason", body.reason()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created create(Caller caller, @PathVariable UUID legalEntityId,
                                   @RequestBody Requests.CreateLoan body) {
        UUID id = executor.run(caller, create, new LoanUseCases.Draft(
            legalEntityId, body.reference(), body.productCode(), body.currency(),
            body.loanAccountId(), body.settlementAccountId(), body.principal(), body.disbursedOn(),
            body.customerPartyId(), Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    /** L'argent sort : les conditions sont proposees par l'un, approuvees par un autre. */
    @PostMapping("/{contractId}/disbursement")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View disburse(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID contractId,
                                      @RequestBody Requests.Disbursement body) {
        return makerChecker.submit(caller, legalEntityId, "LOAN_DISBURSE", Payloads.of(
            "contractId", contractId,
            "annualRatePercent", body.annualRatePercent(), "frequency", body.frequency(),
            "instalments", body.instalments(), "graceInstalments", body.graceInstalments(),
            "firstDueDate", body.firstDueDate(), "method", body.method(),
            "dayCount", body.dayCount(), "periodicFee", body.periodicFee(),
            "insuranceBasis", body.insuranceBasis(),
            "insuranceRatePercent", body.insuranceRatePercent(),
            "taxOnInterestPercent", body.taxOnInterestPercent(),
            "upfrontFees", body.upfrontFees()));
    }

    @PostMapping("/{contractId}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanService.Settlement repay(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID contractId, IdempotencyKey key,
                                        @RequestBody Requests.LoanRepayment body) {
        return executor.run(caller, repay, new LoanUseCases.Repayment(
            contractId, body.amount(), body.currency(), body.valueDate(), key,
            Callers.actorId(caller)));
    }

    /** Un droit de l'emprunteur, enregistre par l'agent ; l'echeancier qui en resulte est approuve par un second. */
    @PostMapping("/{contractId}/prepayments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View prepay(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID contractId, IdempotencyKey key,
                                    @RequestBody Requests.LoanPrepayment body) {
        return makerChecker.submit(caller, legalEntityId, "LOAN_PREPAY", Payloads.of(
            "contractId", contractId, "amount", body.amount(), "currency", body.currency(),
            "mode", body.mode(), "idempotencyKey", key.value()));
    }


    /**
     * Passage en perte, a deux : la sortie d'un actif des livres. La creance, elle, reste due et
     * se suit au hors bilan.
     */
    @PostMapping("/{contractId}/write-off")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View writeOff(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID contractId,
                                      @RequestBody Requests.LoanWriteOffRequest body) {
        if (body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("Un passage en perte porte son motif");
        }
        return makerChecker.submit(caller, legalEntityId, "LOAN_WRITE_OFF", Payloads.of(
            "contractId", contractId, "reason", body.reason(),
            "writtenOffOn", body.writtenOffOn()));
    }

    /** Ce qui rentre apres la perte : un produit de recuperation, jamais un remboursement. */
    @PostMapping("/{contractId}/recoveries")
    @ResponseStatus(HttpStatus.CREATED)
    public io.corebanking.loan.service.LoanWriteOffService.Recovery recover(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID contractId,
            IdempotencyKey key, @RequestBody Requests.LoanRecoveryRequest body) {
        io.corebanking.loan.service.LoanContract contract = LoanUseCases.require(database,
                                                                                 contractId);
        if (body.amount() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : amount");
        }
        java.time.LocalDate on = body.recoveredOn() == null
            ? database.inTransaction(c -> io.corebanking.api.usecase.AccountUseCases
                  .businessDate(c, legalEntityId))
            : body.recoveredOn();
        return executor.run(caller, recover, new LoanUseCases.RecoveryCommand(
            legalEntityId, contractId,
            io.corebanking.kernel.money.Money.of(body.amount(), contract.currency()),
            body.channelAccountId(), on, key, Callers.actorId(caller)));
    }

    /** Le dossier de perte : ce qui est sorti, ce qui a ete recouvre, ce qui reste du. */
    @GetMapping("/{contractId}/write-off")
    public LoanUseCases.WriteOffView writeOffView(Caller caller, @PathVariable UUID legalEntityId,
                                                  @PathVariable UUID contractId) {
        return executor.run(caller, readWriteOff, new LoanUseCases.Lookup(contractId));
    }

    /** Revision de taux, a deux : un nouvel echeancier sur le capital restant du. */
    @PostMapping("/{contractId}/rate-revision")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View reviseRate(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID contractId,
                                        @RequestBody Requests.LoanRateRevision body) {
        if (body.annualRatePercent() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : annualRatePercent");
        }
        return makerChecker.submit(caller, legalEntityId, "LOAN_RATE_REVISION", Payloads.of(
            "contractId", contractId,
            "annualRatePercent", body.annualRatePercent().toPlainString(),
            "effectiveFrom", body.effectiveFrom()));
    }

    @GetMapping("/{contractId}")
    public LoanUseCases.LoanView read(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID contractId) {
        return executor.run(caller, read, new LoanUseCases.Lookup(contractId));
    }
}
