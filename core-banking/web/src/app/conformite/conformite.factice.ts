import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Conformite, EnAttente } from './conformite.port';
import {
  Alerte, Declaration, DemandeDeclaration, DemandeScenario, DemandeTransmission, Scenario,
  StatutAlerte,
} from './modele/conformite.modele';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

/**
 * Une conformité de démonstration.
 *
 * Elle **refuse comme le socle refuse** : une déclaration qui mêle deux tiers,
 * une alerte déjà déclarée, un classement sans motif. Une démonstration plus
 * complaisante que la production apprendrait aux opérateurs des gestes que la
 * production refusera — et c'est en formation que les mauvais réflexes se
 * prennent.
 *
 * Les alertes portent des faits plausibles pour la zone : des versements
 * d'espèces fractionnés sous le seuil de déclaration, un compte dormant qui se
 * réveille, une correspondance de filtrage sur un homonyme.
 */
@Injectable()
export class ConformiteFactice implements Conformite {
  private alertesInternes: Alerte[] = [
    {
      id: 'al-especes', partyId: 'p-kabore-ets', scenarioCode: 'ESP-5M', origin: 'MONITORING',
      raisedOn: '2026-09-15',
      detail: 'Espèces cumulées 7 250 000 XOF sur 30 jours, seuil 5 000 000 XOF.',
      amount: xof(7250000), status: 'OPEN', assignedTo: null, closedOn: null,
      closureReason: null, closedBy: null, reportId: null,
      pieces: [
        { entryId: 'e-1', bookingDate: '2026-09-02', accountId: 'ac-kabore-courant', direction: 'CREDIT',
          amount: xof(2400000) },
        { entryId: 'e-2', bookingDate: '2026-09-08', accountId: 'ac-kabore-courant', direction: 'CREDIT',
          amount: xof(2450000) },
        { entryId: 'e-3', bookingDate: '2026-09-14', accountId: 'ac-kabore-courant', direction: 'CREDIT',
          amount: xof(2400000) },
      ],
    },
    {
      id: 'al-fractionnement', partyId: 'p-kabore-ets', scenarioCode: 'FRACT-3',
      origin: 'MONITORING',
      raisedOn: '2026-09-16',
      detail: '3 opérations sous le seuil en 10 jours, somme 7 250 000 XOF.',
      amount: xof(7250000), status: 'UNDER_REVIEW', assignedTo: 'u-conformite',
      closedOn: null, closureReason: null, closedBy: null, reportId: null,
      pieces: [
        { entryId: 'e-1', bookingDate: '2026-09-02', accountId: 'ac-kabore-courant', direction: 'CREDIT',
          amount: xof(2400000) },
        { entryId: 'e-2', bookingDate: '2026-09-08', accountId: 'ac-kabore-courant', direction: 'CREDIT',
          amount: xof(2450000) },
      ],
    },
    {
      id: 'al-filtrage', partyId: 'p-traore', scenarioCode: null, origin: 'SCREENING',
      raisedOn: '2026-09-17',
      detail: 'Correspondance nom et date de naissance avec une liste de sanctions.',
      amount: null, status: 'OPEN', assignedTo: null, closedOn: null, closureReason: null,
      closedBy: null, reportId: null, pieces: [],
    },
    {
      id: 'al-dormant', partyId: 'p-nikiema', scenarioCode: 'DORM-1', origin: 'MONITORING',
      raisedOn: '2026-08-28', detail: 'Compte dormant depuis 14 mois, mouvementé de 1 800 000 XOF.',
      amount: xof(1800000), status: 'CLOSED', assignedTo: 'u-conformite',
      closedOn: '2026-09-01',
      closureReason: 'Vente d’un véhicule justifiée par acte de cession au dossier.',
      closedBy: 'u-conformite', reportId: null, pieces: [],
    },
  ];

  private declarationsInternes: Declaration[] = [
    {
      id: 'ds-ouedraogo', partyId: 'p-ouedraogo', reference: 'DS-2026-0007',
      draftedOn: '2026-08-12',
      narrative: 'Dépôts d’espèces répétés sans rapport avec l’activité déclarée, suivis de '
        + 'virements immédiats vers un compte tiers à l’étranger.',
      transmittedOn: '2026-08-14', transmissionReference: 'CENTIF/2026/1184',
      alertIds: ['al-ouedraogo'],
    },
    // Rédigée, pas encore déposée : c'est l'état qui demande une action, et
    // celui qu'une file de conformité doit rendre visible d'un coup d'œil.
    {
      id: 'ds-compaore', partyId: 'p-compaore', reference: 'DS-2026-0011',
      draftedOn: '2026-09-16',
      narrative: 'Trois virements reçus de contreparties sans lien connu avec le titulaire, '
        + 'retirés en espèces le jour même.',
      transmittedOn: null, transmissionReference: null,
      alertIds: ['al-compaore-1', 'al-compaore-2'],
    },
  ];

