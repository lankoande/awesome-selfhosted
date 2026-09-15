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

    public LoanController(UseCaseExecutor executor, Database database, AccountDirectory accounts,
                          LoanService loans, MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.create = new LoanUseCases.Create(database, accounts);
        this.repay = new LoanUseCases.Repay(database, loans);
        this.read = new LoanUseCases.Read(database, loans);
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

    @GetMapping("/{contractId}")
    public LoanUseCases.LoanView read(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID contractId) {
        return executor.run(caller, read, new LoanUseCases.Lookup(contractId));
    }
}
