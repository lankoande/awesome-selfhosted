package io.corebanking.loan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Grille de declassement et de provisionnement d'un profil reglementaire.
 *
 * <h2>Ce que la grille refuse, et pourquoi</h2>
 *
 * <p>Une grille de declassement est le parametrage le plus lourd de consequences d'un core
 * banking : elle decide du niveau de provision de tout un portefeuille, donc du resultat publie et
 * du ratio de solvabilite. Quatre defauts y sont possibles, tous silencieux :
 *
 * <ul>
 *   <li><b>un trou entre deux classes</b> — un credit a quatre-vingt-onze jours de retard ne serait
 *       classe nulle part, et le traitement echouerait sur ce credit-la seulement, un soir
 *       d'arrete ;</li>
 *   <li><b>un chevauchement</b> — deux classes couvrant le meme retard rendraient le classement
 *       dependant de l'ordre de lecture, et l'arrete ne serait pas reproductible ;</li>
 *   <li><b>un taux de provision qui decroit</b> avec la degradation — une classe pire provisionnant
 *       moins qu'une meilleure, ce qui n'a aucun sens prudentiel et se lit comme une inversion de
 *       deux lignes dans un tableur ;</li>
 *   <li><b>une classe saine apres une classe douteuse</b> — la distinction sain / en souffrance
 *       commande la suspension des interets et la declaration a la centrale des risques ; elle ne
 *       peut pas alterner.</li>
 * </ul>
 *
 * <p>Les quatre sont refuses ici, au chargement du parametrage.
 *
 * @param suspendFromBucket code de la classe a partir de laquelle les interets cessent d'etre
 *                          constates en produits ; nul si le profil ne suspend jamais
 * @param cureDays          periode d'observation avant qu'un credit regularise ne redevienne sain.
 *                          Sans elle, un debiteur qui regle la veille de l'arrete efface son
 *                          declassement et la provision qui l'accompagne, puis retombe en impaye
 *                          le lendemain : le portefeuille parait sain a chaque arrete et ne l'est
 *                          jamais.
 */
public record RiskGrid(
    String code,
    List<RiskBucket> buckets,
    Contagion contagion,
    String suspendFromBucket,
    int cureDays) {

    /** Grille sans periode d'observation : le retour a meilleure fortune est immediat. */
    public RiskGrid(String code, List<RiskBucket> buckets, Contagion contagion,
                    String suspendFromBucket) {
        this(code, buckets, contagion, suspendFromBucket, 0);
    }

    public RiskGrid {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(contagion, "contagion");
        buckets = List.copyOf(Objects.requireNonNull(buckets, "buckets"));
        require(!buckets.isEmpty(), code, "aucune classe de risque");

        BigDecimal previousRate = null;
        boolean seenNonPerforming = false;
        for (int index = 0; index < buckets.size(); index++) {
            RiskBucket bucket = buckets.get(index);
            require(bucket.ordinal() == index, code,
                    "la classe " + bucket.code() + " porte le rang " + bucket.ordinal()
                    + " en position " + index);
            if (index == 0) {
                require(bucket.fromDays() == 0, code,
                        "la premiere classe s'ouvre a " + bucket.fromDays()
                        + " jours : un credit a jour ne serait classe nulle part");
            } else {
                RiskBucket previous = buckets.get(index - 1);
                require(previous.toDays() != null, code,
                        "la classe " + previous.code() + " est sans borne haute alors qu'elle est "
                        + "suivie de " + bucket.code());
                require(bucket.fromDays() == previous.toDays() + 1, code,
                        "entre " + previous.code() + " et " + bucket.code() + ", les retards de "
                        + (previous.toDays() + 1) + " a " + (bucket.fromDays() - 1)
                        + " jours ne sont couverts par aucune classe");
            }
            if (previousRate != null) {
                require(bucket.provisionRatePercent().compareTo(previousRate) >= 0, code,
                        "la classe " + bucket.code() + " provisionne "
                        + bucket.provisionRatePercent() + " % alors que la precedente provisionne "
                        + previousRate + " % : une classe plus degradee ne provisionne pas moins");
            }
            previousRate = bucket.provisionRatePercent();

            if (!bucket.performing()) {
                seenNonPerforming = true;
            } else {
                require(!seenNonPerforming, code,
                        "la classe " + bucket.code() + " est saine alors qu'une classe moins "
                        + "degradee ne l'est pas : la frontiere sain / en souffrance ne s'inverse "
                        + "pas");
            }
        }
        RiskBucket last = buckets.get(buckets.size() - 1);
        require(last.toDays() == null, code,
                "la derniere classe " + last.code() + " s'arrete a " + last.toDays()
                + " jours : au-dela, aucun credit ne serait classe");

        require(cureDays >= 0, code, "periode d'observation negative : " + cureDays);
        if (suspendFromBucket != null) {
            String wanted = suspendFromBucket;
            require(buckets.stream().anyMatch(b -> b.code().equals(wanted)), code,
                    "la suspension des interets designe la classe « " + wanted
                    + " », qui ne figure pas dans la grille");
        }
    }

    /** Classe correspondant a un nombre de jours de retard. La grille couvre tout par construction. */
    public RiskBucket bucketFor(long daysPastDue) {
        return buckets.stream().filter(bucket -> bucket.covers(daysPastDue)).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Grille " + code + " : aucune classe pour " + daysPastDue + " jours de retard. "
                + "La validation au chargement aurait du l'empecher."));
    }

    public Optional<RiskBucket> byCode(String bucketCode) {
        return buckets.stream().filter(bucket -> bucket.code().equals(bucketCode)).findFirst();
    }

    /** La plus degradee de deux classes. C'est l'operation de la contagion. */
    public RiskBucket worst(RiskBucket left, RiskBucket right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /**
     * Vrai si les interets cessent d'etre constates en produits a partir de cette classe.
     *
     * <p>L'omission de cette regle surevalue le produit net bancaire : la banque continue de
     * constater en resultat des interets qu'elle ne percevra pas. C'est une non-conformite
     * directe, et l'une des plus frequentes dans les developpements maison.
     */
    public boolean suspendsAt(RiskBucket bucket) {
        if (suspendFromBucket == null) {
            return false;
        }
        return byCode(suspendFromBucket)
            .map(threshold -> bucket.ordinal() >= threshold.ordinal())
            .orElse(false);
    }

    /**
     * Vrai si un credit redevenu sain doit encore etre observe avant d'etre reclasse.
     *
     * @param daysSinceLastArrears jours ecoules depuis le dernier arrete ou le credit portait un
     *                             impaye
     */
    public boolean stillUnderObservation(long daysSinceLastArrears) {
        return cureDays > 0 && daysSinceLastArrears < cureDays;
    }

    private static void require(boolean condition, String code, String detail) {
        if (!condition) {
            throw new InvalidRiskGridException(code, detail);
        }
    }

    /** Grille de declassement refusee. */
    public static class InvalidRiskGridException extends RuntimeException {
        public InvalidRiskGridException(String code, String detail) {
            super("Grille de risque " + code + " : " + detail + ".");
        }
    }
}
