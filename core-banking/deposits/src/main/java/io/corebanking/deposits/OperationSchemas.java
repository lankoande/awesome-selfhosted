package io.corebanking.deposits;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;

/**
 * Schemas comptables des operations de guichet et de virement.
 *
 * <p>Le montant, le frais et la taxe sont arrondis chacun pour soi et le total debite est leur
 * somme : debiter un total arrondi puis en deduire une composante par difference desequilibrerait
 * l'ecriture des que deux composantes ont des decimales.
 */
public final class OperationSchemas {

    public static final String CASH_DEPOSIT = "CASH_DEPOSIT";
    public static final String CASH_WITHDRAWAL = "CASH_WITHDRAWAL";
    public static final String TRANSFER = "TRANSFER";

    public static final String ROLE_CASH = "cash";
    public static final String ROLE_DESTINATION = "destination";
    public static final String ROLE_FEE_INCOME = "fee_income";
    public static final String ROLE_TAX = "tax";

    private OperationSchemas() {}

    /** Versement : la caisse entre, le compte du client est credite. */
    public static EventTemplate cashDeposit(CurrencyRef currency) {
        return EventTemplate.of(CASH_DEPOSIT)
            .derive("amt", "round(amount, " + currency.scale() + ")")
            .line(TemplateLine.debit("PARAM:" + ROLE_CASH, "amt", "Versement especes"))
            .line(TemplateLine.credit("CONTRACT", "amt", "Versement especes"))
            .build();
    }

    /** Retrait : le client est debite du montant, du frais et de la taxe ; la caisse sort le montant. */
    public static EventTemplate cashWithdrawal(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(CASH_WITHDRAWAL)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Retrait especes"))
            .line(TemplateLine.credit("PARAM:" + ROLE_CASH, "amt", "Retrait especes"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de retrait")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /** Virement interne : le donneur d'ordre paie le montant, le frais et la taxe. */
    public static EventTemplate transfer(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(TRANSFER)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Virement emis"))
            .line(TemplateLine.credit("PARAM:" + ROLE_DESTINATION, "amt", "Virement recu"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de virement")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }
}
