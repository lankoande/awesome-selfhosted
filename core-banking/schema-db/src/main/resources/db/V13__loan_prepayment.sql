--liquibase formatted sql
--changeset socle:13 splitStatements:false
--comment loan prepayment
-- =====================================================================================
--  Remboursement anticipe et retour a meilleure fortune
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  Conditions financieres du contrat.
--
--  Elles n'y figuraient pas : l'echeancier suffisait tant qu'il n'etait produit qu'une
--  fois. Un remboursement anticipe oblige a le reconstruire, et donc a retrouver le taux,
--  la periodicite, la methode et les accessoires du contrat. Les redemander a l'appelant
--  reviendrait a laisser chaque canal reinventer les conditions du credit.
-- -------------------------------------------------------------------------------------
ALTER TABLE loan_contract ADD COLUMN annual_rate_percent NUMERIC(12,6);
ALTER TABLE loan_contract ADD COLUMN frequency TEXT;
ALTER TABLE loan_contract ADD COLUMN amortisation_method TEXT;
ALTER TABLE loan_contract ADD COLUMN day_count TEXT;
ALTER TABLE loan_contract ADD COLUMN periodic_fee NUMERIC(23,5) NOT NULL DEFAULT 0;
ALTER TABLE loan_contract ADD COLUMN insurance_basis TEXT;
ALTER TABLE loan_contract ADD COLUMN insurance_rate_percent NUMERIC(12,6) NOT NULL DEFAULT 0;
ALTER TABLE loan_contract ADD COLUMN tax_on_interest_percent NUMERIC(12,6) NOT NULL DEFAULT 0;

-- Un remboursement anticipe est une troisieme origine de reglement, a cote du prelevement
-- et du versement au guichet. La distinguer n'est pas cosmetique : elle ne s'impute pas
-- dans le meme ordre et ne produit pas le meme effet sur l'echeancier.
ALTER TABLE loan_payment DROP CONSTRAINT loan_payment_source_check;
ALTER TABLE loan_payment ADD CONSTRAINT ck_payment_source
    CHECK (source IN ('DIRECT_DEBIT','MANUAL','PREPAYMENT'));

-- -------------------------------------------------------------------------------------
--  Retour a meilleure fortune.
--
--  Un credit regularise ne redevient pas sain le jour meme : la plupart des profils
--  imposent une periode d'observation sans incident. Sans elle, un debiteur qui regle la
--  veille de l'arrete efface son declassement et la provision qui l'accompagne, puis
--  retombe en impaye le lendemain — le portefeuille parait sain a chaque arrete et ne
--  l'est jamais.
-- -------------------------------------------------------------------------------------
ALTER TABLE risk_profile ADD COLUMN cure_days INTEGER NOT NULL DEFAULT 0
    CHECK (cure_days >= 0);

ALTER TABLE loan_classification DROP CONSTRAINT loan_classification_reason_check;
ALTER TABLE loan_classification ADD CONSTRAINT ck_classification_reason
    CHECK (reason IN ('AGEING','CONTAGION','CURE'));
