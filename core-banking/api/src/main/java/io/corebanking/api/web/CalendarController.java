package io.corebanking.api.web;

import io.corebanking.security.Caller;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conditions de banque : regles de date de valeur et jours feries. Un ferie deplace des dates de
 * valeur et des echeances, une regle deplace des interets — l'un et l'autre se valident a deux.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/calendar")
public class CalendarController {

    private final MakerChecker makerChecker;

    public CalendarController(MakerChecker makerChecker) {
        this.makerChecker = makerChecker;
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

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View addHoliday(Caller caller, @PathVariable UUID legalEntityId,
                                        @RequestBody Requests.Holiday body) {
        return makerChecker.submit(caller, legalEntityId, "HOLIDAY_ADD",
                                   Payloads.of("date", body.date(), "label", body.label()));
    }
}
