package io.corebanking.api.web;

import io.corebanking.api.usecase.FxUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FxPositions;
import io.corebanking.ledger.store.FxRates;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
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
 * Change : cours de reference et positions. Un cours cote a deux contrôle tout cours applique
 * par une ecriture ; une position dit ou l'exposition d'une devise se mesure, et l'arrete la
 * revalorise. La lecture rend l'exposition du jour : solde en devise, contre-valeur portee,
 * cours, ecart latent.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class FxController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final FxUseCases.ReadRates readRates;
    private final FxUseCases.ReadPositions readPositions;

    public FxController(UseCaseExecutor executor, Database database, MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readRates = new FxUseCases.ReadRates(database);
        this.readPositions = new FxUseCases.ReadPositions(database);
    }

    /** Un cours de cloture, a deux : le coteur propose, un second valide. */
    @PostMapping("/fx-rates")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View quote(Caller caller, @PathVariable UUID legalEntityId,
                                   @RequestBody Requests.FxRateRequest body) {
        if (body.quotedOn() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : quotedOn");
        }
        if (body.rate() == null || body.rate().signum() <= 0) {
            throw new IllegalArgumentException("Cours non strictement positif : " + body.rate());
        }
        return makerChecker.submit(caller, legalEntityId, "FX_RATE_QUOTE", Payloads.of(
            "currency", body.currency(), "quotedOn", body.quotedOn(),
            "rate", body.rate().toPlainString(), "source", body.source()));
    }

    @GetMapping("/fx-rates")
    public List<FxRates.Rate> rates(Caller caller, @PathVariable UUID legalEntityId,
                                    @RequestParam(required = false) String currency,
                                    @RequestParam(required = false) Integer limit) {
        return executor.run(caller, readRates,
                            new FxUseCases.RateQuery(legalEntityId, currency, limit));
    }

    /** Une position de change, a deux : compte de position, contre-valeur, resultat, marge. */
    @PostMapping("/fx-positions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declare(Caller caller, @PathVariable UUID legalEntityId,
                                     @RequestBody Requests.FxPositionRequest body) {
        if (body.toleranceBps() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : toleranceBps");
        }
        return makerChecker.submit(caller, legalEntityId, "FX_POSITION_DECLARE", Payloads.of(
            "currency", body.currency(), "positionAccountId", body.positionAccountId(),
            "counterValueAccountId", body.counterValueAccountId(),
            "gainAccountId", body.gainAccountId(), "lossAccountId", body.lossAccountId(),
            "toleranceBps", body.toleranceBps()));
    }

    @GetMapping("/fx-positions")
    public List<FxPositions.Exposure> positions(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readPositions, new FxUseCases.PositionQuery(legalEntityId));
    }
}
