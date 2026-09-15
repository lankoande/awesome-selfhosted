-- Row Level Security par entite : les traitements.
ALTER TABLE batch_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON batch_run
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE batch_step ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON batch_step
    USING (EXISTS (SELECT 1 FROM batch_run r WHERE r.id = batch_step.run_id))
    WITH CHECK (EXISTS (SELECT 1 FROM batch_run r WHERE r.id = batch_step.run_id));
