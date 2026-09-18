--liquibase formatted sql
--changeset socle:57 splitStatements:false
--comment idempotence des soumissions a double validation
-- =====================================================================================
--  Idempotence des soumissions a double validation
--
--  Une operation a double validation ne touche pas le registre au moment ou elle est
--  soumise : elle attend. C'est precisement ce qui rendait le doublon indolore a ecrire
--  et couteux a decouvrir — deux demandes d'ouverture pour le meme client, deux chequiers,
--  deux caisses, qu'un valideur approuve de bonne foi des jours plus tard sans savoir
--  qu'il valide deux fois la meme chose. Le posting_idempotency ne couvre pas ce cas :
--  il protege les ecritures, et une soumission n'en produit aucune.
--
--  La cle est portee par la soumission elle-meme. Son unicite est bornee a l'entite ET au
--  maker : deux personnes qui emploient par hasard la meme cle font deux demandes, ce qui
--  est la verite ; et la cle d'un tiers ne peut pas servir a lire ce qu'il a soumis.
--
--  L'empreinte de la requete accompagne la cle. Rejouer une cle avec une requete
--  differente n'est pas un rejeu, c'est une confusion : le socle la refuse au lieu de
--  rendre le premier resultat pour une demande qui n'est pas celle-la.
-- =====================================================================================

ALTER TABLE pending_operation ADD COLUMN idempotency_key TEXT;
ALTER TABLE pending_operation ADD COLUMN request_digest  TEXT;

-- Partiel : les soumissions anterieures, et celles d'un appelant qui n'envoie pas de cle,
-- restent possibles et ne se genent pas entre elles.
CREATE UNIQUE INDEX ux_pending_operation_idempotency
    ON pending_operation (legal_entity_id, maker_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN pending_operation.idempotency_key IS
    'Cle d''idempotence de la soumission, portee par l''appelant ; unique par entite et par maker.';
COMMENT ON COLUMN pending_operation.request_digest IS
    'Empreinte de la requete soumise : un rejeu de la cle avec une autre requete est refuse.';
