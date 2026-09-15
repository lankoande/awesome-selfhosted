-- =====================================================================================
--  Restitutions et affectation du resultat
--
--  Le journal se lit par curseur : la page suivante reprend strictement apres une position
--  (date comptable, instant de connaissance, ecriture, ligne), quatre colonnes de la ligne
--  elle-meme, portees par un index dans cet ordre. Le cout d'une page ne depend pas de ce qui
--  la precede : c'est ce qui rend une extraction massive possible sans relire la banque a
--  chaque page. Dans une journee, l'ordre est celui ou les ecritures ont ete connues.
-- =====================================================================================
CREATE INDEX idx_line_entity_journal
    ON journal_line (legal_entity_id, booking_date, knowledge_time, entry_id, line_number);

-- L'affectation du resultat d'un exercice clos : la decision de l'assemblee, comptabilisee a
-- deux, une ecriture qui solde le compte de resultat de l'exercice sur les comptes de reserves,
-- de report a nouveau ou de dividendes a payer. Une affectation ne s'efface pas : pour la
-- refaire, on contre-passe son ecriture, et la contre-passation se voit ici par le journal.
CREATE TABLE result_appropriation (
    id              UUID PRIMARY KEY,
    legal_entity_id UUID NOT NULL REFERENCES legal_entity(id),
    fiscal_year_id  UUID NOT NULL REFERENCES fiscal_year(id),
    entry_id        UUID NOT NULL,
    booking_date    DATE NOT NULL,
    decided_on      DATE NOT NULL,
    reference       TEXT NOT NULL,
    net_result      NUMERIC(23,5) NOT NULL,
    created_by      UUID NOT NULL,
    approved_by     UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_appropriation_approval CHECK (approved_by <> created_by),
    CONSTRAINT ck_appropriation_reference CHECK (length(trim(reference)) > 0),
    CONSTRAINT uq_appropriation_entry UNIQUE (entry_id)
);

CREATE INDEX idx_appropriation_year ON result_appropriation(fiscal_year_id);

ALTER TABLE result_appropriation ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON result_appropriation
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
