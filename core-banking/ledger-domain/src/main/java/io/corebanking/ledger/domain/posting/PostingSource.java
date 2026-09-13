package io.corebanking.ledger.domain.posting;

/** Origine d'une ecriture. Conditionne les regles de correction et la tracabilite. */
public enum PostingSource {
    ONLINE, BATCH, MIGRATION, CORRECTION
}
