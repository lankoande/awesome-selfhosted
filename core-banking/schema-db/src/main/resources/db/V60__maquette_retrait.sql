--liquibase formatted sql
--changeset socle:60 splitStatements:false
--comment trace du retrait d'un brouillon de maquette d'etat financier
-- =====================================================================================
--  Retrait d'un brouillon de maquette
--
--  Meme manque que pour les schemas comptables, et meme raison de le combler. Le statut
--  WITHDRAWN existe dans la contrainte de V39 ; il lui manquait sa signature.
--
--  Un brouillon n'engage rien, son redacteur peut le retirer seul, et c'est justement
--  pour cela qu'il faut savoir qui l'a fait : sans trace, une maquette attendue qui
--  n'existe pas n'a aucune explication, et on la reecrit au lieu de comprendre pourquoi
--  elle avait ete abandonnee.
--
--  La fermeture de validite, elle, passe a deux : pending_operation en garde la trace.
-- =====================================================================================

ALTER TABLE statement_layout
    ADD COLUMN withdrawn_by UUID,
    ADD COLUMN withdrawn_at TIMESTAMPTZ;

ALTER TABLE statement_layout
    ADD CONSTRAINT ck_layout_withdrawal CHECK (
        status <> 'WITHDRAWN'
        OR (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL));
