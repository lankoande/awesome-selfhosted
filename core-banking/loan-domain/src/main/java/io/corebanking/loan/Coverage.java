package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Couverture d'un credit par ses suretes, surete par surete.
 *
 * <p>Le detail est conserve et pas seulement le total. Une garantie ecartee — expertise perimee,
 * rang absorbe, type sans quotite — doit etre <b>signalee</b>, sinon la banque croit couvrir un
 * encours qu'elle ne couvre pas, et ne le decouvre qu'a la realisation.
 */
public record Coverage(Money eligible, List<Line> lines) {

    public Coverage {
        Objects.requireNonNull(eligible, "eligible");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /**
     * Sort d'une surete dans le calcul.
     *
     * @param exclusion motif d'exclusion, nul si la surete est retenue
     */
    public record Line(UUID chargeId, String kind, Money retained, Money eligible,
                       String exclusion) {

        public boolean isExcluded() {
            return exclusion != null;
        }
    }

    /** Suretes ecartees du calcul, avec leur motif. */
    public List<Line> excluded() {
        return lines.stream().filter(Line::isExcluded).toList();
    }
}
