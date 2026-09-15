-- Row Level Security par entite : les conditions de banque. Un calendrier est partage entre
-- entites d'un meme pays et reste lisible par toutes.
ALTER TABLE value_date_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON value_date_rule
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
