import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
  Segment,
} from '../modele/etablissement.modele';
import {
  CompteGeneral, EnteteVersion, FamilleProduit, Tranche, VersionComplete, VersionProduit,
} from '../modele/produits.modele';
import {
  Agence, ConditionsDeBanque, DemandeAgence, DemandeFerie, DemandeHeureLimite,
  DemandeRegleDateValeur,
} from '../modele/reseau.modele';
import { CATALOGUE_DEMONSTRATION } from '../modele/schemas.demonstration';
import { essaiDeDemonstration } from '../modele/schemas.essai-demonstration';
import {
  DemandeFermetureSchema, EnteteSchema, Essai, EvenementSocle, LigneSaisie, SchemaComplet,
  SchemaComptable,
} from '../modele/schemas.modele';
import { FiltreBalance, PageBalance, RunTfj, TotauxBalance } from '../modele/siege.modele';

/** Deux schémas : un brouillon qu'on peut encore activer, un en vigueur qu'on ne peut que fermer. */
export const SCHEMAS_DOUBLE: readonly SchemaComptable[] = [
  { id: 'sc-1', code: 'FRAIS-TENUE', label: 'Frais de tenue de compte', currency: 'XOF',
    validFrom: '2026-10-01', validTo: null, status: 'DRAFT', createdBy: null,
    createdAt: '2026-09-15T09:12:00Z', approvedBy: null, approvedAt: null,
    withdrawnBy: null, withdrawnAt: null },
  { id: 'sc-2', code: 'FRAIS-CARTE', label: 'Frais de carte', currency: 'XOF',
    validFrom: '2026-01-01', validTo: null, status: 'ACTIVE', createdBy: null,
    createdAt: '2025-12-20T10:00:00Z', approvedBy: null, approvedAt: '2025-12-21T08:00:00Z',
    withdrawnBy: null, withdrawnAt: null },
];

export const RESEAU_DOUBLE: readonly Agence[] = [
  { id: 'siege', code: 'SIEGE', name: 'Siège', kind: 'HEAD_OFFICE', parentId: null,
    status: 'ACTIVE', openedOn: '2014-01-02', closedOn: null },
  { id: 'ag-1', code: '00021', name: 'Ouagadougou Gounghin', kind: 'BRANCH', parentId: 'siege',
    status: 'ACTIVE', openedOn: '2019-06-17', closedOn: null },
];

