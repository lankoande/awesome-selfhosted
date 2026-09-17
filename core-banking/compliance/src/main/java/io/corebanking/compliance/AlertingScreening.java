package io.corebanking.compliance;

import io.corebanking.ledger.store.Database;
import io.corebanking.party.Screening;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Le filtrage du fournisseur, augmente de l'alerte qu'une correspondance doit laisser.
 *
 * <p>Le socle appelle {@link Screening} a la creation d'un tiers et bloque le dossier sur
 * correspondance. C'est necessaire et insuffisant : un dossier bloque sans alerte n'a pas de file
 * d'instruction, personne n'est nomme pour lever le doute, et rien ne garde trace de la decision.
 * L'inspection ne demande pas si le blocage a eu lieu — elle demande qui a decide de le lever, et
 * sur quoi.
 *
 * <p>Ce decorateur s'interpose sans toucher au module client : il delegue au vrai fournisseur,
 * et sur correspondance leve une alerte <b>dans la transaction qui cree le tiers</b>. Le dossier
 * bloque et son alerte naissent ensemble, ou ni l'un ni l'autre.
 */
public final class AlertingScreening implements Screening {

    private final Screening delegate;
    private final Database database;

    public AlertingScreening(Screening delegate, Database database) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Optional<Match> screen(Subject subject) {
        Optional<Match> match = delegate.screen(subject);
        if (match.isEmpty()) {
            return match;
        }
        Match found = match.get();
        database.inTransaction(c -> {
            LocalDate on = ComplianceDates.businessDate(c, subject.partyId());
            AmlAlerts.raise(c, ComplianceDates.entityOf(c, subject.partyId()), subject.partyId(),
                            null, AmlAlerts.Origin.SCREENING, on,
                            "Correspondance " + found.list() + " / " + found.reference() + " — "
                            + found.detail(), null, List.of(), null);
            return null;
        });
        return match;
    }
}
