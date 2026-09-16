package io.corebanking.tfj.steps;

import io.corebanking.deposits.TermDepositService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Interets courus des depots a terme.
 *
 * <p>L'etape vient avec les autres constatations d'interets, et avant le cliche des soldes : la
 * charge du jour appartient a la journee arretee. Elle est bloquante — un depot a terme est une
 * dette de la banque dont le prix est contractuel : ne pas la constater surevaluerait le resultat
 * d'un montant que personne ne remarquerait avant le rapprochement.
 */
public final class TermDepositAccrualStep implements TfjStep {

    private final TermDepositService termDeposits;

    public TermDepositAccrualStep(TermDepositService termDeposits) {
        this.termDeposits = termDeposits;
    }

    @Override
    public String name() {
        return "TERM_DEPOSIT_ACCRUAL";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        TermDepositService.Accrued accrued = termDeposits.accrue(
            context.legalEntityId(), context.businessDate(), context.runId(), context.actorId());
        return new StepResult(accrued.deposits(), accrued.deposits(), List.of());
    }
}
