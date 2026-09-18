import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
  Segment,
} from '../modele/etablissement.modele';
import {
  CompteGeneral, EnteteVersion, FamilleProduit, Tranche, VersionComplete, VersionProduit,
} from '../modele/produits.modele';
import { FiltreBalance, PageBalance, RunTfj, TotauxBalance } from '../modele/siege.modele';
import { EnAttenteSiege, Siege } from '../siege.port';

/** Une famille taillée au plus court : intérêts obligatoires, agios exigeants dès qu'on y touche. */
export const FAMILLE_DOUBLE: FamilleProduit = {
  code: 'CURRENT_ACCOUNT',
  label: 'Compte courant',
  required: ['interest.day_count', 'interest.credit_account'],
  optional: ['dormancy.months'],
  requireOneOf: [{ of: ['interest.rate', 'tier:INTEREST'], because: 'sans taux ni barème' }],
  conditions: [{
    when: 'overdraft.rate', fallback: null, in: [], presence: true,
    require: ['overdraft.debit_account'], requireTier: null,
    because: 'des agios sans compte d’imputation échoueraient à la première journée débitrice',
  }],
  groups: [],
  accounts: ['interest.credit_account', 'overdraft.debit_account'],
};

export const VERSION_EN_VIGUEUR: VersionProduit = {
  id: 'pv-1', code: 'CPTE-CHQ-PART', productType: 'CURRENT_ACCOUNT',
  label: 'Compte chèque particulier', currency: 'XOF', validFrom: '2026-01-01', validTo: null,
  status: 'ACTIVE', createdBy: 'ADIALLO', createdAt: '2025-12-04T10:12:00Z',
  approvedBy: 'MKONE', approvedAt: '2025-12-05T08:30:00Z',
};

export const VERSION_BROUILLON: VersionProduit = {
  ...VERSION_EN_VIGUEUR, id: 'pv-2', code: 'EPARGNE-PART', label: 'Épargne particulier',
  validFrom: '2027-01-01', status: 'DRAFT', approvedBy: null, approvedAt: null,
};

export const PARAMETRES_DOUBLE: Readonly<Record<string, string>> = {
  'interest.day_count': 'ACT_365',
  'interest.credit_account': 'gl-1',
  'interest.rate': '3',
};

export const COMPTE_GENERAL_DOUBLE: CompteGeneral = {
  id: 'gl-1', code: '378100', kind: 'GL', normalBalance: 'CREDIT', currency: 'XOF',
  nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true,
};

export const ETABLISSEMENT_DOUBLE: Etablissement = {
  id: 'e-1', code: 'BANQUE-TEST', name: 'Banque de test', countryCode: 'BF',
  functionalCurrency: 'XOF', businessDate: '2026-09-18', status: 'ACTIVE',
  bankCode: '10015', legalName: 'Banque de test SA', approvalNumber: 'AGR-BF-2019-014',
  taxId: '00012345 A', registryNumber: 'RCCM BF-OUA-2019-B-0142',
  address: '01 BP 1420 Ouagadougou 01', phone: '+226 25 30 12 40',
  email: 'contact@banque-test.example',
};

function segment(kind: Segment['kind'], reglages: Partial<Segment> = {}): Segment {
  return {
    kind, literalValue: null, length: null, padChar: null, datePattern: null, algorithm: null,
    ...reglages,
  };
}

export const REGLE_COMPTE: RegleNumerotation = {
  id: 'r-account', domain: 'ACCOUNT', label: 'Numéro de compte (RIB)',
  segments: [segment('BANK_CODE', { length: 5, padChar: '0' }),
             segment('BRANCH_CODE', { length: 5, padChar: '0' }),
             segment('SEQUENCE', { length: 12, padChar: '0' }),
             segment('CHECK_DIGITS', { length: 2, padChar: '0', algorithm: 'RIB_97' })],
  scope: 'BRANCH', reset: 'NEVER', sequenceStart: 1, status: 'ACTIVE',
};

export const REGLE_CLIENT_BROUILLON: RegleNumerotation = {
  id: 'r-party-draft', domain: 'PARTY', label: 'Référence client — nouveau plan',
  segments: [segment('LITERAL', { literalValue: 'CLI-' }),
             segment('SEQUENCE', { length: 8, padChar: '0' })],
  scope: 'ENTITY', reset: 'NEVER', sequenceStart: 1, status: 'DRAFT',
};

/**
 * Le double du port du siège.
 *
 * Il rend ce qu'il faut pour que les écrans se montent, et laisse chaque spec
 * redéfinir ce qui l'intéresse. Une classe plutôt qu'un objet : une spec qui
 * ne couvre qu'une méthode n'a pas à réécrire les onze autres, et l'ajout
 * d'une méthode au port ne casse pas les spécifications qui l'ignorent.
 */
export class SiegeDouble implements Siege {
  async lancerTfj(legalEntityId: string, journee: string,
                  mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj> {
    throw new Error(`non attendu : lancerTfj ${legalEntityId} ${journee} ${mode}`);
  }

  async lireTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    throw new Error(`non attendu : lireTfj ${legalEntityId} ${runId}`);
  }

