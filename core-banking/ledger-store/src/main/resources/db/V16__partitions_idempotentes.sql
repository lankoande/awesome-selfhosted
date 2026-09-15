-- =====================================================================================
--  Creation des partitions du journal : idempotente et serialisee
--
--  Le traitement de fin de journee de chaque entite garantit desormais les partitions des
--  mois a venir a chaque bascule. Deux entites arretant leur journee en meme temps
--  demandent donc la meme partition au meme instant : sans verrou, l'une des deux echoue
--  sur « la table existe deja », et son arrete avec elle. Le verrou consultatif serialise la
--  creation ; IF NOT EXISTS couvre le cas ou la partition est apparue entre le controle et
--  la creation.
-- =====================================================================================

CREATE OR REPLACE FUNCTION ledger_ensure_partitions(p_from DATE, p_to DATE)
RETURNS INT AS $$
DECLARE
    v_month   DATE := date_trunc('month', p_from)::date;
    v_suffix  TEXT;
    v_created INT := 0;
BEGIN
    -- Un seul createur de partitions a la fois, toutes entites confondues. Le verrou est
    -- transactionnel : il tombe avec la transaction qui l'a pris, et ne peut pas fuir.
    PERFORM pg_advisory_xact_lock(hashtext('ledger_ensure_partitions'));

    WHILE v_month <= p_to LOOP
        v_suffix := to_char(v_month, 'YYYY_MM');

        IF to_regclass('journal_entry_' || v_suffix) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS journal_entry_%s PARTITION OF journal_entry
                 FOR VALUES FROM (%L) TO (%L)',
                v_suffix, v_month, v_month + INTERVAL '1 month');
            v_created := v_created + 1;
        END IF;

        IF to_regclass('journal_line_' || v_suffix) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS journal_line_%s PARTITION OF journal_line
                 FOR VALUES FROM (%L) TO (%L)',
                v_suffix, v_month, v_month + INTERVAL '1 month');
            EXECUTE format(
                'CREATE CONSTRAINT TRIGGER trg_balanced_%s
                   AFTER INSERT ON journal_line_%s
                   DEFERRABLE INITIALLY DEFERRED
                   FOR EACH ROW EXECUTE FUNCTION check_entry_balanced()',
                v_suffix, v_suffix);
            v_created := v_created + 1;
        END IF;

        v_month := (v_month + INTERVAL '1 month')::date;
    END LOOP;
    RETURN v_created;
END;
$$ LANGUAGE plpgsql;

-- Vrai si la partition du journal existe pour le mois d'une date. Sert au controle
-- prealable du traitement de fin de journee.
CREATE OR REPLACE FUNCTION ledger_partition_exists(p_date DATE) RETURNS BOOLEAN AS $$
BEGIN
    RETURN to_regclass('journal_entry_' || to_char(date_trunc('month', p_date), 'YYYY_MM'))
           IS NOT NULL;
END;
$$ LANGUAGE plpgsql;
