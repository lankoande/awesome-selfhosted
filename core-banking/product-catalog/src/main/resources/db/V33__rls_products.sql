-- Row Level Security par entite : le paramétrage produit et les schemas comptables.
ALTER TABLE product_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON product_version
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE product_parameter ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON product_parameter
    USING (EXISTS (SELECT 1 FROM product_version v
                    WHERE v.id = product_parameter.product_version_id))
    WITH CHECK (EXISTS (SELECT 1 FROM product_version v
                         WHERE v.id = product_parameter.product_version_id));

ALTER TABLE accounting_schema ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON accounting_schema
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());

ALTER TABLE account_product ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON account_product
    USING (EXISTS (SELECT 1 FROM account a WHERE a.id = account_product.account_id))
    WITH CHECK (EXISTS (SELECT 1 FROM account a WHERE a.id = account_product.account_id));
