--liquibase formatted sql
--changeset socle:29 splitStatements:false
--comment rls loans
-- Row Level Security par entite : le credit.
ALTER TABLE loan_contract ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_contract
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE loan_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_schedule
    USING (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_schedule.contract_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_schedule.contract_id));

ALTER TABLE loan_receivable ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_receivable
    USING (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_receivable.contract_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_receivable.contract_id));

ALTER TABLE loan_payment ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON loan_payment
    USING (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_payment.contract_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loan_contract k WHERE k.id = loan_payment.contract_id));

ALTER TABLE collateral ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON collateral
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE collateral_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON collateral_policy
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE risk_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON risk_profile
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
