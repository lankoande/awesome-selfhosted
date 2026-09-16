package io.corebanking.api.web;

import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.api.usecase.StandingOrderUseCases;
import io.corebanking.deposits.StandingOrderService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
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
 * Ordres permanents : le virement que le client programme une fois.
 *
 * <p>La mise en place engage des virements que personne ne redemandera : elle se decide a deux,
 * comme un mandat de prelevement. La revocation, elle, est un droit du client — l'agent
 * l'enregistre, il ne la decide pas.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/standing-orders")
public class StandingOrderController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final Database database;
    private final StandingOrderUseCases.Cancel cancel;
    private final StandingOrderUseCases.ReadOrders readOrders;
    private final StandingOrderUseCases.ReadOrder readOrder;

    public StandingOrderController(UseCaseExecutor executor, Database database,
                                   MakerChecker makerChecker,
                                   StandingOrderService standingOrders) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.database = database;
        this.cancel = new StandingOrderUseCases.Cancel(database, standingOrders);
        this.readOrders = new StandingOrderUseCases.ReadOrders(database);
        this.readOrder = new StandingOrderUseCases.ReadOrder(database);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View register(Caller caller, @PathVariable UUID legalEntityId,
                                      @RequestBody Requests.StandingOrderRequest body) {
        // Nature, periodicite et beneficiaire se valident a la soumission : un valideur ne doit
        // pas decouvrir une demande fausse.
        kind(body.kind());
        frequency(body.frequency());
        if (body.accountId() == null || body.reference() == null || body.startDate() == null) {
            throw new IllegalArgumentException(
                "Un ordre permanent porte son compte, sa reference et sa date de debut");
        }
        return makerChecker.submit(caller, legalEntityId, "STANDING_ORDER_REGISTER", Payloads.of(
            "accountId", body.accountId(), "reference", body.reference(),
            "kind", body.kind().trim().toUpperCase(Locale.ROOT),
            "amount", body.amount() == null ? null : body.amount().toPlainString(),
            "floorAmount", body.floorAmount() == null ? null : body.floorAmount().toPlainString(),
            "beneficiaryAccountId", body.beneficiaryAccountId(),
            "beneficiaryName", body.beneficiaryName(), "beneficiaryBank", body.beneficiaryBank(),
            "beneficiaryAccount", body.beneficiaryAccount(),
            "frequency", body.frequency().trim().toUpperCase(Locale.ROOT),
            "startDate", body.startDate(), "endDate", body.endDate(),
            "occurrences", body.occurrences(), "maxAttempts", body.maxAttempts(),
            "narrative", body.narrative()));
    }

    /** Revocation : ce qui est parti reste parti, rien de plus ne partira. */
    @PostMapping("/{standingOrderId}/revocation")
    public StandingOrderService.StandingOrder revoke(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID standingOrderId,
            @RequestBody Requests.StandingOrderRevocation body) {
        LocalDate on = body.on() == null
            ? database.inTransaction(c -> AccountUseCases.businessDate(c, legalEntityId))
            : body.on();
        return executor.run(caller, cancel, new StandingOrderUseCases.Revocation(
            legalEntityId, standingOrderId, on, body.reason(), Callers.actorId(caller)));
    }

    @GetMapping
    public List<StandingOrderService.StandingOrder> orders(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String status) {
        return executor.run(caller, readOrders,
                            new StandingOrderUseCases.EntityQuery(legalEntityId, status(status)));
    }

    /** L'ordre et ce que chaque echeance a donne : execution, rejet motive, ou rien a balayer. */
    @GetMapping("/{standingOrderId}")
    public StandingOrderUseCases.OrderView order(Caller caller, @PathVariable UUID legalEntityId,
                                                 @PathVariable UUID standingOrderId) {
        return executor.run(caller, readOrder,
                            new StandingOrderUseCases.OrderQuery(legalEntityId, standingOrderId));
    }

    private static StandingOrderService.Kind kind(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : kind");
        }
        try {
            return StandingOrderService.Kind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nature d'ordre permanent inconnue : " + value
                + " (FIXED pour un montant, SWEEP pour ce qui depasse un plancher)");
        }
    }

    private static io.corebanking.kernel.time.Periodicity frequency(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : frequency");
        }
        try {
            return io.corebanking.kernel.time.Periodicity.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Periodicite inconnue : " + value + " ("
                + java.util.Arrays.toString(io.corebanking.kernel.time.Periodicity.values()) + ")");
        }
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "COMPLETED", "CANCELLED").contains(normalised)) {
            throw new IllegalArgumentException("Statut d'ordre permanent inconnu : " + value
                + " (ACTIVE, COMPLETED, CANCELLED)");
        }
        return normalised;
    }
}
