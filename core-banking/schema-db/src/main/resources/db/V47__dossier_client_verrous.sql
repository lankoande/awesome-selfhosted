--liquibase formatted sql
--changeset socle:47 splitStatements:false
--comment dossier client verrous
-- =====================================================================================
--  Dossier client : ce que la base garantit, plutot que le service
--
--  Trois manques de V46, tous invisibles a un seul appelant et tous vrais a deux :
--
--  1. Deux depots concurrents de la meme nature de piece laissaient deux pieces en vigueur.
--     Le service en remplace une ; il ne voit pas celle que la transaction voisine vient
--     d'inserer. Un index unique partiel le rend impossible : une seule piece en vigueur par
--     tiers et par nature. Le chainage impose alors de marquer l'ancienne avant d'inserer la
--     nouvelle, donc de designer une ligne qui n'existe pas encore : la cle etrangere devient
--     differee, verifiee a la validation.
--
--  2. kyc_policy_document n'avait pas de politique de securite au niveau des lignes. Sa mere
--     en a une ; une table fille se protege par sa mere, comme partout ailleurs ici.
-- =====================================================================================

-- Une piece par tiers et par nature, en vigueur : le reste est l'historique, chaine.
DROP INDEX idx_document_current;
ALTER TABLE party_document DROP CONSTRAINT party_document_superseded_by_fkey;
ALTER TABLE party_document ADD CONSTRAINT party_document_superseded_by_fkey
    FOREIGN KEY (superseded_by) REFERENCES party_document(id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX uq_document_current ON party_document(party_id, kind)
    WHERE superseded_by IS NULL;

ALTER TABLE kyc_policy_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON kyc_policy_document
    USING (EXISTS (SELECT 1 FROM kyc_policy p WHERE p.id = kyc_policy_document.policy_id))
    WITH CHECK (EXISTS (SELECT 1 FROM kyc_policy p WHERE p.id = kyc_policy_document.policy_id));
