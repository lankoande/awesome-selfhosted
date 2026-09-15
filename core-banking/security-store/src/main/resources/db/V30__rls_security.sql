-- Row Level Security par entite : la double validation et la piste d'audit des habilitations.
ALTER TABLE pending_operation ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON pending_operation
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE operation_approval ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON operation_approval
    USING (EXISTS (SELECT 1 FROM pending_operation p WHERE p.id = operation_approval.operation_id))
    WITH CHECK (EXISTS (SELECT 1 FROM pending_operation p
                         WHERE p.id = operation_approval.operation_id));

-- La piste d'audit se lit par entite visee ; elle s'ecrit hors transaction metier, dans
-- l'entite de l'appelant : une tentative transverse y figure sous l'entite qu'elle visait.
ALTER TABLE authorization_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON authorization_audit
    USING (target_entity_id = ledger_current_entity()
           OR caller_entity_id = ledger_current_entity())
    WITH CHECK (caller_entity_id = ledger_current_entity());
