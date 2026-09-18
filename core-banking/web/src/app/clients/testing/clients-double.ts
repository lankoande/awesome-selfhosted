import { Clients } from '../clients.port';
import {
  BeneficiaireEffectif, DemandeOuverture, DemandeTiers, Dossier, IssueOuverture,
  PageTiers, Piece, Tiers,
} from '../modele/clients.modele';

export const TIERS_DOUBLE_ID = '33333333-3333-4333-8333-000000004217';

/** Un client sur lequel tout est en ordre : le cas où un compte peut s'ouvrir. */
export const TIERS_DOUBLE: Tiers = {
  id: TIERS_DOUBLE_ID,
  reference: 'CL-0004217',
  displayName: 'SANKARA Aminata',
  kind: 'NATURAL_PERSON',
  countryCode: 'BF',
  birthOrRegistrationDate: '1988-04-12',
  segment: 'Particulier',
  status: 'ACTIVE',
  statusReason: null,
  kycStatus: 'VERIFIED',
  kycLevel: 'STANDARD',
  kycVerifiedOn: '2026-03-12',
  kycReviewDue: '2028-03-12',
  riskRating: 'LOW',
};

export const DOSSIER_DOUBLE: Dossier = {
  partyId: TIERS_DOUBLE_ID,
  reference: 'CL-0004217',
  kind: 'NATURAL_PERSON',
  level: 'STANDARD',
  complete: true,
  missing: [],
  expired: [],
  beneficialOwnersMissing: false,
  unverifiedOwners: [],
  policyDeclared: true,
  summary: 'Dossier complet.',
};

/**
 * Un socle sous contrôle pour les écrans du référentiel client : tout le port
 * implémenté avec un client en règle, chaque test ne redéfinissant que ce qui
 * l'intéresse.
 *
 * Le tiers par défaut est ouvrable — c'est le cas rare en production et le cas
 * utile en test : on part de « rien ne bloque » et on ajoute l'obstacle qu'on
 * veut voir apparaître.
 */
export class ClientsDouble implements Clients {
  readonly creations: DemandeTiers[] = [];
  readonly ouvertures: DemandeOuverture[] = [];
  readonly clesOuverture: string[] = [];
  readonly recherches: { q: string; page: number }[] = [];

  tiers: Tiers = TIERS_DOUBLE;
  dossierRendu: Dossier = DOSSIER_DOUBLE;
  piecesRendues: readonly Piece[] = [];
  beneficiairesRendus: readonly BeneficiaireEffectif[] = [];
  resultats: readonly Tiers[] = [TIERS_DOUBLE];
  suivant = false;
  produitsRendus: readonly { code: string; libelle: string }[] = [];

  /** Ce que rend l'ouverture. Un test la remplace pour lever un refus. */
  issueOuverture: () => Promise<IssueOuverture> = async () => ({ operationId: 'PND-000207' });
  /** Ce que rend la création. Un test la remplace pour lever un refus. */
  issueCreation: () => Promise<Tiers> = async () => this.tiers;

  async chercher(_entite: string, q: string, page: number): Promise<PageTiers> {
    this.recherches.push({ q, page });
    return { tiers: this.resultats, page, precedent: page > 0, suivant: this.suivant };
  }

  async lire(): Promise<Tiers> {
    return this.tiers;
  }

  async dossier(): Promise<Dossier> {
    return this.dossierRendu;
  }

  async pieces(): Promise<readonly Piece[]> {
    return this.piecesRendues;
  }

  async beneficiaires(): Promise<readonly BeneficiaireEffectif[]> {
    return this.beneficiairesRendus;
  }

  async creer(demande: DemandeTiers): Promise<Tiers> {
    this.creations.push(demande);
    return this.issueCreation();
  }

  async ouvrirCompte(demande: DemandeOuverture, cle: string): Promise<IssueOuverture> {
    this.ouvertures.push(demande);
    this.clesOuverture.push(cle);
    return this.issueOuverture();
  }

  async produits(): Promise<readonly { code: string; libelle: string }[]> {
    return this.produitsRendus;
  }
}
