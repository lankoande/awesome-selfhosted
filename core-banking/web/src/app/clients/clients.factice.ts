import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Clients } from './clients.port';
import {
  BeneficiaireEffectif, DemandeOuverture, DemandeTiers, Dossier, IssueOuverture,
  PageTiers, Piece, Tiers,
} from './modele/clients.modele';

/**
 * Le référentiel client de démonstration.
 *
 * Son catalogue existe pour montrer **chaque issue**, pas pour faire joli : un
 * dossier complet qui s'ouvre, un dossier à qui il manque une pièce, une pièce
 * expirée, une personne morale dont un bénéficiaire n'est pas vérifié, un tiers
 * bloqué, une revue KYC dépassée. Un jeu de démonstration où tout marche
 * n'apprend rien sur l'écran.
 */
const JOUR = '2026-09-18';

const TIERS: readonly Tiers[] = [
  {
    id: 'p-sankara', reference: 'CLI-000417', displayName: 'SANKARA Aminata',
    kind: 'NATURAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '1988-04-12',
    segment: 'Particulier', status: 'ACTIVE', statusReason: null,
    kycStatus: 'VERIFIED', kycLevel: 'STANDARD', kycVerifiedOn: '2026-03-12',
    kycReviewDue: '2028-03-12', riskRating: 'LOW',
  },
  {
    id: 'p-ouedraogo', reference: 'CLI-001182', displayName: 'OUEDRAOGO Salif',
    kind: 'NATURAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '1975-11-30',
    segment: 'Particulier', status: 'ACTIVE', statusReason: null,
    kycStatus: 'VERIFIED', kycLevel: 'STANDARD', kycVerifiedOn: '2025-06-01',
    kycReviewDue: '2027-06-01', riskRating: 'LOW',
  },
  {
    id: 'p-kabore-ets', reference: 'CLI-002044', displayName: 'ETS KABORE & Fils',
    kind: 'LEGAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '2014-02-03',
    segment: 'Entreprise', status: 'ACTIVE', statusReason: null,
    kycStatus: 'VERIFIED', kycLevel: 'ENHANCED', kycVerifiedOn: '2026-01-20',
    kycReviewDue: '2027-01-20', riskRating: 'MEDIUM',
  },
  {
    id: 'p-traore', reference: 'CLI-003390', displayName: 'TRAORE Fatimata',
    kind: 'NATURAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '1992-07-19',
    segment: 'Particulier', status: 'BLOCKED', statusReason: 'Opposition judiciaire en cours',
    kycStatus: 'VERIFIED', kycLevel: 'STANDARD', kycVerifiedOn: '2025-09-02',
    kycReviewDue: '2027-09-02', riskRating: 'HIGH',
  },
  {
    id: 'p-compaore', reference: 'CLI-004265', displayName: 'COMPAORE Issa',
    kind: 'NATURAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '1969-01-08',
    segment: 'Particulier', status: 'ACTIVE', statusReason: null,
    kycStatus: 'EXPIRED', kycLevel: 'STANDARD', kycVerifiedOn: '2023-08-15',
    kycReviewDue: '2026-08-15', riskRating: 'MEDIUM',
  },
  {
    id: 'p-nikiema', reference: 'CLI-005108', displayName: 'NIKIEMA Rasmata',
    kind: 'NATURAL_PERSON', countryCode: 'BF', birthOrRegistrationDate: '2001-05-26',
    segment: 'Particulier', status: 'ACTIVE', statusReason: null,
    kycStatus: 'PENDING', kycLevel: 'SIMPLIFIED', kycVerifiedOn: null,
    kycReviewDue: null, riskRating: 'LOW',
  },
];

