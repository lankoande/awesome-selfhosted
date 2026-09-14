package io.corebanking.loan;

/** Plan de deblocage refuse. */
public class InvalidDisbursementPlanException extends RuntimeException {

    public InvalidDisbursementPlanException(String detail) {
        super("Plan de deblocage : " + detail + ".");
    }
}
