package io.corebanking.fee.service;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;

/**
 * Schema comptable standard de perception d'une commission.
 *
 * <h2>Pourquoi le schema arrondit lui-meme</h2>
 *
 * <p>Le moteur de schemas refuse d'imputer un montant non arrondi plutot que de l'arrondir a
 * l'insu de l'auteur. Le schema doit donc porter ses arrondis, et il les porte <b>ligne par
 * ligne</b> : le net et la taxe sont arrondis chacun pour son compte, et le total debite est leur
 * somme.
 *
 * <p>L'ecriture symetrique — debiter {@code net + taxe} puis arrondir les trois lignes — est
 * desequilibree des que les deux composantes ont des decimales : arrondir 0,5 et 0,5 donne 0 et 0
 * au pair, tandis que leur somme 1,0 s'impute a 1. Le controle par tirage du deploiement le
 * refuse, et c'est bien un francs d'ecart par operation qu'il evite.
 *
 * <h2>Ce que le schema laisse au parametrage</h2>
 *
 * <p>Les comptes de produit et de taxe sont designes par role, pas par identifiant : le meme
 * schema sert une filiale ivoirienne et une filiale senegalaise, dont les plans comptables
 * different. Une banque qui veut une ventilation plus fine — produit par canal, taxe par
 * territoire — enregistre son propre schema sous un autre code et le nomme dans le produit.
 */
public final class FeeSchemas {

    /** Code du schema standard, employe lorsque le produit n'en designe pas d'autre. */
    public static final String STANDARD_CODE = "FEE_STANDARD";

    /** Type d'evenement traduit par le schema. */
    public static final String EVENT_FEE_CHARGE = "FEE_CHARGE";

    /** Role du compte de produit de commission dans le schema. */
    public static final String ROLE_INCOME = "fee_income";

    /** Role du compte de taxe collectee dans le schema. */
    public static final String ROLE_TAX = "fee_tax";

    private FeeSchemas() {}

    /**
     * Le seul schema que le parametrage peut aujourd'hui remplacer.
     *
     * <p>{@link io.corebanking.schema.AccountingSchema} sert deux usages qu'il faut distinguer :
     * les schemas du guichet et du credit sont <b>construits en code</b> et se lisent ; celui de
     * la commission est le seul que {@code SchemaCatalog.resolveAt} resout, lorsque la commission
     * nomme un code autre que {@link #STANDARD_CODE}. Un schema redige sous un autre code ne
     * serait lu par personne.
     */
    public static java.util.Map<String, EventTemplate> all(CurrencyRef currency) {
        return java.util.Map.of(EVENT_FEE_CHARGE, feeCharge(currency));
    }

    /**
     * Schema standard pour une devise. L'echelle d'arrondi est celle de la devise : le meme
     * schema ne peut pas servir en XOF et en EUR, et c'est pourquoi il est construit par devise
     * plutot qu'ecrit une fois pour toutes.
     */
    public static EventTemplate feeCharge(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_FEE_CHARGE)
            .derive("net_booked", "round(net, " + scale + ")")
            .derive("tax_booked", "round(tax, " + scale + ")")
            .derive("total", "net_booked + tax_booked")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:" + ROLE_INCOME, "net_booked",
                                      "Produit de commission"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "tax_booked", "Taxe collectee")
                      .onlyIf("tax_booked > 0"))
            .build();
    }

    public static AccountingSchema standard(CurrencyRef currency) {
        return AccountingSchema.of(STANDARD_CODE, 1).on(feeCharge(currency)).build();
    }
}
