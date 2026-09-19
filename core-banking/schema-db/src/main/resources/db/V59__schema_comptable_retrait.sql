--liquibase formatted sql
--changeset socle:59 splitStatements:false
--comment trace du retrait d'un brouillon de schema comptable
-- =====================================================================================
--  Retrait d'un brouillon de schema comptable
--
--  Le schema comptable naissait et s'activait, et rien d'autre. Deux actes manquaient,
--  et le second explique le premier.
--
--  La fermeture de validite, d'abord. La contrainte d'exclusion de V5 interdit deux
--  validites actives qui se croisent sous le meme code : tant que le schema en vigueur
--  n'a pas de fin, aucun successeur ne peut etre active. La fermeture n'est donc pas
--  une commodite, c'est ce qui rend le versionnement possible. Elle passe a deux, et
--  pending_operation en garde la trace — rien a ajouter ici.
--
--  Le retrait d'un brouillon, ensuite. Un brouillon n'engage rien, son redacteur peut
--  le retirer seul, et c'est justement pour cela qu'il faut savoir qui l'a fait : sans
--  trace, un schema attendu qui n'existe pas n'a aucune explication, et on le reecrit
--  au lieu de comprendre pourquoi il avait ete abandonne. Le statut WITHDRAWN existe
--  deja dans la contrainte de V5 ; il lui manquait sa signature.
-- =====================================================================================

ALTER TABLE accounting_schema
    ADD COLUMN withdrawn_by UUID,
    ADD COLUMN withdrawn_at TIMESTAMPTZ;

-- Un retrait sans auteur ni date serait indistinguable d'un statut pose par erreur.
ALTER TABLE accounting_schema
    ADD CONSTRAINT ck_schema_withdrawal CHECK (
        status <> 'WITHDRAWN'
        OR (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL));
