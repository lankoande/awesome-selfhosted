package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.OperationsService;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.error.LedgerViolation;
import io.corebanking.ledger.store.AccountBlockedException;
import io.corebanking.party.PartyService;
import io.corebanking.product.ProductFamily;
import io.corebanking.security.AccessDeniedException;
import io.corebanking.security.KeycloakCallerFactory;
import io.corebanking.tfj.TfjEngine;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Un refus est une reponse, pas une trace de pile : chaque exception du socle a son statut et son
 * message, au format « problem details » (RFC 9457). Les refus d'habilitation sont 403, les
 * conflits d'etat 409, les requetes que le socle ne peut pas honorer 422 ; rien de ce que le
 * client envoie ne remonte en 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Habilitation refusee", e.getMessage());
    }

    @ExceptionHandler({KeycloakCallerFactory.MissingClaimException.class,
                       KeycloakCallerFactory.SegregationOfDutiesException.class})
    ProblemDetail badToken(RuntimeException e) {
        return problem(HttpStatus.FORBIDDEN, "Jeton insuffisant", e.getMessage());
    }

    @ExceptionHandler(WebConfiguration.MissingIdempotencyKeyException.class)
    ProblemDetail noKey(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Cle d'idempotence absente", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Requete illisible", "Le corps de la requete n'est "
                       + "pas du JSON valide pour cette operation.");
    }

    @ExceptionHandler({AccountDirectory.UnknownAccountException.class,
                       io.corebanking.api.usecase.EodUseCases.UnknownRunException.class,
                       io.corebanking.api.usecase.LoanUseCases.UnknownLoanException.class,
                       io.corebanking.deposits.Tills.UnknownTillException.class,
                       io.corebanking.api.usecase.ProductUseCases
                           .UnknownProductVersionException.class,
                       io.corebanking.security.store.PendingOperations
                           .UnknownPendingOperationException.class})
    ProblemDetail unknown(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Objet inconnu", e.getMessage());
    }

    @ExceptionHandler({AccountBlockedException.class, InsufficientFundsException.class,
                       OperationsService.AccountNotOperableException.class,
                       PartyService.PartyNotOperableException.class,
                       PartyService.DuplicatePartyException.class,
                       AccountLifecycle.ClosureRefusedException.class,
                       TfjEngine.TfjRefusedException.class, IllegalStateException.class,
                       MakerChecker.NotDecidableException.class,
                       io.corebanking.loan.service.LoanService.ArrearsOutstandingException.class,
                       io.corebanking.api.usecase.TillUseCases.NoTillException.class,
                       io.corebanking.deposits.Tills.TillClosedException.class,
                       io.corebanking.deposits.TillService.UnjustifiedDifferenceException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Operation refusee", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, LedgerViolation.class,
                       ValueDatePolicy.NoRuleException.class,
                       ProductFamily.IncompleteProductException.class,
                       io.corebanking.product.ProductFamilies.UnknownFamilyException.class,
                       io.corebanking.loan.service.LoanService.UsuryCeilingExceededException.class,
                       io.corebanking.loan.LoanTerms.InvalidLoanTermsException.class,
                       io.corebanking.loan.AmortisationSchedule.InvalidScheduleException.class})
    ProblemDetail unprocessable(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Requete non applicable", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException e) {
        LOG.error("Erreur non prevue sur une requete", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                       "L'operation n'a pas pu etre traitee ; rien n'a ete comptabilise.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:corebanking:problem:" + status.value()));
        return problem;
    }
}
