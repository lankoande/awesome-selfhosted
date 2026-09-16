package io.corebanking.party;

/**
 * Nature d'une piece du dossier client.
 *
 * <p>La liste est fermee, et c'est voulu : une nature libre rendrait la matrice d'exigences
 * ininterpretable — « justificatif » et « justif. domicile » y compteraient pour deux pieces
 * differentes, et le dossier passerait pour incomplet sans que personne ne sache pourquoi.
 */
public enum DocumentKind {
    /** Piece d'identite : carte nationale, passeport, titre de sejour. */
    IDENTITY,
    /** Justificatif de domicile. */
    ADDRESS_PROOF,
    /** Justificatif de revenus : bulletin, avis d'imposition, etats financiers. */
    INCOME_PROOF,
    /** Statuts d'une personne morale. */
    ARTICLES,
    /** Extrait du registre du commerce. */
    TRADE_REGISTRY_EXTRACT,
    /** Attestation fiscale. */
    TAX_CERTIFICATE,
    /** Specimen de signature. */
    SIGNATURE_SPECIMEN,
    PHOTO,
    OTHER
}