  private scenariosInternes: Scenario[] = [
    { id: 'sc-1', code: 'ESP-5M', label: 'Espèces au-delà de 5 000 000 sur 30 jours',
      method: 'CASH_THRESHOLD', thresholdAmount: '5000000', windowDays: 30, minimumCount: null,
      ratio: null, riskRating: null, validFrom: '2026-01-01', validTo: null },
    { id: 'sc-2', code: 'FRACT-3', label: 'Fractionnement : 3 opérations sous le seuil',
      method: 'STRUCTURING', thresholdAmount: '5000000', windowDays: 10, minimumCount: 3,
      ratio: null, riskRating: null, validFrom: '2026-01-01', validTo: null },
    { id: 'sc-3', code: 'ATYP-3X', label: 'Flux trois fois supérieurs au profil déclaré',
      method: 'ATYPICAL_ACTIVITY', thresholdAmount: null, windowDays: 30, minimumCount: null,
      ratio: '3', riskRating: 'HIGH', validFrom: '2026-03-01', validTo: null },
    { id: 'sc-4', code: 'DORM-1', label: 'Réveil d’un compte dormant au-delà de 1 000 000',
      method: 'DORMANT_REACTIVATION', thresholdAmount: '1000000', windowDays: null,
      minimumCount: null, ratio: null, riskRating: null, validFrom: '2026-01-01', validTo: null },
  ];

  async alertes(_legalEntityId: string, statut: StatutAlerte | null): Promise<readonly Alerte[]> {
    await this.delai();
    return statut ? this.alertesInternes.filter((a) => a.status === statut)
                  : [...this.alertesInternes];
  }

  async alerte(_legalEntityId: string, alertId: string): Promise<Alerte> {
    await this.delai();
    return this.requiert(alertId);
  }

  async prendreEnCharge(_legalEntityId: string, alertId: string, _cle: string): Promise<Alerte> {
    await this.delai();
    const alerte = this.requiert(alertId);
    if (alerte.status === 'CLOSED' || alerte.status === 'REPORTED') {
      throw new RefusMetier(409, 'ALERTE_FERMEE',
                            'Cette alerte est fermée : elle ne se reprend pas en charge.');
    }
    return this.remplacer({ ...alerte, status: 'UNDER_REVIEW', assignedTo: 'u-demonstration' });
  }

  async classer(_legalEntityId: string, alertId: string, motif: string,
                _cle: string): Promise<Alerte> {
    await this.delai();
    const alerte = this.requiert(alertId);
    if (motif.trim() === '') {
      throw new RefusMetier(400, 'MOTIF_ABSENT', 'Le classement d’une alerte porte son motif.');
    }
    if (alerte.status === 'CLOSED' || alerte.status === 'REPORTED') {
      throw new RefusMetier(409, 'ALERTE_FERMEE', 'Cette alerte est déjà fermée.');
    }
    return this.remplacer({
      ...alerte, status: 'CLOSED', closedOn: '2026-09-18', closureReason: motif,
      closedBy: 'u-demonstration',
    });
  }

  async declarations(): Promise<readonly Declaration[]> {
    await this.delai();
    return [...this.declarationsInternes];
  }

  async rediger(_legalEntityId: string, demande: DemandeDeclaration,
                _cle: string): Promise<EnAttente> {
    await this.delai();
    if (demande.alertIds.length === 0) {
      throw new RefusMetier(400, 'ALERTES_ABSENTES',
        'Une déclaration cite les alertes qu’elle couvre.');
    }
    for (const id of demande.alertIds) {
      const alerte = this.requiert(id);
      if (alerte.partyId !== demande.partyId) {
        throw new RefusMetier(409, 'DECLARATION_MELANGEE',
          'Une alerte citée ne porte pas sur le tiers déclaré : une déclaration ne mélange pas '
          + 'deux dossiers.');
      }
      if (alerte.status === 'REPORTED') {
        throw new RefusMetier(409, 'ALERTE_DEJA_DECLAREE',
          'Cette alerte est déjà couverte par une déclaration : deux dossiers pour un seul fait.');
      }
    }
    // Comme le socle : rien ne change tant qu'un second regard n'a pas approuvé.
    // Les alertes ne passent à « déclarée » qu'à ce moment-là.
    return { operationId: 'op-declaration-' + demande.reference };
  }

  async transmettre(_legalEntityId: string, reportId: string, demande: DemandeTransmission,
                    _cle: string): Promise<Declaration> {
    await this.delai();
    const index = this.declarationsInternes.findIndex((d) => d.id === reportId);
    if (index < 0) {
      throw new RefusMetier(404, 'DECLARATION_INCONNUE', 'Déclaration inconnue.');
    }
    const declaration: Declaration = {
      ...this.declarationsInternes[index],
      transmittedOn: demande.transmittedOn ?? '2026-09-18',
      transmissionReference: demande.reference,
    };
    this.declarationsInternes = this.declarationsInternes.map(
      (d, i) => (i === index ? declaration : d));
    return declaration;
  }

  async scenarios(): Promise<readonly Scenario[]> {
    await this.delai();
    return [...this.scenariosInternes];
  }

  async declarerScenario(_legalEntityId: string, demande: DemandeScenario,
                         _cle: string): Promise<EnAttente> {
    await this.delai();
    if (this.scenariosInternes.some((s) => s.code === demande.code)) {
      throw new RefusMetier(409, 'CODE_DEJA_PRIS',
        'Un scénario porte déjà ce code : ' + demande.code);
    }
    return { operationId: 'op-scenario-' + demande.code };
  }

  private requiert(alertId: string): Alerte {
    const alerte = this.alertesInternes.find((a) => a.id === alertId);
    if (!alerte) throw new RefusMetier(404, 'ALERTE_INCONNUE', 'Alerte inconnue.');
    return alerte;
  }

  private remplacer(alerte: Alerte): Alerte {
    this.alertesInternes = this.alertesInternes.map((a) => (a.id === alerte.id ? alerte : a));
    return alerte;
  }

  private delai(): Promise<void> {
    return new Promise((resoudre) => setTimeout(resoudre, 90));
  }
}
