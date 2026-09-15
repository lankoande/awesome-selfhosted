-- Row Level Security par entite : les commissions.
ALTER TABLE fee_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON fee_charge
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE account_fee_exemption ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_fee_exemption
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_fee_exemption.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_fee_exemption.account_id));
