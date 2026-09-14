package io.corebanking.loan.service;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;

/**
 * Schemas comptables du credit.
 *
 * <h2>Le modele d'imputation, en trois evenements</h2>
 *
 * <ol>
 *   <li><b>Deblocage</b> — le compte de pret est debite du capital, le compte du client credite.
 *       L'encours nait a l'actif, la contrepartie est mise a disposition.</li>
 *   <li><b>Exigibilite</b> — a l'echeance, les <b>charges</b> de l'echeance sont constatees en
 *       produits et portees en creances rattachees. Le capital, lui, ne bouge pas : il est deja a
 *       l'actif depuis le deblocage, et le rendre exigible ne cree aucun flux.</li>
 *   <li><b>Reglement</b> — le compte du client est debite du montant regle ; la part de capital
 *       vient en diminution de l'encours, la part de charges solde les creances rattachees.</li>
 * </ol>
 *
 * <p>La consequence a retenir : <b>l'encours ne diminue qu'au reglement</b>, jamais a l'echeance.
 * Un modele qui amortirait l'encours des l'exigibilite afficherait un actif inferieur a ce que le
 * client doit reellement, et sous-estimerait l'exposition au moment ou elle devient risquee.
 *
 * <p>Comme pour les commissions, chaque composante est <b>arrondie pour elle-meme</b> et le total
 * est leur somme. Debiter un total arrondi puis en deduire une composante par difference
 * desequilibrerait l'ecriture des que deux composantes ont des decimales, et rendrait la taxe
 * declaree irreconciliable avec l'interet porte au compte de produit.
 */
public final class LoanSchemas {

    public static final String STANDARD_CODE = "LOAN_STANDARD";

    public static final String EVENT_DISBURSEMENT = "LOAN_DISBURSEMENT";
    public static final String EVENT_INSTALMENT_DUE = "LOAN_INSTALMENT_DUE";
    public static final String EVENT_REPAYMENT = "LOAN_REPAYMENT";
    public static final String EVENT_LATE_CHARGES = "LOAN_LATE_CHARGES";

    public static final String ROLE_SETTLEMENT = "settlement";
    public static final String ROLE_ACCRUED = "accrued_receivable";
    public static final String ROLE_INTEREST_INCOME = "interest_income";
    public static final String ROLE_INSURANCE_INCOME = "insurance_income";
    public static final String ROLE_FEE_INCOME = "fee_income";
    public static final String ROLE_TAX = "tax_payable";
    public static final String ROLE_LATE_INTEREST_INCOME = "late_interest_income";
    public static final String ROLE_PENALTY_INCOME = "penalty_income";

    private LoanSchemas() {}

    public static EventTemplate disbursement(CurrencyRef currency) {
        String amount = "round(principal, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_DISBURSEMENT)
            .derive("amount", amount)
            .line(TemplateLine.debit("CONTRACT", "amount", "Deblocage du credit"))
            .line(TemplateLine.credit("PARAM:" + ROLE_SETTLEMENT, "amount",
                                      "Mise a disposition des fonds"))
            .build();
    }

    public static EventTemplate instalmentDue(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_INSTALMENT_DUE)
            .derive("i", "round(interest, " + scale + ")")
            .derive("s", "round(insurance, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("charges", "i + s + f + t")
            .line(TemplateLine.debit("PARAM:" + ROLE_ACCRUED, "charges", "Echeance exigible"))
            .line(TemplateLine.credit("PARAM:" + ROLE_INTEREST_INCOME, "i", "Interets")
                      .onlyIf("i > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_INSURANCE_INCOME, "s", "Assurance")
                      .onlyIf("s > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de dossier")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe collectee").onlyIf("t > 0"))
            .build();
    }

    public static EventTemplate repayment(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_REPAYMENT)
            .derive("p", "round(principal, " + scale + ")")
            .derive("c", "round(charges, " + scale + ")")
            .derive("total", "p + c")
            .line(TemplateLine.debit("PARAM:" + ROLE_SETTLEMENT, "total", "Reglement d'echeance"))
            .line(TemplateLine.credit("CONTRACT", "p", "Amortissement du capital").onlyIf("p > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_ACCRUED, "c", "Charges reglees")
                      .onlyIf("c > 0"))
            .build();
    }

    /**
     * Interets de retard et penalites.
     *
     * <p>Les deux produits sont credites sur des comptes distincts de ceux des interets
     * contractuels. Ce n'est pas une commodite de restitution : les produits sur creances en
     * souffrance forment une ligne a part des etats reglementaires, et les melanger aux interets
     * sains rend la declaration impossible a reconstituer.
     */
    public static EventTemplate lateCharges(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_LATE_CHARGES)
            .derive("li", "round(late_interest, " + scale + ")")
            .derive("pen", "round(penalty, " + scale + ")")
            .derive("total", "li + pen")
            .line(TemplateLine.debit("PARAM:" + ROLE_ACCRUED, "total", "Charges de retard"))
            .line(TemplateLine.credit("PARAM:" + ROLE_LATE_INTEREST_INCOME, "li",
                                      "Interets de retard").onlyIf("li > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_PENALTY_INCOME, "pen", "Penalites")
                      .onlyIf("pen > 0"))
            .build();
    }

    public static AccountingSchema standard(CurrencyRef currency) {
        return AccountingSchema.of(STANDARD_CODE, 1)
            .on(disbursement(currency))
            .on(instalmentDue(currency))
            .on(repayment(currency))
            .on(lateCharges(currency))
            .build();
    }
}
