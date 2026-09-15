package io.corebanking.api.config;

import io.corebanking.calendar.Calendars;
import io.corebanking.fee.service.FeeChargingService;
import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.service.LoanClassificationService;
import io.corebanking.loan.service.LoanLateChargesService;
import io.corebanking.loan.service.LoanMobilisationService;
import io.corebanking.loan.service.LoanService;
import io.corebanking.tfj.StandardTfj;
import io.corebanking.tfj.TfjEngine;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Un moteur de TFJ par entite : la bascule de journee depend du calendrier de l'entite, et le
 * calendrier ne se prend pas en parametre a chaque appel.
 */
public final class EodEngines {

    private final Database database;
    private final PostingService postingService;
    private final BatchInterestAccrualService interestService;
    private final FeeChargingService feeService;
    private final LoanService loanService;
    private final LoanMobilisationService mobilisationService;
    private final LoanLateChargesService lateService;
    private final LoanClassificationService classificationService;
    private final ConcurrentHashMap<UUID, TfjEngine> engines = new ConcurrentHashMap<>();

    public EodEngines(Database database, PostingService postingService,
                      BatchInterestAccrualService interestService, FeeChargingService feeService,
                      LoanService loanService, LoanMobilisationService mobilisationService,
                      LoanLateChargesService lateService,
                      LoanClassificationService classificationService) {
        this.database = database;
        this.postingService = postingService;
        this.interestService = interestService;
        this.feeService = feeService;
        this.loanService = loanService;
        this.mobilisationService = mobilisationService;
        this.lateService = lateService;
        this.classificationService = classificationService;
    }

    public TfjEngine forEntity(UUID legalEntityId) {
        return engines.computeIfAbsent(legalEntityId, entity -> StandardTfj.engine(
            database, postingService, interestService, feeService, loanService,
            mobilisationService, lateService, classificationService,
            Calendars.load(database, entity).calendar()));
    }
}
