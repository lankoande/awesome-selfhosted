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
 *   <li><b>Interets courus</b> — chaque nuit, l'interet de l'echeance en cours est constate en
 *       produits au prorata des jours ecoules, en contrepartie d'un compte de courus a l'actif.
 *       Le produit d'un mois est ainsi dans le resultat de ce mois, pas dans celui de
 *       l'echeance.</li>
 *   <li><b>Exigibilite</b> — a l'echeance, les <b>charges</b> de l'echeance sont portees en
 *       creances rattachees : l'assurance, les frais et la taxe sont constates en produits ou
 *       en dettes ce jour-la, l'interet est <b>repris des courus</b>, ou il a deja ete reconnu
 *       en totalite. Le capital, lui, ne bouge pas : il est deja a l'actif depuis le deblocage,
 *       et le rendre exigible ne cree aucun flux.</li>
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
    public static final String EVENT_PREPAYMENT = "LOAN_PREPAYMENT";
    public static final String EVENT_PROVISION_CHARGE = "LOAN_PROVISION_CHARGE";
    public static final String EVENT_PROVISION_RELEASE = "LOAN_PROVISION_RELEASE";
    public static final String EVENT_INTEREST_SUSPENSION = "LOAN_INTEREST_SUSPENSION";
    public static final String EVENT_TRANCHE_RELEASE = "LOAN_TRANCHE_RELEASE";
    public static final String EVENT_INTERIM_INTEREST = "LOAN_INTERIM_INTEREST";
    public static final String EVENT_INTEREST_ACCRUAL = "LOAN_INTEREST_ACCRUAL";
    public static final String EVENT_WRITE_OFF = "LOAN_WRITE_OFF";
    public static final String EVENT_WRITE_OFF_OFF_BALANCE = "LOAN_WRITE_OFF_OFF_BALANCE";
    public static final String EVENT_RECOVERY = "LOAN_RECOVERY";
    public static final String EVENT_RECOVERY_OFF_BALANCE = "LOAN_RECOVERY_OFF_BALANCE";

    public static final String ROLE_SETTLEMENT = "settlement";
    public static final String ROLE_ACCRUED = "accrued_receivable";
    public static final String ROLE_ACCRUED_INTEREST = "accrued_interest";
    public static final String ROLE_INTEREST_INCOME = "interest_income";
    public static final String ROLE_INSURANCE_INCOME = "insurance_income";
    public static final String ROLE_FEE_INCOME = "fee_income";
    public static final String ROLE_TAX = "tax_payable";
    public static final String ROLE_LATE_INTEREST_INCOME = "late_interest_income";
    public static final String ROLE_PENALTY_INCOME = "penalty_income";
    public static final String ROLE_PROVISION_EXPENSE = "provision_expense";
    public static final String ROLE_PROVISION_ALLOWANCE = "provision_allowance";
    public static final String ROLE_PROVISION_RELEASE = "provision_release";
    public static final String ROLE_RESERVED_INTEREST = "reserved_interest";
    public static final String ROLE_PREPAYMENT_INDEMNITY = "prepayment_indemnity";
    public static final String ROLE_WRITE_OFF_LOSS = "write_off_loss";
    public static final String ROLE_RECOVERY_INCOME = "recovery_income";
    public static final String ROLE_WRITTEN_OFF = "written_off_off_balance";
    public static final String ROLE_WRITTEN_OFF_COUNTERPART = "written_off_counterpart";

    private LoanSchemas() {}

    /**
     * Deblocage : l'encours nait a l'actif pour le capital entier, mais l'emprunteur ne recoit que
     * le net des frais retenus.
     *
     * <p>C'est cette retenue qui creuse l'ecart entre taux nominal et taux effectif : les frais ne
     * sont pas rembourses par l'emprunteur, ils lui sont preleves d'entree, et il rembourse
     * pourtant le capital entier.
     */
    public static EventTemplate disbursement(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_DISBURSEMENT)
            .derive("capital", "round(principal, " + scale + ")")
            // Les frais sont plafonnes au capital dans le schema lui-meme. Le cas ou ils
            // l'absorberaient est deja refuse en amont — l'emprunteur ne recevrait rien — mais un
            // schema comptable ne doit pas pouvoir produire une ecriture desequilibree, meme
            // appele avec des valeurs qu'aucun chemin du code ne lui donne. Le controle par tirage
            // du deploiement l'exige, et il a raison : le schema survivra a l'appelant.
            .derive("frais", "min(round(upfront_fees, " + scale + "), capital)")
            .derive("net", "capital - frais")
            .line(TemplateLine.debit("CONTRACT", "capital", "Deblocage du credit"))
            .line(TemplateLine.credit("PARAM:" + ROLE_SETTLEMENT, "net",
                                      "Mise a disposition des fonds").onlyIf("net > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "frais",
                                      "Frais de dossier").onlyIf("frais > 0"))
            .build();
    }

    /**
     * Deblocage d'une tranche : l'encours augmente du montant mis a disposition, et de lui seul.
     *
     * <p>Le schema est celui du deblocage — meme ecriture, meme retenue de frais — mais l'evenement
     * est distinct. Un credit mobilise en quatre fois produit quatre ecritures de deblocage : les
     * confondre avec un deblocage unique rendrait le journal incapable de dire a quelle date chaque
     * franc a ete verse, ce qui est precisement l'information dont le taux effectif et les interets
     * intercalaires dependent.
     */
    public static EventTemplate trancheRelease(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_TRANCHE_RELEASE)
            .derive("capital", "round(amount, " + scale + ")")
            .derive("frais", "min(round(upfront_fees, " + scale + "), capital)")
            .derive("net", "capital - frais")
            .line(TemplateLine.debit("CONTRACT", "capital", "Deblocage de tranche"))
            .line(TemplateLine.credit("PARAM:" + ROLE_SETTLEMENT, "net",
                                      "Mise a disposition des fonds").onlyIf("net > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "frais",
                                      "Frais de dossier").onlyIf("frais > 0"))
            .build();
    }

    /**
     * Interets intercalaires : ce que coute le credit pendant sa mobilisation.
     *
     * <p>Ils sont constates en produits d'interets, comme les interets d'une echeance, et portes en
     * creances rattachees en attendant leur reglement. Ce ne sont pas des interets de retard : la
     * periode intercalaire est une periode normale du credit, simplement anterieure au debut de
     * l'amortissement.
     *
     * <p>Le capital ne bouge pas. Un modele qui capitaliserait les interets intercalaires dans
     * l'encours produirait des interets sur des interets, ce que le socle refuse par construction.
     */
    public static EventTemplate interimInterest(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_INTERIM_INTEREST)
            .derive("i", "round(interest, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "i + t")
            .line(TemplateLine.debit("PARAM:" + ROLE_ACCRUED, "total",
                                     "Interets intercalaires exigibles"))
            .line(TemplateLine.credit("PARAM:" + ROLE_INTEREST_INCOME, "i",
                                      "Interets de la periode de mobilisation").onlyIf("i > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe collectee").onlyIf("t > 0"))
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
            // L'interet a ete constate jour apres jour : l'echeance le reprend des courus, elle
            // ne le constate pas une seconde fois.
            .line(TemplateLine.credit("PARAM:" + ROLE_ACCRUED_INTEREST, "i",
                                      "Interets courus repris").onlyIf("i > 0"))
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

    /**
     * Dotation aux provisions : une charge, et une depreciation de l'actif.
     *
     * <p>Dotation et reprise sont deux evenements distincts plutot qu'un seul a montant signe. Le
     * moteur de schemas refuse les montants negatifs — le sens est porte par la direction, jamais
     * par le signe — et la separation rend surtout les deux flux lisibles dans le compte de
     * resultat, ou ils ne se compensent pas.
     */
    public static EventTemplate provisionCharge(CurrencyRef currency) {
        String amount = "round(amount, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_PROVISION_CHARGE)
            .derive("booked", amount)
            .line(TemplateLine.debit("PARAM:" + ROLE_PROVISION_EXPENSE, "booked",
                                     "Dotation aux provisions"))
            .line(TemplateLine.credit("PARAM:" + ROLE_PROVISION_ALLOWANCE, "booked",
                                      "Provision pour depreciation"))
            .build();
    }

    public static EventTemplate provisionRelease(CurrencyRef currency) {
        String amount = "round(amount, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_PROVISION_RELEASE)
            .derive("booked", amount)
            .line(TemplateLine.debit("PARAM:" + ROLE_PROVISION_ALLOWANCE, "booked",
                                     "Reprise de provision"))
            .line(TemplateLine.credit("PARAM:" + ROLE_PROVISION_RELEASE, "booked",
                                      "Reprise sur provisions"))
            .build();
    }

    /**
     * Suspension des interets : sortie du resultat des interets deja constates et non percus.
     *
     * <p>L'ecriture que les developpements maison omettent le plus souvent. Sans elle, la banque
     * continue de porter en produits des interets qu'elle ne percevra pas : le produit net
     * bancaire est surevalue, et la non-conformite est directe. Les interets ne disparaissent pas
     * pour autant — ils sont enregistres en <b>interets reserves</b>, hors resultat, et
     * reviendront au compte de produits s'ils sont un jour encaisses.
     *
     * <p>Chaque composante est reprise <b>sur le compte ou elle a ete constatee</b> : les interets
     * contractuels sur le produit d'interets, les interets de retard sur le produit sur creances
     * en souffrance. Les reprendre en bloc sur un seul compte creuserait un solde negatif sur
     * l'autre, et rendrait les deux lignes de l'etat reglementaire fausses en sens contraire.
     */
    public static EventTemplate interestSuspension(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_INTEREST_SUSPENSION)
            .derive("i", "round(interest, " + scale + ")")
            .derive("l", "round(late_interest, " + scale + ")")
            .derive("total", "i + l")
            .line(TemplateLine.debit("PARAM:" + ROLE_INTEREST_INCOME, "i",
                                     "Interets contractuels sortis du resultat").onlyIf("i > 0"))
            .line(TemplateLine.debit("PARAM:" + ROLE_LATE_INTEREST_INCOME, "l",
                                     "Interets de retard sortis du resultat").onlyIf("l > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_RESERVED_INTEREST, "total",
                                      "Interets reserves"))
            .build();
    }

    /**
     * Remboursement anticipe : le capital sort de l'encours, l'indemnite entre en produits.
     *
     * <p>L'indemnite est creditee sur un compte distinct des interets. Ce n'est pas un interet —
     * elle ne remunere aucune duree — et la confondre avec un produit d'interets fausserait a la
     * fois la marge d'interet et le taux de rendement du portefeuille.
     */
    public static EventTemplate prepayment(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_PREPAYMENT)
            .derive("capital", "round(principal, " + scale + ")")
            .derive("indemnite", "round(indemnity, " + scale + ")")
            .derive("total", "capital + indemnite")
            .line(TemplateLine.debit("PARAM:" + ROLE_SETTLEMENT, "total",
                                     "Remboursement anticipe"))
            .line(TemplateLine.credit("CONTRACT", "capital", "Capital rembourse par anticipation")
                      .onlyIf("capital > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_PREPAYMENT_INDEMNITY, "indemnite",
                                      "Indemnite de remboursement anticipe")
                      .onlyIf("indemnite > 0"))
            .build();
    }

    public static AccountingSchema standard(CurrencyRef currency) {
        return AccountingSchema.of(STANDARD_CODE, 1)
            .on(disbursement(currency))
            .on(instalmentDue(currency))
            .on(repayment(currency))
            .on(lateCharges(currency))
            .on(provisionCharge(currency))
            .on(provisionRelease(currency))
            .on(interestSuspension(currency))
            .on(prepayment(currency))
            .on(trancheRelease(currency))
            .on(interimInterest(currency))
            .build();
    }

    /**
     * Passage en perte : ce qui sort de l'actif, et ce qui l'absorbe.
     *
     * <p>L'ordre d'absorption n'est pas une commodite. Les <b>interets reserves</b> viennent en
     * premier sur la part d'interets : ces produits ont deja ete sortis du resultat a la
     * suspension, et les passer en perte une seconde fois constaterait une charge pour un produit
     * jamais pris. Vient ensuite la <b>provision</b> constituee, qui est faite pour cela. Le
     * reliquat seul est une perte. Si provision et reserves depassent ce qui sort — un dossier
     * sur-provisionne —, l'excedent est repris en produit : il n'a plus d'objet.
     */
    public static EventTemplate writeOff(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(EVENT_WRITE_OFF)
            .derive("capital", "round(principal, " + scale + ")")
            .derive("creances", "round(receivables, " + scale + ")")
            .derive("reserves", "round(reserved, " + scale + ")")
            .derive("provision", "round(provision, " + scale + ")")
            .derive("perte", "round(loss, " + scale + ")")
            .derive("reprise", "round(released, " + scale + ")")
            .line(TemplateLine.credit("CONTRACT", "capital", "Capital sorti de l'actif")
                      .onlyIf("capital > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_ACCRUED, "creances",
                                      "Creances sorties de l'actif").onlyIf("creances > 0"))
            .line(TemplateLine.debit("PARAM:" + ROLE_RESERVED_INTEREST, "reserves",
                                     "Interets reserves imputes").onlyIf("reserves > 0"))
            // La provision disparait en entier : la part utilisee absorbe la sortie, celle qui
            // reste n'a plus d'objet et revient au resultat. Ne debiter que la part utilisee
            // laisserait au bilan une provision sans creance, et desequilibrerait l'ecriture.
            .derive("provision_totale", "provision + reprise")
            .line(TemplateLine.debit("PARAM:" + ROLE_PROVISION_ALLOWANCE, "provision_totale",
                                     "Provision utilisee et reprise")
                      .onlyIf("provision_totale > 0"))
            .line(TemplateLine.debit("PARAM:" + ROLE_WRITE_OFF_LOSS, "perte",
                                     "Perte sur creance irrecouvrable").onlyIf("perte > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_PROVISION_RELEASE, "reprise",
                                      "Provision sans objet reprise").onlyIf("reprise > 0"))
            .build();
    }

    /** La creance sortie de l'actif entre au hors bilan : elle reste due. */
    public static EventTemplate writeOffOffBalance(CurrencyRef currency) {
        String amount = "round(amount, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_WRITE_OFF_OFF_BALANCE)
            .derive("engagement", amount)
            .line(TemplateLine.debit("PARAM:" + ROLE_WRITTEN_OFF, "engagement",
                                     "Creance passee en perte, toujours due"))
            .line(TemplateLine.credit("PARAM:" + ROLE_WRITTEN_OFF_COUNTERPART, "engagement",
                                      "Contrepartie du hors bilan"))
            .build();
    }

    /**
     * Recouvrement apres perte : un produit, jamais un remboursement.
     *
     * <p>Il n'y a plus de creance a l'actif a diminuer — l'imputer sur un encours ferait
     * reapparaitre un credit solde et rendrait le capital negatif.
     */
    public static EventTemplate recovery(CurrencyRef currency) {
        String amount = "round(amount, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_RECOVERY)
            .derive("encaisse", amount)
            .line(TemplateLine.debit("PARAM:" + ROLE_SETTLEMENT, "encaisse",
                                     "Encaissement sur creance amortie"))
            .line(TemplateLine.credit("PARAM:" + ROLE_RECOVERY_INCOME, "encaisse",
                                      "Recuperation sur creance passee en perte"))
            .build();
    }

    /** Ce qui est recouvre sort du hors bilan : il n'est plus du. */
    public static EventTemplate recoveryOffBalance(CurrencyRef currency) {
        String amount = "round(amount, " + currency.scale() + ")";
        return EventTemplate.of(EVENT_RECOVERY_OFF_BALANCE)
            .derive("engagement", amount)
            .line(TemplateLine.debit("PARAM:" + ROLE_WRITTEN_OFF_COUNTERPART, "engagement",
                                     "Contrepartie du hors bilan"))
            .line(TemplateLine.credit("PARAM:" + ROLE_WRITTEN_OFF, "engagement",
                                      "Creance recouvree, sortie du hors bilan"))
            .build();
    }
}
