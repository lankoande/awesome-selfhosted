package io.corebanking.tfj.steps;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Bascule de journee.
 *
 * <p>Derniere etape, et seul mecanisme autorise a modifier la date comptable de l'entite. La
 * placer en fin de chaine n'est pas une commodite : tant qu'un controle bloquant n'a pas abouti, la
 * journee ne bascule pas, et le systeme refuse par construction de travailler sur une journee
 * suivante alors que la precedente n'est pas arretee.
 *
 * <p>La journee avance au <b>jour ouvre suivant</b>. Un week-end ou un pont n'est donc pas arrete,
 * et c'est sans consequence sur les interets : le calcul couvre toutes les journees depuis la
 * derniere remuneree jusqu'a celle traitee. Le TFJ du lundi remunere donc samedi, dimanche et
 * lundi, en une fois et pour les montants exacts.
 *
 * <p>Faire l'inverse — arreter chaque jour calendaire — est egalement pratique dans la profession.
 * Le choix se parametrera par entite ; en attendant, la bascule sur jour ouvre est le comportement
 * retenu, et il est explicite plutot que subi.
 *
 * <h2>Ce que la bascule garantit a la journee suivante</h2>
 *
 * <p>Les partitions du journal pour les trois mois a venir, et une periode comptable couvrant la
 * journee ouverte. Les deux sont idempotentes ; les deux manquaient en exploitation, ou seuls les
 * tests les creaient. Une periode deja close n'est pas rouverte : c'est une decision comptable, et
 * le controle prealable du traitement suivant la refusera en la nommant.
 */
public final class OpenNextDayStep implements TfjStep {

    private final Database database;
    private final BusinessCalendar calendar;

    public OpenNextDayStep(Database database, BusinessCalendar calendar) {
        this.database = database;
        this.calendar = calendar;
    }

    @Override
    public String name() {
        return "OPEN_NEXT_DAY";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    /**
     * Nombre de mois de partitions garantis au-dela de la journee suivante.
     *
     * <p>Trois mois laissent le temps de remarquer un traitement de fin de journee qui n'aurait
     * plus tourne, avant que la premiere ecriture d'un mois sans partition n'arrete la banque.
     */
    private static final int PARTITION_HORIZON_MONTHS = 3;

    @Override
    public StepResult execute(TfjContext context) {
        LocalDate next = calendar.nextBusinessDay(context.businessDate());
        boolean periodOpened = database.inTransaction(c -> {
            // Ce que la journee suivante exigera pour sa premiere ecriture est garanti ici, avant
            // la bascule, et dans la meme transaction qu'elle : une partition du journal pour ses
            // mois a venir, et une periode comptable qui la couvre. Jusqu'ici, ni l'une ni l'autre
            // n'etaient creees hors des tests, et la premiere ecriture du mois suivant arretait la
            // banque.
            SchemaMigrator.ensurePartitions(c, next, next.plusMonths(PARTITION_HORIZON_MONTHS));
            boolean opened = Entities.ensurePeriodCovering(c, context.legalEntityId(), next);
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE legal_entity SET current_business_date = ?"
                + " WHERE id = ? AND current_business_date = ?")) {
                ps.setObject(1, next);
                ps.setObject(2, context.legalEntityId());
                // Condition sur la date courante : si une autre execution a deja bascule, la mise
                // a jour ne fait rien, au lieu de faire sauter une journee.
                ps.setObject(3, context.businessDate());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Bascule de journee", e);
            }
            return opened;
        });
        return StepResult.of(1, periodOpened ? 2 : 1);
    }
}