  async reprendreTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    throw new Error(`non attendu : reprendreTfj ${legalEntityId} ${runId}`);
  }

  async annulerTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    throw new Error(`non attendu : annulerTfj ${legalEntityId} ${runId}`);
  }

  async balance(legalEntityId: string, filtre: FiltreBalance, page: number,
                taille: number): Promise<PageBalance> {
    return { lignes: [], numero: page, taille, precedent: false, suivant: false };
  }

  async totauxBalance(legalEntityId: string,
                      filtre: FiltreBalance): Promise<readonly TotauxBalance[]> {
    return [];
  }

  async etablissement(legalEntityId: string): Promise<Etablissement> {
    return { ...ETABLISSEMENT_DOUBLE, id: legalEntityId };
  }

  async majEtablissement(legalEntityId: string, demande: DemandeEtablissement,
                         cleIdempotence?: string): Promise<EnAttenteSiege> {
    this.derniereCorrection = demande;
    return { operationId: 'op-etab' };
  }

  async regles(legalEntityId: string): Promise<readonly RegleNumerotation[]> {
    return [REGLE_COMPTE, REGLE_CLIENT_BROUILLON];
  }

  async proposition(legalEntityId: string,
                    domaine: DomaineNumerotation): Promise<DemandeRegle> {
    return {
      domain: domaine, label: 'Proposition', segments: [segment('SEQUENCE', { length: 6 })],
      sequenceScope: 'ENTITY', sequenceReset: 'NEVER', sequenceStart: 1,
    };
  }

  async redigerRegle(legalEntityId: string, demande: DemandeRegle,
                     cleIdempotence?: string): Promise<{ id: string }> {
    this.derniereRegle = demande;
    return { id: 'r-neuve' };
  }

  async activerRegle(legalEntityId: string, ruleId: string,
                     cleIdempotence?: string): Promise<EnAttenteSiege> {
    this.derniereActivation = ruleId;
    return { operationId: 'op-regle' };
  }

  // -------------------------------------------------------------- paramétrage produit

  async familles(): Promise<readonly FamilleProduit[]> {
    return [FAMILLE_DOUBLE];
  }

  async versions(legalEntityId: string, code: string | null,
                 statut: string | null): Promise<readonly VersionProduit[]> {
    this.dernierFiltre = { code, statut };
    return this.versionsRendues.filter(
      (v) => (code === null || v.code === code) && (statut === null || v.status === statut));
  }

  async version(legalEntityId: string, versionId: string): Promise<VersionComplete> {
    const header = this.versionsRendues.find((v) => v.id === versionId) ?? VERSION_EN_VIGUEUR;
    return {
      header,
      parameters: header.id === VERSION_EN_VIGUEUR.id ? PARAMETRES_DOUBLE : {},
      tiers: header.id === VERSION_EN_VIGUEUR.id
        ? { INTEREST: [{ from: '0', to: null, annualRatePercent: '3' }] }
        : {},
    };
  }

  async redigerVersion(legalEntityId: string, entete: EnteteVersion,
                       parametres: Readonly<Record<string, string>>,
                       baremes: Readonly<Record<string, readonly Tranche[]>>,
                       ): Promise<{ readonly id: string }> {
    this.derniereVersion = { entete, parametres, baremes };
    return { id: 'pv-neuve' };
  }

  async activerVersion(legalEntityId: string, versionId: string): Promise<EnAttenteSiege> {
    this.derniereActivationProduit = versionId;
    return { operationId: 'op-produit' };
  }

  async fermerVersion(legalEntityId: string, versionId: string,
                      validTo: string): Promise<EnAttenteSiege> {
    this.derniereFermeture = { versionId, validTo };
    return { operationId: 'op-produit-fermeture' };
  }

  async retirerVersion(legalEntityId: string, versionId: string): Promise<VersionProduit> {
    this.dernierRetrait = versionId;
    return { ...VERSION_BROUILLON, id: versionId, status: 'WITHDRAWN' };
  }

  async comptesGeneraux(legalEntityId: string, texte: string): Promise<readonly CompteGeneral[]> {
    this.derniereQuete = texte;
    return [COMPTE_GENERAL_DOUBLE];
  }

  versionsRendues: readonly VersionProduit[] = [VERSION_EN_VIGUEUR, VERSION_BROUILLON];

  /** Ce que la spec vient vérifier : le poste a-t-il envoyé ce qu'il fallait ? */
  derniereCorrection: DemandeEtablissement | null = null;
  derniereRegle: DemandeRegle | null = null;
  derniereActivation: string | null = null;
  derniereVersion: {
    entete: EnteteVersion;
    parametres: Readonly<Record<string, string>>;
    baremes: Readonly<Record<string, readonly Tranche[]>>;
  } | null = null;
  derniereActivationProduit: string | null = null;
  derniereFermeture: { versionId: string; validTo: string } | null = null;
  dernierRetrait: string | null = null;
  dernierFiltre: { code: string | null; statut: string | null } | null = null;
  derniereQuete: string | null = null;
}
