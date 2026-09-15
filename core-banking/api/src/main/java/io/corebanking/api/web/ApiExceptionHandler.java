package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.EodUseCases;
import io.corebanking.api.usecase.LoanUseCases;
import io.corebanking.api.usecase.Paging;
import io.corebanking.api.usecase.ProductUseCases;
import io.corebanking.api.usecase.TillUseCases;
import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.OperationsService;
import io.corebanking.deposits.TillService;
import io.corebanking.deposits.Tills;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.error.LedgerViolation;
import io.corebanking.ledger.store.AccountBlockedException;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.service.LoanService;
import io.corebanking.party.PartyService;
import io.corebanking.product.ProductFamilies;
import io.corebanking.product.ProductFamily;
import io.corebanking.security.AccessDeniedException;
import io.corebanking.security.KeycloakCallerFactory;
import io.corebanking.security.store.PendingOperations;
import io.corebanking.tfj.TfjEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Un refus est une reponse, pas une trace de pile : chaque exception du socle a son statut et son
 * message, dans la meme enveloppe que les succes ({@link ApiResponse}), avec les champs de RFC
 * 9457. Les refus d'habilitation sont 403, les conflits d'etat 409, les requetes que le socle ne
 * peut pas honorer 422 ; rien de ce que le client envoie ne remonte en 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "Habilitation refusee", e.getMessage(), request);
    }

    @ExceptionHandler({KeycloakCallerFactory.MissingClaimException.class,
                       KeycloakCallerFactory.SegregationOfDutiesException.class})
    ResponseEntity<ApiResponse<Void>> token(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "Jeton insuffisant", e.getMessage(), request);
    }

    @ExceptionHandler(WebConfiguration.MissingIdempotencyKeyException.class)
    ResponseEntity<ApiResponse<Void>> idempotency(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Cle d'idempotence absente", e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e,
                                                 HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Requete illisible",
                       "Le corps de la requete n'est pas un JSON conforme au contrat.", request);
    }

    @ExceptionHandler({Paging.InvalidPageException.class,
                       MethodArgumentTypeMismatchException.class,
                       MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> badRequest(Exception e, HttpServletRequest request) {
        String detail = e instanceof MethodArgumentTypeMismatchException mismatch
            ? "Parametre " + mismatch.getName() + " invalide : " + mismatch.getValue()
            : e.getMessage();
        return respond(HttpStatus.BAD_REQUEST, "Requete invalide", detail, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> noRoute(NoResourceFoundException e,
                                              HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "Chemin inconnu",
                       "Aucune ressource a l'adresse " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> method(HttpRequestMethodNotSupportedException e,
                                             HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "Methode non admise", e.getMessage(),
                       request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> mediaType(HttpMediaTypeNotSupportedException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Type de contenu non admis",
                       "L'API lit et ecrit application/json.", request);
    }

    @ExceptionHandler({AccountDirectory.UnknownAccountException.class,
                       EodUseCases.UnknownRunException.class,
                       LoanUseCases.UnknownLoanException.class,
                       Tills.UnknownTillException.class,
                       ProductUseCases.UnknownProductVersionException.class,
                       PendingOperations.UnknownPendingOperationException.class})
    ResponseEntity<ApiResponse<Void>> unknown(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "Objet inconnu", e.getMessage(), request);
    }

    @ExceptionHandler({AccountBlockedException.class, InsufficientFundsException.class,
                       OperationsService.AccountNotOperableException.class,
                       PartyService.PartyNotOperableException.class,
                       PartyService.DuplicatePartyException.class,
                       AccountLifecycle.ClosureRefusedException.class,
                       TfjEngine.TfjRefusedException.class, IllegalStateException.class,
                       MakerChecker.NotDecidableException.class,
                       LoanService.ArrearsOutstandingException.class,
                       TillUseCases.NoTillException.class,
                       Tills.TillClosedException.class,
                       TillService.UnjustifiedDifferenceException.class})
    ResponseEntity<ApiResponse<Void>> conflict(RuntimeException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "Operation refusee", e.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, LedgerViolation.class,
                       ValueDatePolicy.NoRuleException.class,
                       ProductFamily.IncompleteProductException.class,
                       ProductFamilies.UnknownFamilyException.class,
                       LoanService.UsuryCeilingExceededException.class,
                       LoanTerms.InvalidLoanTermsException.class,
                       AmortisationSchedule.InvalidScheduleException.class})
    ResponseEntity<ApiResponse<Void>> unprocessable(RuntimeException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "Requete non applicable", e.getMessage(),
                       request);
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiResponse<Void>> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("Erreur non prevue sur la requete {} {} [{}]", request.getMethod(),
                  request.getRequestURI(), RequestIds.of(request), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                       "L'operation n'a pas pu etre traitee ; rien n'a ete comptabilise.", request);
    }

    static ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String title, String detail,
                                                     HttpServletRequest request) {
        String requestId = RequestIds.of(request);
        return ResponseEntity.status(status)
            .header(RequestIds.HEADER, requestId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ApiResponse.error(status.value(), title, detail, request.getRequestURI(),
                                    requestId));
    }
}
