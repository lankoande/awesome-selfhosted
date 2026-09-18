--liquibase formatted sql
--changeset socle:28 splitStatements:false
--comment rls party
-- Row Level Security par entite : le referentiel client.
ALTER TABLE party ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE party_identifier ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_identifier
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE party_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON party_event
    USING (EXISTS (SELECT 1 FROM party p WHERE p.id = party_event.party_id))
    WITH CHECK (EXISTS (SELECT 1 FROM party p WHERE p.id = party_event.party_id));

ALTER TABLE account_holder ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_holder
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_holder.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_holder.account_id));