const DOSSIERS: Readonly<Record<string, Dossier>> = {
  'p-sankara': {
    partyId: 'p-sankara', reference: 'CLI-000417', kind: 'NATURAL_PERSON', level: 'STANDARD',
    complete: true, missing: [], expired: [], beneficialOwnersMissing: false,
    unverifiedOwners: [], policyDeclared: true, summary: 'Dossier complet.',
  },
  'p-ouedraogo': {
    partyId: 'p-ouedraogo', reference: 'CLI-001182', kind: 'NATURAL_PERSON', level: 'STANDARD',
    complete: false, missing: ['ADDRESS_PROOF'], expired: [], beneficialOwnersMissing: false,
    unverifiedOwners: [], policyDeclared: true,
    summary: 'Pièce manquante : justificatif de domicile.',
  },
  'p-kabore-ets': {
    partyId: 'p-kabore-ets', reference: 'CLI-002044', kind: 'LEGAL_PERSON', level: 'ENHANCED',
    complete: false, missing: [], expired: ['TRADE_REGISTRY_EXTRACT'],
    beneficialOwnersMissing: false, unverifiedOwners: ['KABORE Boureima'], policyDeclared: true,
    summary: 'Extrait du registre expiré ; un bénéficiaire effectif non vérifié.',
  },
  'p-traore': {
    partyId: 'p-traore', reference: 'CLI-003390', kind: 'NATURAL_PERSON', level: 'STANDARD',
    complete: true, missing: [], expired: [], beneficialOwnersMissing: false,
    unverifiedOwners: [], policyDeclared: true, summary: 'Dossier complet.',
  },
  'p-compaore': {
    partyId: 'p-compaore', reference: 'CLI-004265', kind: 'NATURAL_PERSON', level: 'STANDARD',
    complete: true, missing: [], expired: [], beneficialOwnersMissing: false,
    unverifiedOwners: [], policyDeclared: true, summary: 'Dossier complet.',
  },
  'p-nikiema': {
    partyId: 'p-nikiema', reference: 'CLI-005108', kind: 'NATURAL_PERSON', level: 'SIMPLIFIED',
    complete: false, missing: ['IDENTITY', 'PHOTO'], expired: [],
    beneficialOwnersMissing: false, unverifiedOwners: [], policyDeclared: true,
    summary: "Pièces manquantes : pièce d'identité, photographie.",
  },
};

const PIECES: Readonly<Record<string, readonly Piece[]>> = {
  'p-sankara': [
    { id: 'd-1', kind: 'IDENTITY', reference: 'CNIB B1284455', issuer: 'ONI',
      issuedOn: '2022-03-02', expiresOn: '2032-03-01', collectedOn: '2026-03-12', supersededBy: null },
    { id: 'd-2', kind: 'ADDRESS_PROOF', reference: 'Facture SONABEL 03/2026', issuer: 'SONABEL',
      issuedOn: '2026-03-05', expiresOn: null, collectedOn: '2026-03-12', supersededBy: null },
    { id: 'd-0', kind: 'ADDRESS_PROOF', reference: 'Facture ONEA 11/2024', issuer: 'ONEA',
      issuedOn: '2024-11-04', expiresOn: null, collectedOn: '2024-11-20', supersededBy: 'd-2' },
  ],
  'p-ouedraogo': [
    { id: 'd-3', kind: 'IDENTITY', reference: 'CNIB B0997310', issuer: 'ONI',
      issuedOn: '2019-05-14', expiresOn: '2029-05-13', collectedOn: '2025-06-01', supersededBy: null },
  ],
  'p-kabore-ets': [
    { id: 'd-4', kind: 'ARTICLES', reference: 'Statuts du 03/02/2014', issuer: 'Notaire',
      issuedOn: '2014-02-03', expiresOn: null, collectedOn: '2026-01-20', supersededBy: null },
    { id: 'd-5', kind: 'TRADE_REGISTRY_EXTRACT', reference: 'RCCM BF-OUA-2014-B-1207',
      issuer: 'Greffe', issuedOn: '2025-01-10', expiresOn: '2026-01-10',
      collectedOn: '2026-01-20', supersededBy: null },
    { id: 'd-6', kind: 'SIGNATURE_SPECIMEN', reference: 'Carton de signature', issuer: null,
      issuedOn: '2026-01-20', expiresOn: null, collectedOn: '2026-01-20', supersededBy: null },
  ],
};

const BENEFICIAIRES: Readonly<Record<string, readonly BeneficiaireEffectif[]>> = {
  'p-kabore-ets': [
    { id: 'b-1', ownerName: 'KABORE Adama', ownershipPercent: '60',
      ownerReference: 'CL-0004118', declaredOn: '2026-01-20', validTo: null },
    { id: 'b-2', ownerName: 'KABORE Boureima', ownershipPercent: '40',
      ownerReference: null, declaredOn: '2026-01-20', validTo: null },
  ],
};

@Injectable()
export class ClientsFactice implements Clients {
  private readonly tiers = [...TIERS];
  /** Rendu vrai par le premier essai sur un compte d'entreprise : le second regard. */
  /** Numérote les demandes en attente pour que deux envois ne se confondent pas. */
  private rang = 7;

