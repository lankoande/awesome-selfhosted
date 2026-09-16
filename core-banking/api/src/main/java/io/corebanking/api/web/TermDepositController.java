package io.corebanking.api.web;

import io.corebanking.api.usecase.TermDepositUseCases;
import io.corebanking.deposits.TermDepositService;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Depots a terme.
 *
 * <p>La souscription et la rupture passent par le controle a deux : la premiere engage la banque
 * sur un prix et sur une duree, la seconde defait cet engagement. Les lectures rendent le contrat
 * et ce que chaque echeance a donne.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/term-deposits")
public class TermDepositController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final TermDepositUseCases.ReadDeposits readDeposits;
    private final TermDepositUseCases.ReadDeposit readDeposit;

    public TermDepositController(UseCaseExecutor executor,
                                 io.corebanking.ledger.store.Database database,
                                 MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readDeposits = new TermDepositUseCases.ReadDeposits(database);
        this.readDeposit = new TermDepositUseCases.ReadDeposit(database);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View subscribe(Caller caller, @PathVariable UUID legalEntityId,
                                       @RequestBody Requests.TermDepositRequest body) {
        // Instruction de terme et periodicite se valident a la soumission : un valideur ne doit
        // pas decouvrir une demande fausse.
        instruction(body.maturityInstruction());
        if (body.depositAccountId() == null || body.settlementAccountId() == null
            || body.reference() == null || body.principal() == null
            || body.termMonths() == null) {
            throw new IllegalArgumentException("Un depot a terme porte sa reference, ses deux "
                + "comptes, son capital et sa duree");
        }
        return makerChecker.submit(caller, legalEntityId, "TERM_DEPOSIT_SUBSCRIBE", Payloads.of(
            "reference", body.reference(), "depositAccountId", body.depositAccountId(),
            "settlementAccountId", body.settlementAccountId(),
            "principal", body.principal().toPlainString(),
            "grantedRatePercent", body.grantedRatePercent() == null ? null
                                  : body.grantedRatePercent().toPlainString(),
            "termMonths", body.termMonths(),
            "interestPayment", body.interestPayment(),
            "maturityInstruction",
            body.maturityInstruction().trim().toUpperCase(Locale.ROOT)));
    }

    /** Rupture avant terme : le client reprend ses fonds, la banque reprend son prix. */
    @PostMapping("/{termDepositId}/break")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View breakEarly(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID termDepositId,
                                        @RequestBody Requests.TermDepositBreak body) {
        if (body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("Une rupture avant terme porte son motif");
        }
        return makerChecker.submit(caller, legalEntityId, "TERM_DEPOSIT_BREAK", Payloads.of(
            "termDepositId", termDepositId, "reason", body.reason()));
    }

    @GetMapping
    public List<TermDepositService.TermDeposit> deposits(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String status) {
        return executor.run(caller, readDeposits,
                            new TermDepositUseCases.EntityQuery(legalEntityId, status(status)));
    }

    /** Le contrat et ce que chaque echeance a donne : interets servis, terme, rupture. */
    @GetMapping("/{termDepositId}")
    public TermDepositUseCases.DepositView deposit(Caller caller,
                                                   @PathVariable UUID legalEntityId,
                                                   @PathVariable UUID termDepositId) {
        return executor.run(caller, readDeposit,
                            new TermDepositUseCases.DepositQuery(legalEntityId, termDepositId));
    }

    private static void instruction(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : maturityInstruction");
        }
        try {
            TermDepositService.MaturityInstruction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Instruction de terme inconnue : " + value
                + " (PAY_OUT, RENEW_PRINCIPAL, RENEW_ALL)");
        }
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "MATURED", "BROKEN").contains(status)) {
            throw new IllegalArgumentException("Statut de depot a terme inconnu : " + value
                + " (ACTIVE, MATURED, BROKEN)");
        }
        return status;
    }
}
