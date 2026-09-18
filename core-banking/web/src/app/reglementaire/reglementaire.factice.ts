import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { EnAttente, Reglementaire } from './reglementaire.port';
import {
  Declaration, DemandeDeclaration, DemandeRegleFiscale, DemandeTransmission, DossierEtat,
  Echeance, Etat, RegleFiscale, StatutEtat,
} from './modele/reglementaire.modele';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

/**
 * Un réglementaire de démonstration.
 *
 * Il **refuse comme le socle refuse** : un état qui porte des anomalies ne se
 * transmet pas, un état transmis ne s'annule pas, une annulation sans motif est
 * rejetée. Une démonstration plus complaisante que la production apprendrait aux
 * comptables des gestes que la production refusera.
 *
 * Le jeu montre les trois situations qu'un exploitant rencontre : un état
 * transmis et reproductible, un état produit qui attend son dépôt, et **un état
 * qui porte une anomalie** — celui qu'il faut savoir lire.
 */
@Injectable()
export class ReglementaireFactice implements Reglementaire {
  private readonly declarationsInternes: Declaration[] = [
    { id: 'dec-situation', code: 'SIT-COMPTA', label: 'Situation comptable mensuelle',
      recipient: 'CENTRAL_BANK', method: 'ACCOUNTING_SITUATION', frequency: 'MONTHLY',
      deadlineDays: 15, thresholdAmount: null, subjectCode: null,
      validFrom: '2026-01-01', validTo: null },
    { id: 'dec-risques', code: 'CR-RISQUES', label: 'Centrale des risques',
      recipient: 'CENTRAL_BANK', method: 'CREDIT_REGISTRY', frequency: 'MONTHLY',
      deadlineDays: 20, thresholdAmount: '5000000', subjectCode: null,
      validFrom: '2026-01-01', validTo: null },
    { id: 'dec-incidents', code: 'INC-PAIE', label: 'Incidents de paiement',
      recipient: 'CENTRAL_BANK', method: 'PAYMENT_INCIDENTS', frequency: 'MONTHLY',
      deadlineDays: 10, thresholdAmount: null, subjectCode: null,
      validFrom: '2026-01-01', validTo: null },
    { id: 'dec-taxes', code: 'TAX-COLL', label: 'Taxes collectées',
      recipient: 'TAX_AUTHORITY', method: 'TAX_COLLECTION', frequency: 'QUARTERLY',
      deadlineDays: 30, thresholdAmount: null, subjectCode: null,
      validFrom: '2026-01-01', validTo: null },
  ];

