--liquibase formatted sql
--changeset socle:38 splitStatements:false
--comment index grand livre
-- =====================================================================================
--  Grand livre d'un compte par curseur, et appartenance d'une affectation a son entite
--
--  L'ordre total du journal — date comptable, instant de connaissance, ecriture, ligne — doit
--  etre porte par un index pour chaque cle de lecture. V37 le porte pour l'entite ; ici pour le
--  compte, sans quoi le grand livre d'un compte chaud relirait a chaque page toute la plage.
--  L'index (compte, date comptable) qu'il prolonge devient un prefixe : il est retire.
-- =====================================================================================
CREATE INDEX idx_line_account_journal
    ON journal_line (account_id, booking_date, knowledge_time, entry_id, line_number);
DROP INDEX idx_line_account_book;

-- Une affectation appartient a l'entite de son exercice : le schema le tient, pas seulement
-- le code qui l'ecrit.
ALTER TABLE fiscal_year ADD CONSTRAINT uq_fiscal_year_entity UNIQUE (id, legal_entity_id);
ALTER TABLE result_appropriation
    ADD CONSTRAINT fk_appropriation_year_entity
    FOREIGN KEY (fiscal_year_id, legal_entity_id) REFERENCES fiscal_year (id, legal_entity_id);
