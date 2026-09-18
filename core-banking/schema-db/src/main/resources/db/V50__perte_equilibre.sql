--liquibase formatted sql
--changeset socle:50 splitStatements:false
--comment perte equilibre
-- =====================================================================================
--  Passage en perte : l'egalite que V49 ecrivait mal
--
--  La contrainte de V49 incluait la reprise de provision dans ce qui sort de l'actif :
--
--      principal + creances + reprise = provision_utilisee + reserves + perte
--
--  Elle est fausse des qu'un dossier est sur-provisionne. La reprise n'est pas une sortie
--  d'actif : c'est la part de provision devenue sans objet, qui revient au resultat. Ce qui
--  sort est absorbe, et rien d'autre :
--
--      principal + creances = reserves + provision_utilisee + perte
--
--  L'ecriture, elle, debite la provision entiere et rend la difference en produit : c'est ce
--  qui l'equilibre, et c'est ce que V49 ne faisait pas — le ledger aurait refuse l'ecriture
--  d'un dossier sur-provisionne, de nuit, sur la seule operation qui ne se rejoue pas.
-- =====================================================================================
ALTER TABLE loan_write_off DROP CONSTRAINT ck_write_off_balance;
ALTER TABLE loan_write_off ADD CONSTRAINT ck_write_off_balance CHECK (
    principal_written + receivables_written = provision_used + reserved_used + loss_recognised);
