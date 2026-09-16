package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.money.Currencies;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le catalogue des operations couvre le perimetre des services.
 *
 * <p>Les points d'entree qui changent l'etat de la banque sont inventories ici, avec l'operation
 * qui les protege. L'inventaire est tenu a la main tant que les cas d'usage n'existent pas : ce
 * sont eux, avec {@link UseCase#operation()}, qui rendront ce rattachement mecanique. D'ici la,
 * une operation que personne ne reclame et un point d'entree que rien ne protege se voient ici.
 */
class OperationCoverageTest {

    /** Points d'entree de service → operation. Un point d'entree absent est un point ouvert. */
    private static final Map<String, Operation> ENTRY_POINTS = Map.ofEntries(
        Map.entry("OperationsService.deposit / withdraw", Operation.CASH_OPERATION),
        Map.entry("OperationsService.transfer", Operation.TRANSFER),
        Map.entry("JdbcPostingService.post (ordre divers)", Operation.JOURNAL_ENTRY_MANUAL),
        Map.entry("JdbcPostingService.reverse", Operation.ENTRY_REVERSAL),
        Map.entry("Holds.place / release", Operation.ACCOUNT_HOLD),
        Map.entry("AccountLifecycle.block / unblock", Operation.ACCOUNT_BLOCK),
        Map.entry("PartyService.create / block / unblock", Operation.PARTY_CREATE),
        Map.entry("PartyService.verifyKyc", Operation.KYC_VERIFY),
        Map.entry("AccountLifecycle.open", Operation.ACCOUNT_OPEN),
        Map.entry("AccountLifecycle.close", Operation.ACCOUNT_CLOSE),
        Map.entry("ProductCatalog.assignProduct", Operation.ACCOUNT_PRODUCT_ASSIGN),
        Map.entry("ProductCatalog.createDraft", Operation.PRODUCT_DRAFT),
        Map.entry("ProductCatalog.activate", Operation.PRODUCT_ACTIVATE),
        Map.entry("SchemaCatalog.createDraft", Operation.ACCOUNTING_SCHEMA_DRAFT),
        Map.entry("SchemaCatalog.activate", Operation.ACCOUNTING_SCHEMA_ACTIVATE),
        Map.entry("StatementLayouts.createDraft", Operation.STATEMENT_LAYOUT_DRAFT),
        Map.entry("StatementLayouts.activate", Operation.STATEMENT_LAYOUT_ACTIVATE),
        Map.entry("Calendars.createCalendar / addHoliday / attachToEntity / addRule",
                  Operation.CALENDAR_MANAGE),
        Map.entry("Branches.create", Operation.BRANCH_MANAGE),
        Map.entry("PaymentService.order", Operation.PAYMENT_ORDER),
        Map.entry("PaymentService.settle", Operation.PAYMENT_PROCESS),
        Map.entry("Limits.set", Operation.ACCOUNT_LIMIT_MANAGE),
        Map.entry("ChequeService.issueBook", Operation.CHEQUE_BOOK_ISSUE),
        Map.entry("ChequeService.pay", Operation.CHEQUE_PAY),
        Map.entry("ChequeService.deposit", Operation.CHEQUE_DEPOSIT),
        Map.entry("ChequeService.settleDeposit", Operation.CHEQUE_PROCESS),
        Map.entry("ChequeService.stop", Operation.CHEQUE_STOP),
        Map.entry("DirectDebitService.registerMandate", Operation.MANDATE_REGISTER),
        Map.entry("DirectDebitService.revokeMandate", Operation.MANDATE_REVOKE),
        Map.entry("DirectDebitService.present", Operation.DIRECT_DEBIT_PRESENT),
        Map.entry("DirectDebitService.issue", Operation.DIRECT_DEBIT_ISSUE),
        Map.entry("DirectDebitService.settle / cancel / refund / returnIssued",
                  Operation.DIRECT_DEBIT_PROCESS),
        Map.entry("Suspense.setPolicy", Operation.SUSPENSE_MANAGE),
        Map.entry("PartyDocuments.deposit", Operation.PARTY_DOCUMENT),
        Map.entry("Relationships.declare / BeneficialOwners.declare",
                  Operation.PARTY_RELATIONSHIP),
        Map.entry("KycPolicies.declare", Operation.KYC_POLICY_MANAGE),
        Map.entry("LoanOrigination.submit / assess / addCondition", Operation.LOAN_APPLICATION),
        Map.entry("LoanOrigination.decide", Operation.LOAN_APPLICATION_DECIDE),
        Map.entry("LoanOrigination.clearCondition", Operation.LOAN_CONDITION_CLEAR),
        Map.entry("LendingPolicies.declare", Operation.LENDING_POLICY_MANAGE),
        Map.entry("LoanWriteOffService.writeOff", Operation.LOAN_WRITE_OFF),
        Map.entry("LoanWriteOffService.recover", Operation.LOAN_RECOVERY),
        Map.entry("LoanService.reviseRate", Operation.LOAN_RATE_REVISION),
        Map.entry("FxRates.quote", Operation.FX_RATE_QUOTE),
        Map.entry("FxPositions.declare", Operation.FX_POSITION_MANAGE),
        Map.entry("Tills.create", Operation.TILL_MANAGE),
        Map.entry("TillService.close", Operation.TILL_CLOSE),
        Map.entry("FeeLedger.grantExemption", Operation.FEE_EXEMPTION_GRANT),
        Map.entry("LoanStore.createContract / assignCustomer", Operation.LOAN_CONTRACT_CREATE),
        Map.entry("LoanService.disburse", Operation.LOAN_DISBURSE),
        Map.entry("LoanMobilisationService.open / release", Operation.LOAN_DISBURSE),
        Map.entry("LoanService.reschedule", Operation.LOAN_RESCHEDULE),
        Map.entry("LoanService.prepay", Operation.LOAN_PREPAY),
        Map.entry("LoanService.settle", Operation.LOAN_REPAYMENT),
        Map.entry("Collaterals.register / allocate / release", Operation.COLLATERAL_MANAGE),
        Map.entry("Collaterals.createPolicy / RiskProfiles.createDraft",
                  Operation.RISK_PARAMETER_DRAFT),
        Map.entry("Collaterals.activatePolicy / RiskProfiles.activate",
                  Operation.RISK_PARAMETER_ACTIVATE),
        Map.entry("TfjEngine.run / resume", Operation.TFJ_RUN),
        Map.entry("TfjEngine.cancel", Operation.TFJ_CANCEL),
        Map.entry("WithholdingTaxes.declare", Operation.TAX_PARAMETER_DECLARE),
        Map.entry("StandardTfm / PeriodCloseStep", Operation.PERIOD_CLOSE),
        Map.entry("TfjEngine.cancel (TFM)", Operation.PERIOD_REOPEN),
        Map.entry("FiscalYears.open", Operation.FISCAL_YEAR_MANAGE),
        Map.entry("StandardTfa", Operation.YEAR_CLOSE),
        Map.entry("TfjEngine.cancel (TFA)", Operation.YEAR_REOPEN),
        Map.entry("FiscalYears.appropriate", Operation.RESULT_APPROPRIATION));

    /** Operations sans point d'entree de service : consultations, ou pas encore construites. */
    private static final Set<Operation> WITHOUT_SERVICE = EnumSet.of(
        Operation.ACCOUNT_BALANCE_READ, Operation.ACCOUNT_JOURNAL_READ, Operation.LEDGER_READ,
        Operation.PARTY_READ, Operation.LOAN_READ, Operation.PAYMENT_READ, Operation.CHEQUE_READ,
        Operation.DIRECT_DEBIT_READ, Operation.SUSPENSE_READ, Operation.FX_READ,
        Operation.PARTY_FILE_READ, Operation.AUDIT_READ);

    @Test
    @DisplayName("toute operation est reclamee par un point d'entree, ou est une consultation")
    void every_operation_is_claimed() {
        Set<Operation> claimed = EnumSet.copyOf(ENTRY_POINTS.values());
        claimed.addAll(WITHOUT_SERVICE);
        Set<Operation> dead = EnumSet.allOf(Operation.class);
        dead.removeAll(claimed);
        // Une operation que rien ne reclame fait croire a une protection qui ne s'exerce nulle part.
        assertThat(dead).isEmpty();
    }

    @Test
    @DisplayName("tout ce qui fait sortir de l'argent ou modifie une dette se valide a deux")
    void money_and_debt_require_a_second_person() {
        for (Operation operation : EnumSet.of(Operation.LOAN_DISBURSE, Operation.LOAN_RESCHEDULE,
                                              Operation.COLLATERAL_MANAGE,
                                              Operation.FEE_EXEMPTION_GRANT,
                                              Operation.JOURNAL_ENTRY_MANUAL,
                                              Operation.ACCOUNT_PRODUCT_ASSIGN,
                                              Operation.PERIOD_CLOSE,
                                              Operation.KYC_VERIFY, Operation.ACCOUNT_OPEN,
                                              Operation.ACCOUNT_CLOSE, Operation.ACCOUNT_BLOCK,
                                              Operation.ACCOUNT_HOLD, Operation.LOAN_PREPAY,
                                              Operation.BRANCH_MANAGE, Operation.TILL_MANAGE,
                                              Operation.FISCAL_YEAR_MANAGE, Operation.YEAR_CLOSE,
                                              Operation.YEAR_REOPEN,
                                              Operation.RESULT_APPROPRIATION,
                                              Operation.STATEMENT_LAYOUT_ACTIVATE,
                                              Operation.ACCOUNT_LIMIT_MANAGE,
                                              Operation.CHEQUE_BOOK_ISSUE,
                                              Operation.MANDATE_REGISTER,
                                              Operation.SUSPENSE_MANAGE,
                                              Operation.PARTY_RELATIONSHIP,
                                              Operation.KYC_POLICY_MANAGE,
                                              Operation.LOAN_APPLICATION_DECIDE,
                                              Operation.LOAN_CONDITION_CLEAR,
                                              Operation.LENDING_POLICY_MANAGE,
                                              Operation.LOAN_WRITE_OFF,
                                              Operation.LOAN_RATE_REVISION,
                                              Operation.FX_RATE_QUOTE,
                                              Operation.FX_POSITION_MANAGE)) {
            assertThat(SecurityConfig.ruleFor(operation).dualControl())
                .as(operation.name()).isTrue();
        }
    }

    @Test
    @DisplayName("une regle plafonnee donne un plafond a chacun de ses roles, dans la devise de reference")
    void every_ceilinged_rule_covers_all_its_roles() {
        // Un role autorise mais sans plafond serait refuse sur tout montant — « aucun plafond
        // defini » — sans que la matrice ne le dise. L'oubli est silencieux, le test ne l'est pas.
        for (Map.Entry<Operation, AccessRule> entry : SecurityConfig.policy().entrySet()) {
            AccessRule rule = entry.getValue();
            if (rule.ceilings().isEmpty()) {
                continue;
            }
            assertThat(new TreeSet<>(rule.ceilings().keySet()))
                .as(entry.getKey().name())
                .containsExactlyInAnyOrderElementsOf(rule.roles());
            rule.ceilings().values().forEach(ceiling ->
                assertThat(ceiling.currency()).isEqualTo(Currencies.XOF));
            // Un plafond deplace ne vise que des roles de la regle, et ne depasse jamais
            // l'ordinaire : deplace veut dire plus prudent, pas plus large.
            rule.remoteCeilings().forEach((role, ceiling) -> {
                assertThat(rule.roles()).as(entry.getKey().name()).contains(role);
                assertThat(ceiling.currency()).isEqualTo(Currencies.XOF);
                assertThat(ceiling.isGreaterThan(rule.ceilings().get(role))).isFalse();
            });
        }
    }

    @Test
    @DisplayName("les roles de credit existent au catalogue et sont portes par un poste")
    void credit_roles_are_carried() {
        assertThat(RoleCatalogue.declared()).contains(Roles.CREDIT_OFFICER, Roles.CREDIT_MANAGER);
        assertThat(JobProfile.CHARGE_CREDIT.roles()).containsExactly(Roles.CREDIT_OFFICER);
        assertThat(JobProfile.RESPONSABLE_ENGAGEMENTS.roles())
            .containsExactly(Roles.CREDIT_MANAGER);
    }
}
