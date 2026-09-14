package io.corebanking.tfj.steps;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.ledger.store.Database;
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

    @Override
    public StepResult execute(TfjContext context) {
        LocalDate next = calendar.nextBusinessDay(context.businessDate());
        database.inTransaction(c -> {
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
            return null;
        });
        return StepResult.of(1, 1);
    }
}
