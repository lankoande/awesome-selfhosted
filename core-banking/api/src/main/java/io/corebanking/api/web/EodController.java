package io.corebanking.api.web;

import io.corebanking.api.config.EodEngines;
import io.corebanking.api.usecase.EodUseCases;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import io.corebanking.tfj.RunMode;
import io.corebanking.tfj.TfjRun;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Traitement de fin de journee. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/eod/runs")
public class EodController {

    private final UseCaseExecutor executor;
    private final EodUseCases.Run run;
    private final EodUseCases.Resume resume;
    private final EodUseCases.Cancel cancel;
    private final EodUseCases.Read read;

    public EodController(UseCaseExecutor executor, EodEngines engines) {
        this.executor = executor;
        this.run = new EodUseCases.Run(engines);
        this.resume = new EodUseCases.Resume(engines);
        this.cancel = new EodUseCases.Cancel(engines);
        this.read = new EodUseCases.Read(engines);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TfjRun run(Caller caller, @PathVariable UUID legalEntityId,
                      @RequestBody Requests.RunEod body) {
        RunMode mode = body.mode() == null ? RunMode.REAL : RunMode.valueOf(body.mode());
        return executor.run(caller, run, new EodUseCases.Launch(
            legalEntityId, body.businessDate(), mode, Callers.actorId(caller)));
    }

    @GetMapping("/{runId}")
    public TfjRun read(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID runId) {
        return executor.run(caller, read, new EodUseCases.Lookup(legalEntityId, runId));
    }

    @PostMapping("/{runId}/resume")
    public TfjRun resume(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID runId) {
        return executor.run(caller, resume,
                            new EodUseCases.Resumption(legalEntityId, runId, Callers.actorId(caller)));
    }

    @PostMapping("/{runId}/cancel")
    public TfjRun cancel(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID runId,
                         @RequestBody Requests.CancelEod body) {
        return executor.run(caller, cancel, new EodUseCases.Cancellation(
            legalEntityId, runId, body.reversalBookingDate(), body.reason(),
            Callers.actorId(caller)));
    }
}