export const CONDITIONS_DOUBLE: ConditionsDeBanque = {
  calendarCode: 'BF', calendarLabel: 'Jours ouvrés — Burkina Faso', coversFrom: '2026-01-01',
  coversTo: '2026-12-31', weekend: [6, 7],
  holidays: [{ date: '2026-08-05', label: 'Fête nationale' }],
  rules: [{ id: 'vd-1', operationType: 'TRANSFER', channel: 'CLEARING', direction: 'CREDIT',
            offset: 2, unit: 'BUSINESS_DAYS', convention: 'FOLLOWING', validFrom: '2026-01-01',
            validTo: null }],
  cutoffs: [{ id: 'co-1', channel: 'CLEARING', cutoffTime: '14:30', closesChannel: false,
              validFrom: '2026-01-01', validTo: null }],
};
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

  // -------------------------------------------------------------- réseau et calendrier

  async agences(): Promise<readonly Agence[]> {
    return RESEAU_DOUBLE;
  }

  async creerAgence(legalEntityId: string, demande: DemandeAgence): Promise<EnAttenteSiege> {
    this.derniereAgence = demande;
    return { operationId: 'op-agence' };
  }

  async conditions(): Promise<ConditionsDeBanque> {
    return CONDITIONS_DOUBLE;
  }

  async ajouterFerie(legalEntityId: string, demande: DemandeFerie): Promise<EnAttenteSiege> {
    this.dernierFerie = demande;
    return { operationId: 'op-ferie' };
  }

  async ajouterRegle(legalEntityId: string,
                     demande: DemandeRegleDateValeur): Promise<EnAttenteSiege> {
    this.derniereRegleValeur = demande;
    return { operationId: 'op-regle-valeur' };
  }

  async ajouterHeureLimite(legalEntityId: string,
                           demande: DemandeHeureLimite): Promise<EnAttenteSiege> {
    this.derniereHeure = demande;
    return { operationId: 'op-heure' };
  }

  // ------------------------------------------------------------ schémas comptables

  catalogue: readonly EvenementSocle[] = CATALOGUE_DEMONSTRATION;
  listeSchemas: readonly SchemaComptable[] = SCHEMAS_DOUBLE;

  async evenementsDuSocle(): Promise<readonly EvenementSocle[]> {
    return this.catalogue;
  }

  async schemas(legalEntityId: string, code: string | null,
                statut: string | null): Promise<readonly SchemaComptable[]> {
    this.dernierFiltreSchema = { code, statut };
    return this.listeSchemas.filter((schema) =>
      (!code || schema.code === code) && (!statut || schema.status === statut));
  }

  async schema(legalEntityId: string, schemaId: string): Promise<SchemaComplet> {
    const entete = this.listeSchemas.find((candidat) => candidat.id === schemaId)
      ?? SCHEMAS_DOUBLE[0]!;
    return {
      header: entete,
      events: [{
        eventType: 'FEE_CHARGE',
        derivations: [{ name: 'net_booked', expression: 'round(net, 0)' },
                      { name: 'tax_booked', expression: 'round(tax, 0)' }],
        lines: [
          { account: 'CONTRACT', direction: 'DEBIT', amount: 'net_booked + tax_booked',
            condition: null, label: 'Frais de tenue' },
          { account: 'PARAM:fee_income', direction: 'CREDIT', amount: 'net_booked',
            condition: null, label: 'Commissions percues' },
          { account: 'PARAM:fee_tax', direction: 'CREDIT', amount: 'tax_booked',
            condition: 'tax_booked > 0', label: 'Taxe collectee' },
        ],
        variables: ['net', 'tax'],
      }],
    };
  }

  async essayer(legalEntityId: string, evenement: string, devise: string | null,
                lignes: readonly LigneSaisie[],
                derivations: readonly (readonly [string, string])[],
                valeurs: Readonly<Record<string, string>>): Promise<Essai> {
    this.dernierEssai = { evenement, lignes, derivations, valeurs };
    return essaiDeDemonstration(evenement, devise === 'EUR' ? 2 : 0, lignes, derivations, valeurs);
  }

  async essayerLeSocle(legalEntityId: string, evenement: string, devise: string | null,
                       valeurs: Readonly<Record<string, string>>): Promise<Essai> {
    const modele = this.catalogue.find((candidat) => candidat.eventType === evenement)
      ?? this.catalogue[0]!;
    return essaiDeDemonstration(evenement, devise === 'EUR' ? 2 : 0,
      modele.lines.map((ligne) => ({
        account: ligne.account, direction: ligne.direction, amount: ligne.amount,
        condition: ligne.condition ?? '', label: ligne.label ?? '',
      })),
      modele.derivations.map((derivation) => [derivation.name, derivation.expression] as const),
      valeurs);
  }

  async redigerSchema(legalEntityId: string, entete: EnteteSchema, lignes: readonly LigneSaisie[],
                      derivations: readonly (readonly [string, string])[]):
                      Promise<{ readonly id: string }> {
    this.dernierSchema = { entete, lignes, derivations };
    return { id: 'sc-neuf' };
  }

  async activerSchema(legalEntityId: string, schemaId: string): Promise<EnAttenteSiege> {
    this.derniereActivationSchema = schemaId;
    return { operationId: 'op-schema' };
  }

  async fermerSchema(legalEntityId: string, schemaId: string,
                     demande: DemandeFermetureSchema): Promise<EnAttenteSiege> {
    this.derniereFermetureSchema = { schemaId, validTo: demande.validTo };
    return { operationId: 'op-fermeture-schema' };
  }

  async retirerSchema(legalEntityId: string, schemaId: string): Promise<void> {
    this.dernierRetraitSchema = schemaId;
  }

  dernierFiltreSchema: { code: string | null; statut: string | null } | null = null;
  dernierEssai: {
    evenement: string;
    lignes: readonly LigneSaisie[];
    derivations: readonly (readonly [string, string])[];
    valeurs: Readonly<Record<string, string>>;
  } | null = null;
  dernierSchema: {
    entete: EnteteSchema;
    lignes: readonly LigneSaisie[];
    derivations: readonly (readonly [string, string])[];
  } | null = null;
  derniereActivationSchema: string | null = null;
  derniereFermetureSchema: { schemaId: string; validTo: string | null } | null = null;
  dernierRetraitSchema: string | null = null;

  derniereAgence: DemandeAgence | null = null;
  dernierFerie: DemandeFerie | null = null;
  derniereRegleValeur: DemandeRegleDateValeur | null = null;
  derniereHeure: DemandeHeureLimite | null = null;

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
