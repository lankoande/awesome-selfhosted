-- =====================================================================================
--  Le client d'un credit est un tiers du referentiel, plus un identifiant nu.
--
--  La contagion de declassement, l'agregation des engagements et le blocage d'un dossier
--  ont besoin de savoir de qui l'on parle. Un contrat existant sans client reste licite :
--  le rattachement viendra de l'origination.
-- =====================================================================================
ALTER TABLE loan_contract
    ADD CONSTRAINT fk_contract_customer FOREIGN KEY (customer_id) REFERENCES party(id);