  async chercher(_e: string, q: string, page: number, taille: number): Promise<PageTiers> {
    await this.attendre();
    const cherche = q.trim().toLowerCase();
    const trouves = cherche
      ? this.tiers.filter((t) => t.displayName.toLowerCase().includes(cherche)
                                 || t.reference.toLowerCase().includes(cherche))
      : this.tiers;
    const debut = page * taille;
    return {
      tiers: trouves.slice(debut, debut + taille),
      page,
      precedent: page > 0,
      suivant: debut + taille < trouves.length,
    };
  }

  async lire(_e: string, partyId: string): Promise<Tiers> {
    await this.attendre();
    const trouve = this.tiers.find((t) => t.id === partyId);
    if (!trouve) {
      throw new RefusMetier(404, 'TIERS_INCONNU', 'Ce client est introuvable.',
                            "La référence n'existe pas dans cette entité.");
    }
    return trouve;
  }

  async dossier(_e: string, partyId: string): Promise<Dossier> {
    await this.attendre();
    const trouve = DOSSIERS[partyId];
    if (!trouve) throw new RefusMetier(404, 'DOSSIER_INCONNU', 'Dossier introuvable.');
    return trouve;
  }

  async pieces(_e: string, partyId: string): Promise<readonly Piece[]> {
    await this.attendre();
    return PIECES[partyId] ?? [];
  }

  async beneficiaires(_e: string, partyId: string): Promise<readonly BeneficiaireEffectif[]> {
    await this.attendre();
    return BENEFICIAIRES[partyId] ?? [];
  }

  async creer(demande: DemandeTiers): Promise<Tiers> {
    await this.attendre(400);
    const numero = String(this.tiers.length + 1).padStart(6, '0');
    const cree: Tiers = {
      id: `p-${numero}`, reference: `CLI-${numero}`, displayName: demande.displayName,
      kind: demande.kind, countryCode: demande.countryCode,
      birthOrRegistrationDate: demande.birthOrRegistrationDate,
      segment: demande.segment, status: 'ACTIVE', statusReason: null,
      // Un tiers naît non vérifié : la connaissance client se constate, elle ne
      // se suppose pas à la saisie.
      kycStatus: 'PENDING', kycLevel: 'SIMPLIFIED', kycVerifiedOn: null,
      kycReviewDue: null, riskRating: 'LOW',
    };
    this.tiers.push(cree);
    (DOSSIERS as Record<string, Dossier>)[cree.id] = {
      partyId: cree.id, reference: cree.reference, kind: cree.kind, level: 'SIMPLIFIED',
      complete: false, missing: ['IDENTITY'], expired: [], beneficialOwnersMissing: false,
      unverifiedOwners: [], policyDeclared: true,
      summary: "Pièce manquante : pièce d'identité.",
    };
    return cree;
  }

  async ouvrirCompte(demande: DemandeOuverture): Promise<IssueOuverture> {
    await this.attendre(500);
    const titulaire = await this.lire(demande.legalEntityId, demande.holderPartyId);
    // Le socle refuse ce que l'écran annonçait : la règle est la sienne.
    if (titulaire.status !== 'ACTIVE' || titulaire.kycStatus !== 'VERIFIED'
        || !(DOSSIERS[titulaire.id]?.complete ?? false)) {
      throw new RefusMetier(
        409, 'TIERS_NON_OUVRABLE', "Ce client ne peut pas recevoir de nouveau compte.",
        (DOSSIERS[titulaire.id]?.summary ?? '')
        + ' Ses comptes existants continuent de fonctionner.');
    }
    // Le socle n'ouvre jamais un compte dans la foulée : le contrôleur répond
    // 202 sans condition et le contrat ne déclare pas d'autre issue favorable.
    // La démonstration montre donc ce que le guichetier verra réellement.
    return { operationId: `PND-0002${this.rang++}` };
  }

  async produits(): Promise<readonly { code: string; libelle: string }[]> {
    // Le contrat n'expose pas la liste des produits ouvrables : il sait les
    // rédiger et les activer, pas les lire. On ne devine pas un catalogue —
    // l'écran passe en saisie libre et le dit.
    return [];
  }

  private attendre(ms = 250): Promise<void> {
    return new Promise((r) => setTimeout(r, ms));
  }
}
