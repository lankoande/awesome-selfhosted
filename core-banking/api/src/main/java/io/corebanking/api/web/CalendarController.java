package io.corebanking.api.web;

import io.corebanking.api.usecase.NetworkUseCases;
import io.corebanking.calendar.Calendars;
import io.corebanking.ledger.store.Database;
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
 * Conditions de banque : regles de date de valeur, heures limites des canaux et jours feries. Un
 * ferie deplace des dates de valeur et des echeances, une regle ou une heure limite deplace des
 * interets — tous se valident a deux.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/calendar")
public class CalendarController {

    private final MakerChecker makerChecker;
    private final UseCaseExecutor executor;
    private final NetworkUseCases.ReadCalendar conditions;

    public CalendarController(MakerChecker makerChecker, UseCaseExecutor executor,
                              Database database) {
        this.makerChecker = makerChecker;
        this.executor = executor;
        this.conditions = new NetworkUseCases.ReadCalendar(database);
    }

    /**
     * Les conditions de banque, ensemble.
     *
     * <p>Une date de valeur est le produit d'une regle, d'une heure limite et d'un calendrier :
     * les lire separement ne permettrait d'expliquer aucune des dates que les clients contestent.
     */
    @GetMapping
    public Calendars.Conditions conditions(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, conditions, new NetworkUseCases.Query(legalEntityId));
    }

    @PostMapping("/value-date-rules")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View addRule(Caller caller, @PathVariable UUID legalEntityId,
                                     @RequestBody Requests.ValueDateRuleRequest body) {
        return makerChecker.submit(caller, legalEntityId, "VALUE_DATE_RULE_ADD", Payloads.of(
            "operationType", body.operationType(), "channel", body.channel(),
            "direction", body.direction(), "offset", body.offset(), "unit", body.unit(),
            "convention", body.convention(), "validFrom", body.validFrom(),
            "validTo", body.validTo()));
    }

    /** Heure limite d'un canal : au-dela, la valeur du jour ouvre suivant, ou le canal ferme. */
    @PostMapping("/cutoffs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View addCutoff(Caller caller, @PathVariable UUID legalEntityId,
                                       @RequestBody Requests.ChannelCutoffRequest body) {
        // L'heure se valide a la soumission : un valideur ne doit pas decouvrir une demande fausse.
        if (body.cutoffTime() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : cutoffTime");
        }
        try {
            java.time.LocalTime.parse(body.cutoffTime());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Heure limite attendue au format HH:mm : "
                                               + body.cutoffTime());
        }
        return makerChecker.submit(caller, legalEntityId, "CHANNEL_CUTOFF_ADD", Payloads.of(
            "channel", body.channel(), "cutoffTime", body.cutoffTime(),
            "closesChannel", body.closesChannel(), "validFrom", body.validFrom(),
            "validTo", body.validTo()));
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View addHoliday(Caller caller, @PathVariable UUID legalEntityId,
                                        @RequestBody Requests.Holiday body) {
        return makerChecker.submit(caller, legalEntityId, "HOLIDAY_ADD",
                                   Payloads.of("date", body.date(), "label", body.label()));
    }
}