  private etatsInternes: Etat[] = [
    {
      id: 'et-sit-aout', declarationId: 'dec-situation', declarationCode: 'SIT-COMPTA',
      method: 'ACCOUNTING_SITUATION', subjectCode: null,
      periodStart: '2026-08-01', periodEnd: '2026-08-31', dueOn: '2026-09-15',
      producedOn: '2026-09-03', thresholdUsed: null, lineCount: 214,
      totalAmount: xof(4820640000), status: 'TRANSMITTED', transmittedOn: '2026-09-08',
      transmissionReference: 'BCEAO/2026/08/0417', cancelledOn: null, cancellationReason: null,
      anomalies: [],
      lignes: [
        { subjectKind: 'GL_ACCOUNT', subjectReference: '101', label: 'Caisse',
          amount: xof(84200000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
        { subjectKind: 'GL_ACCOUNT', subjectReference: '251', label: 'Comptes de dépôt',
          amount: xof(3105440000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
        { subjectKind: 'GL_ACCOUNT', subjectReference: '201', label: 'Crédits à la clientèle',
          amount: xof(1631000000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
      ],
    },
    {
      id: 'et-risques-aout', declarationId: 'dec-risques', declarationCode: 'CR-RISQUES',
      method: 'CREDIT_REGISTRY', subjectCode: null,
      periodStart: '2026-08-01', periodEnd: '2026-08-31', dueOn: '2026-09-20',
      producedOn: '2026-09-04', thresholdUsed: '5000000', lineCount: 3,
      totalAmount: xof(41300000), status: 'PRODUCED', transmittedOn: null,
      transmissionReference: null, cancelledOn: null, cancellationReason: null,
      anomalies: [],
      lignes: [
        { subjectKind: 'PARTY', subjectReference: 'CLI-002044', label: 'ETS KABORE & Fils',
          amount: xof(24500000), offBalance: xof(0), classification: 'SAIN', daysPastDue: 0,
          occurrences: null, detail: null },
        { subjectKind: 'PARTY', subjectReference: 'CLI-004265', label: 'COMPAORE Issa',
          amount: xof(9300000), offBalance: xof(0), classification: 'SURVEILLE',
          daysPastDue: 42, occurrences: null, detail: null },
        { subjectKind: 'PARTY', subjectReference: 'CLI-000417', label: 'SANKARA Aminata',
          amount: xof(7500000), offBalance: xof(0), classification: 'SAIN', daysPastDue: 0,
          occurrences: null, detail: null },
      ],
    },
    {
      // L'état qu'il faut savoir lire : produit, donc visible, mais intransmissible.
      id: 'et-sit-sept', declarationId: 'dec-situation', declarationCode: 'SIT-COMPTA',
      method: 'ACCOUNTING_SITUATION', subjectCode: null,
      periodStart: '2026-09-01', periodEnd: '2026-09-30', dueOn: '2026-10-15',
      producedOn: '2026-09-17', thresholdUsed: null, lineCount: 211,
      totalAmount: xof(4903180000), status: 'PRODUCED', transmittedOn: null,
      transmissionReference: null, cancelledOn: null, cancellationReason: null,
      anomalies: [
        'Balance déséquilibrée de 12 400 XOF entre le débit et le crédit : '
        + 'la situation ne se tient pas.',
        'Compte 3821 non affecté à une rubrique de la maquette : son solde n’entre nulle part.',
      ],
      lignes: [
        { subjectKind: 'GL_ACCOUNT', subjectReference: '101', label: 'Caisse',
          amount: xof(79840000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
        { subjectKind: 'GL_ACCOUNT', subjectReference: '3821', label: 'Compte d’attente',
          amount: xof(12400), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: 'non affecté' },
      ],
    },
  ];

  private readonly reglesInternes: RegleFiscale[] = [
    { id: 'tx-irc', code: 'IRC', label: 'Impôt sur le revenu des créances',
      basis: 'INTEREST_PAID', ratePercent: '15', collectionAccountId: 'gl-4451',
      validFrom: '2026-01-01', validTo: null },
    { id: 'tx-taf', code: 'TAF', label: 'Taxe sur les activités financières',
      basis: 'FEES_CHARGED', ratePercent: '17', collectionAccountId: 'gl-4452',
      validFrom: '2026-01-01', validTo: null },
  ];

  async echeances(): Promise<readonly Echeance[]> {
    await this.delai();
    return [
      // Produite mais pas partie : le retard qui se voit le moins, et qui compte.
      { declarationCode: 'CR-RISQUES', periodEnd: '2026-07-31', dueOn: '2026-08-20',
        produced: true },
      // Rien n'existe encore : c'est la ligne qui demande un geste.
      { declarationCode: 'INC-PAIE', periodEnd: '2026-08-31', dueOn: '2026-09-10',
        produced: false },
      { declarationCode: 'TAX-COLL', periodEnd: '2026-06-30', dueOn: '2026-07-30',
        produced: false },
    ];
  }

  async declarations(): Promise<readonly Declaration[]> {
    await this.delai();
    return [...this.declarationsInternes];
  }

  async declarer(_legalEntityId: string, demande: DemandeDeclaration,
                 _cle: string): Promise<EnAttente> {
    await this.delai();
    if (this.declarationsInternes.some((d) => d.code === demande.code)) {
      throw new RefusMetier(409, 'CODE_DEJA_PRIS',
                            'Une déclaration porte déjà ce code : ' + demande.code);
    }
    return { operationId: 'op-declaration-' + demande.code };
  }

  async produire(_legalEntityId: string, declarationId: string, periodEnd: string,
                 _cle: string): Promise<Etat> {
    await this.delai();
    const declaration = this.declarationsInternes.find((d) => d.id === declarationId);
    if (!declaration) {
      throw new RefusMetier(404, 'DECLARATION_INCONNUE', 'Déclaration inconnue.');
    }
    if (!finDePeriode(periodEnd, declaration.frequency)) {
      throw new RefusMetier(422, 'PERIODE_INCOMPLETE',
        `Le ${periodEnd} ne ferme pas de période ${declaration.frequency} : un état se produit `
        + 'sur la période que le superviseur attend, pas sur un intervalle choisi.');
    }
    const existant = this.etatsInternes.find(
      (e) => e.declarationId === declarationId && e.periodEnd === periodEnd
             && e.status !== 'CANCELLED');
    if (existant) {
      throw new RefusMetier(409, 'ETAT_DEJA_PRODUIT',
        'Un état existe déjà pour cette période. Deux états transmis pour le même mois seraient '
        + 'deux déclarations contradictoires : annulez le précédent en le motivant.');
    }
    const etat: Etat = {
      id: 'et-' + declaration.code + '-' + periodEnd, declarationId,
      declarationCode: declaration.code, method: declaration.method,
      subjectCode: declaration.subjectCode, periodStart: debutDePeriode(periodEnd,
                                                                       declaration.frequency),
      periodEnd, dueOn: echeance(periodEnd, declaration.deadlineDays ?? 15),
      producedOn: '2026-09-18', thresholdUsed: declaration.thresholdAmount, lineCount: 2,
      totalAmount: xof(1200000), status: 'PRODUCED', transmittedOn: null,
      transmissionReference: null, cancelledOn: null, cancellationReason: null, anomalies: [],
      lignes: [
        { subjectKind: 'GL_ACCOUNT', subjectReference: '101', label: 'Caisse',
          amount: xof(800000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
        { subjectKind: 'GL_ACCOUNT', subjectReference: '251', label: 'Comptes de dépôt',
          amount: xof(400000), offBalance: null, classification: null, daysPastDue: null,
          occurrences: null, detail: null },
      ],
    };
    this.etatsInternes = [etat, ...this.etatsInternes];
    return etat;
  }

  async etats(_legalEntityId: string, statut: StatutEtat | null): Promise<readonly Etat[]> {
    await this.delai();
    const tous = [...this.etatsInternes];
    return statut ? tous.filter((e) => e.status === statut) : tous;
  }

  async etat(_legalEntityId: string, filingId: string): Promise<DossierEtat> {
    await this.delai();
    const etat = this.requiert(filingId);
    // Le recalcul n'a de sens que sur un état transmis : c'est celui-là qu'on
    // doit pouvoir reproduire devant l'inspection.
    return { etat, ecarts: [] };
  }

  async transmettre(_legalEntityId: string, filingId: string, demande: DemandeTransmission,
                    _cle: string): Promise<EnAttente> {
    await this.delai();
    const etat = this.requiert(filingId);
    if (etat.status !== 'PRODUCED') {
      throw new RefusMetier(409, 'ETAT_NON_TRANSMISSIBLE',
        `Cet état est ${etat.status === 'TRANSMITTED' ? 'déjà transmis' : 'annulé'} : `
        + 'il ne se transmet pas deux fois.');
    }
    if (etat.anomalies.length > 0) {
      throw new RefusMetier(422, 'ETAT_EN_ANOMALIE',
        `Cet état porte ${etat.anomalies.length} anomalie(s) : ${etat.anomalies[0]} `
        + 'On ne déclare pas des comptes dont on sait qu’ils sont faux : corriger, reprendre '
        + 'l’état, puis transmettre.');
    }
    if (demande.reference.trim() === '') {
      throw new RefusMetier(400, 'REFERENCE_ABSENTE',
        'La transmission porte la référence rendue par le destinataire.');
    }
    // Comme le socle : rien ne part tant qu'un second regard n'a pas approuvé.
    return { operationId: 'op-transmission-' + filingId };
  }

  async annuler(_legalEntityId: string, filingId: string, motif: string,
                _cle: string): Promise<Etat> {
    await this.delai();
    const etat = this.requiert(filingId);
    if (motif.trim() === '') {
      throw new RefusMetier(400, 'MOTIF_ABSENT', 'L’annulation d’un état porte son motif.');
    }
    if (etat.status !== 'PRODUCED') {
      throw new RefusMetier(409, 'ETAT_NON_ANNULABLE', etat.status === 'TRANSMITTED'
        ? 'Ce qui est transmis ne s’annule pas : il se rectifie par un dépôt suivant.'
        : 'Cet état est déjà annulé.');
    }
    const annule: Etat = {
      ...etat, status: 'CANCELLED', cancelledOn: '2026-09-18', cancellationReason: motif,
    };
    this.etatsInternes = this.etatsInternes.map((e) => (e.id === filingId ? annule : e));
    return annule;
  }

  async reglesFiscales(): Promise<readonly RegleFiscale[]> {
    await this.delai();
    return [...this.reglesInternes];
  }

  async declarerRegleFiscale(_legalEntityId: string, demande: DemandeRegleFiscale,
                             _cle: string): Promise<EnAttente> {
    await this.delai();
    if (this.reglesInternes.some((r) => r.code === demande.code)) {
      throw new RefusMetier(409, 'CODE_DEJA_PRIS',
                            'Une taxe porte déjà ce code : ' + demande.code);
    }
    return { operationId: 'op-taxe-' + demande.code };
  }

  private requiert(filingId: string): Etat {
    const etat = this.etatsInternes.find((e) => e.id === filingId);
    if (!etat) throw new RefusMetier(404, 'ETAT_INCONNU', 'État inconnu.');
    return etat;
  }

  private delai(): Promise<void> {
    return new Promise((resoudre) => setTimeout(resoudre, 90));
  }
}

// --------------------------------------------------------------- calendrier

/** Le dernier jour du mois de cette date ISO. */
function finDeMois(iso: string): string {
  const [a, m] = iso.split('-').map(Number);
  const dernier = new Date(Date.UTC(a, m, 0)).getUTCDate();
  return `${a}-${String(m).padStart(2, '0')}-${String(dernier).padStart(2, '0')}`;
}

/** Cette date ferme-t-elle une période de cette fréquence ? */
function finDePeriode(iso: string, frequence: string): boolean {
  if (iso !== finDeMois(iso)) return false;
  const mois = Number(iso.split('-')[1]);
  if (frequence === 'QUARTERLY') return mois % 3 === 0;
  if (frequence === 'YEARLY') return mois === 12;
  return true;
}

function debutDePeriode(iso: string, frequence: string): string {
  const [a, m] = iso.split('-').map(Number);
  const mois = frequence === 'YEARLY' ? 1 : frequence === 'QUARTERLY' ? m - 2 : m;
  return `${a}-${String(mois).padStart(2, '0')}-01`;
}

function echeance(periodEnd: string, jours: number): string {
  const date = new Date(periodEnd + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() + jours);
  return date.toISOString().slice(0, 10);
}
