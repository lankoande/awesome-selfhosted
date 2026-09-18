import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import {
  actesSurCheque, Cheque, Chequier, DemandeChequier, DemandeMandat, DemandeOpposition,
  DemandePaiementCheque, IncidentCheque, Mandat, obstaclesAuChequier, obstaclesAuMandat,
  obstaclesAuPaiement, peutRevoquer, StatutCheque,
} from './modele/compte.modele';
import {
  actesSurOrdre, actesSurPrelevement, actesSurRemise, DemandeOrdre, DemandeRemise, OrdrePaiement,
  Prelevement, Remise, SensPrelevement, StatutOrdre, StatutPrelevement, StatutRemise,
} from './modele/paiements.modele';
import { Decision, EnAttentePaiements, Page, Paiements } from './paiements.port';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

/**
 * Trois files, dans les états qui font le métier.
 *
 * On n'y met pas que des cas heureux : un ordre parti qui attend son règlement,
 * un ordre revenu impayé, une remise à l'encaissement, un prélèvement rejeté
 * faute de provision. C'est ce qui remplit une vraie journée de compensation, et
 * c'est ce qu'un écran doit savoir montrer.
 */
const ORDRES: OrdrePaiement[] = [
  {
    id: 'op-0142', accountId: 'cpt-sankara', amount: xof(1250000), fee: xof(2500), tax: xof(450),
    beneficiaryName: 'SANOU Awa', beneficiaryBank: 'BOA Burkina Faso',
    beneficiaryAccount: 'BF7601001000000123456789', reference: 'Loyer septembre',
    channel: 'BRANCH', status: 'ORDERED', orderedOn: '2026-09-18', sentOn: null, settledOn: null,
    returnedOn: null, returnReason: null, cancelledOn: null, cancelReason: null,
  },
  {
    id: 'op-0141', accountId: 'cpt-kabore', amount: xof(8400000), fee: xof(12500), tax: xof(2250),
    beneficiaryName: 'SOCIETE GENERALE FOURNITURES', beneficiaryBank: 'Coris Bank International',
    beneficiaryAccount: 'BF7602002000000998877665', reference: 'Facture FA-2026-0881',
    channel: 'BRANCH', status: 'SENT', orderedOn: '2026-09-17', sentOn: '2026-09-17',
    settledOn: null, returnedOn: null, returnReason: null, cancelledOn: null, cancelReason: null,
  },
  {
    id: 'op-0139', accountId: 'cpt-traore', amount: xof(340000), fee: xof(2500), tax: xof(450),
    beneficiaryName: 'OUEDRAOGO Salif', beneficiaryBank: 'Ecobank Burkina',
    beneficiaryAccount: 'BF7603003000000445566778', reference: 'Scolarité',
    channel: 'BRANCH', status: 'SETTLED', orderedOn: '2026-09-15', sentOn: '2026-09-15',
    settledOn: '2026-09-16', returnedOn: null, returnReason: null, cancelledOn: null,
    cancelReason: null,
  },
  {
    id: 'op-0136', accountId: 'cpt-compaore', amount: xof(2100000), fee: xof(5000), tax: xof(900),
    beneficiaryName: 'ETS ZONGO & Fils', beneficiaryBank: 'BICIA-B',
    beneficiaryAccount: 'BF7604004000000112233445', reference: 'Acompte chantier',
    channel: 'BRANCH', status: 'RETURNED', orderedOn: '2026-09-11', sentOn: '2026-09-11',
    settledOn: null, returnedOn: '2026-09-14',
    returnReason: 'Compte du bénéficiaire clos chez le correspondant', cancelledOn: null,
    cancelReason: null,
  },
];

const REMISES: Remise[] = [
  {
    id: 'rc-0231', accountId: 'cpt-kabore', amount: xof(4200000), draweeBank: 'Ecobank Burkina',
    chequeNumber: '0042118', drawerName: 'SOCIETE MINIERE DU SAHEL', channel: 'BRANCH',
    status: 'DEPOSITED', depositedOn: '2026-09-18', valueDate: '2026-09-22', settledOn: null,
    returnedOn: null, returnReason: null,
  },
  {
    id: 'rc-0230', accountId: 'cpt-sankara', amount: xof(185000), draweeBank: 'Coris Bank International',
    chequeNumber: '0098231', drawerName: 'NIKIEMA Rasmata', channel: 'BRANCH',
    status: 'DEPOSITED', depositedOn: '2026-09-17', valueDate: '2026-09-21', settledOn: null,
    returnedOn: null, returnReason: null,
  },
  {
    id: 'rc-0224', accountId: 'cpt-traore', amount: xof(920000), draweeBank: 'BOA Burkina Faso',
    chequeNumber: '0011477', drawerName: 'ETS TAPSOBA', channel: 'BRANCH', status: 'SETTLED',
    depositedOn: '2026-09-12', valueDate: '2026-09-16', settledOn: '2026-09-16',
    returnedOn: null, returnReason: null,
  },
  {
    id: 'rc-0219', accountId: 'cpt-compaore', amount: xof(640000), draweeBank: 'BICIA-B',
    chequeNumber: '0073902', drawerName: 'SAWADOGO Boukary', channel: 'BRANCH',
    status: 'RETURNED', depositedOn: '2026-09-09', valueDate: '2026-09-11', settledOn: null,
    returnedOn: '2026-09-12', returnReason: 'Provision insuffisante chez la banque tirée',
  },
];

const PRELEVEMENTS: Prelevement[] = [
  {
    id: 'pr-0512', direction: 'RECEIVED', accountId: 'cpt-sankara', mandateId: 'md-0031',
    amount: xof(45000), fee: xof(500), dueDate: '2026-09-20', counterpartyName: 'SONABEL',
    counterpartyBank: null, counterpartyAccount: null, mandateReference: 'MDT-SONABEL-0031',
    reference: 'Facture 09/2026', channel: 'CLEARING', status: 'PENDING',
    presentedOn: '2026-09-16', executedOn: null, valueDate: null, rejectionReason: null,
    settledOn: null, closedOn: null, closeReason: null,
  },
  {
    id: 'pr-0509', direction: 'RECEIVED', accountId: 'cpt-traore', mandateId: 'md-0027',
    amount: xof(28500), fee: xof(500), dueDate: '2026-09-17', counterpartyName: 'ONEA',
    counterpartyBank: null, counterpartyAccount: null, mandateReference: 'MDT-ONEA-0027',
    reference: 'Facture 08/2026', channel: 'CLEARING', status: 'COLLECTED',
    presentedOn: '2026-09-13', executedOn: '2026-09-17', valueDate: '2026-09-17',
    rejectionReason: null, settledOn: null, closedOn: null, closeReason: null,
  },
  {
    id: 'pr-0505', direction: 'RECEIVED', accountId: 'cpt-compaore', mandateId: 'md-0019',
    amount: xof(120000), fee: xof(500), dueDate: '2026-09-15', counterpartyName: 'ASSURANCES SAHEL',
    counterpartyBank: null, counterpartyAccount: null, mandateReference: 'MDT-ASSUR-0019',
    reference: 'Prime trimestrielle', channel: 'CLEARING', status: 'REJECTED',
    presentedOn: '2026-09-11', executedOn: '2026-09-15', valueDate: null,
    rejectionReason: 'Provision insuffisante au jour de l’échéance', settledOn: null,
    closedOn: '2026-09-15', closeReason: null,
  },
  {
    id: 'pr-0498', direction: 'ISSUED', accountId: 'cpt-kabore', mandateId: null,
    amount: xof(1850000), fee: xof(3500), dueDate: '2026-09-16', counterpartyName: 'ZONGO Adama',
    counterpartyBank: 'Ecobank Burkina', counterpartyAccount: 'BF7603003000000778899001',
    mandateReference: 'MDT-KABORE-2211', reference: 'Loyer commercial T3', channel: 'CLEARING',
    status: 'COLLECTED', presentedOn: '2026-09-12', executedOn: '2026-09-16',
    valueDate: '2026-09-16', rejectionReason: null, settledOn: null, closedOn: null,
    closeReason: null,
  },
  {
    id: 'pr-0491', direction: 'RECEIVED', accountId: 'cpt-nikiema', mandateId: 'md-0012',
    amount: xof(15000), fee: xof(500), dueDate: '2026-09-10', counterpartyName: 'ORANGE BF',
    counterpartyBank: null, counterpartyAccount: null, mandateReference: 'MDT-ORANGE-0012',
    reference: 'Abonnement', channel: 'CLEARING', status: 'SETTLED', presentedOn: '2026-09-06',
    executedOn: '2026-09-10', valueDate: '2026-09-10', rejectionReason: null,
    settledOn: '2026-09-11', closedOn: null, closeReason: null,
  },
];

/**
 * Le compte que la démonstration instrumente.
 *
 * Un seul : un jeu qui donnerait des chéquiers à tous les comptes ferait croire
 * que tout compte en a un. Ici, `cpt-sankara` a son carnet, ses chèques et son
 * mandat ; les autres montrent ce que montre un compte sans moyens de paiement,
 * et c'est aussi un état à savoir présenter.
 */
const COMPTE_INSTRUMENTE = 'cpt-sankara';

const CHEQUIERS: Chequier[] = [
  {
    id: 'cb-0044', firstNumber: 4211801, lastNumber: 4211825, deliveredOn: '2026-07-03',
    status: 'ACTIVE', fee: xof(3000),
  },
  {
    id: 'cb-0031', firstNumber: 4208101, lastNumber: 4208125, deliveredOn: '2026-02-11',
    status: 'CANCELLED', fee: xof(3000),
  },
];

const CHEQUES: Cheque[] = [
  {
    number: 4211804, bookId: 'cb-0044', status: 'UNUSED', amount: null, beneficiary: null,
    paidOn: null, stoppedOn: null, stopReason: null,
  },
  {
    number: 4211803, bookId: 'cb-0044', status: 'STOPPED', amount: null, beneficiary: null,
    paidOn: null, stoppedOn: '2026-09-12', stopReason: 'THEFT',
  },
  {
    number: 4211802, bookId: 'cb-0044', status: 'REJECTED', amount: xof(1750000),
    beneficiary: 'ETS ZONGO & Fils', paidOn: null, stoppedOn: null, stopReason: null,
  },
  {
    number: 4211801, bookId: 'cb-0044', status: 'PAID', amount: xof(420000),
    beneficiary: 'SAWADOGO Boukary', paidOn: '2026-08-29', stoppedOn: null, stopReason: null,
  },
];

const INCIDENTS: IncidentCheque[] = [
  {
    id: 'ic-0007', number: 4211802, amount: xof(1750000), occurredOn: '2026-09-05',
    reason: 'Provision insuffisante', presentedBy: 'Coris Bank International',
  },
];

const MANDATS: Mandat[] = [
  {
    id: 'md-0031', reference: 'MDT-SONABEL-0031', creditorId: 'BF-SONABEL-001',
    creditorName: 'SONABEL', creditorAccountId: null,
    creditorBank: 'Coris Bank International', creditorAccount: 'BF7602002000000998877665',
    signedOn: '2025-11-04', validFrom: '2025-12-01', validTo: null, maxAmount: xof(150000),
    status: 'ACTIVE', revokedOn: null, revocationReason: null,
  },
  {
    id: 'md-0012', reference: 'MDT-ORANGE-0012', creditorId: 'BF-ORANGE-004',
    creditorName: 'ORANGE BF', creditorAccountId: null, creditorBank: 'Ecobank Burkina',
    creditorAccount: 'BF7603003000000112233445', signedOn: '2024-06-18',
    validFrom: '2024-07-01', validTo: null, maxAmount: null, status: 'REVOKED',
    revokedOn: '2026-04-30', revocationReason: 'Résiliation de l’abonnement',
  },
];

/**
 * La source de démonstration des moyens de paiement.
 *
 * Elle applique les mêmes gardes que le socle — c'est le sujet : un écran qui
 * n'aurait jamais vu un refus d'état ne saurait pas le présenter.
 */
@Injectable()
export class PaiementsFactice implements Paiements {
  latenceMs = 300;

  private chequiersEnCours: readonly Chequier[] = CHEQUIERS;
  private chequesEnCours: readonly Cheque[] = CHEQUES;
  private mandatsEnCours: readonly Mandat[] = MANDATS;
  private ordresEnCours: readonly OrdrePaiement[] = ORDRES;
  private remisesEnCours: readonly Remise[] = REMISES;
  private prelevementsEnCours: readonly Prelevement[] = PRELEVEMENTS;

  async ordres(legalEntityId: string, statut: StatutOrdre | null, page: number,
               taille: number): Promise<Page<OrdrePaiement>> {
    await this.latence();
    return this.page(this.ordresEnCours.filter((o) => statut === null || o.status === statut),
                     page, taille);
  }

  async ordre(legalEntityId: string, orderId: string): Promise<OrdrePaiement> {
    await this.latence();
    return this.requis(this.ordresEnCours.find((o) => o.id === orderId), 'Ordre de paiement');
  }

  async ordonner(legalEntityId: string, demande: DemandeOrdre): Promise<OrdrePaiement> {
    await this.latence();
    const cree: OrdrePaiement = {
      id: `op-${String(this.ordresEnCours.length + 143).padStart(4, '0')}`,
      accountId: demande.accountId,
      amount: { amount: demande.amount, currency: demande.currency },
      // Les frais viennent du barème du socle. La démonstration en pose un
      // plausible plutôt que rien : un écran qui n'en montrerait jamais
      // laisserait croire qu'un virement est gratuit.
      fee: xof(2500), tax: xof(450),
      beneficiaryName: demande.beneficiaryName, beneficiaryBank: demande.beneficiaryBank,
      beneficiaryAccount: demande.beneficiaryAccount, reference: demande.reference,
      channel: demande.channel, status: 'ORDERED', orderedOn: '2026-09-18', sentOn: null,
      settledOn: null, returnedOn: null, returnReason: null, cancelledOn: null, cancelReason: null,
    };
    this.ordresEnCours = [cree, ...this.ordresEnCours];
    return cree;
  }

  async deciderOrdre(legalEntityId: string, orderId: string,
                     decision: Decision): Promise<OrdrePaiement> {
    await this.latence();
    const ordre = this.requis(this.ordresEnCours.find((o) => o.id === orderId),
                              'Ordre de paiement');
    this.refuserSiHorsEtat(actesSurOrdre(ordre).includes(decision.acte), decision.acte,
                           ordre.status);
    const jour = '2026-09-18';
    const change: Partial<OrdrePaiement> = decision.acte === 'ENVOYER'
      ? { status: 'SENT', sentOn: jour }
      : decision.acte === 'REGLER' ? { status: 'SETTLED', settledOn: jour }
      : decision.acte === 'RETOURNER'
        ? { status: 'RETURNED', returnedOn: jour, returnReason: decision.motif ?? null }
        : { status: 'CANCELLED', cancelledOn: jour, cancelReason: decision.motif ?? null };
    const modifie = { ...ordre, ...change };
    this.ordresEnCours = this.ordresEnCours.map((o) => (o.id === orderId ? modifie : o));
    return modifie;
  }

  async remises(legalEntityId: string, statut: StatutRemise | null, page: number,
                taille: number): Promise<Page<Remise>> {
    await this.latence();
    return this.page(this.remisesEnCours.filter((r) => statut === null || r.status === statut),
                     page, taille);
  }

  async remise(legalEntityId: string, depositId: string): Promise<Remise> {
    await this.latence();
    return this.requis(this.remisesEnCours.find((r) => r.id === depositId), 'Remise de chèque');
  }

  async remettre(legalEntityId: string, demande: DemandeRemise): Promise<Remise> {
    await this.latence();
    const cree: Remise = {
      id: `rc-${String(this.remisesEnCours.length + 232).padStart(4, '0')}`,
      accountId: demande.accountId,
      amount: { amount: demande.amount, currency: demande.currency },
      draweeBank: demande.draweeBank, chequeNumber: demande.chequeNumber,
      drawerName: demande.drawerName, channel: demande.channel, status: 'DEPOSITED',
      depositedOn: '2026-09-18', valueDate: '2026-09-22', settledOn: null, returnedOn: null,
      returnReason: null,
    };
    this.remisesEnCours = [cree, ...this.remisesEnCours];
    return cree;
  }

  async deciderRemise(legalEntityId: string, depositId: string,
                      decision: Decision): Promise<Remise> {
    await this.latence();
    const remise = this.requis(this.remisesEnCours.find((r) => r.id === depositId),
                               'Remise de chèque');
    this.refuserSiHorsEtat(actesSurRemise(remise).includes(decision.acte), decision.acte,
                           remise.status);
    const modifie: Remise = decision.acte === 'REGLER'
      ? { ...remise, status: 'SETTLED', settledOn: '2026-09-18' }
      : { ...remise, status: 'RETURNED', returnedOn: '2026-09-18',
          returnReason: decision.motif ?? null };
    this.remisesEnCours = this.remisesEnCours.map((r) => (r.id === depositId ? modifie : r));
    return modifie;
  }

  async prelevements(legalEntityId: string, sens: SensPrelevement | null,
                     statut: StatutPrelevement | null, page: number,
                     taille: number): Promise<Page<Prelevement>> {
    await this.latence();
    return this.page(this.prelevementsEnCours.filter(
      (p) => (sens === null || p.direction === sens) && (statut === null || p.status === statut)),
      page, taille);
  }

  async prelevement(legalEntityId: string, directDebitId: string): Promise<Prelevement> {
    await this.latence();
    return this.requis(this.prelevementsEnCours.find((p) => p.id === directDebitId),
                       'Prélèvement');
  }

  async deciderPrelevement(legalEntityId: string, directDebitId: string,
                           decision: Decision): Promise<Prelevement> {
    await this.latence();
    const prelevement = this.requis(
      this.prelevementsEnCours.find((p) => p.id === directDebitId), 'Prélèvement');
    this.refuserSiHorsEtat(actesSurPrelevement(prelevement).includes(decision.acte), decision.acte,
                           prelevement.status);
    const jour = '2026-09-18';
    const change: Partial<Prelevement> = decision.acte === 'REGLER'
      ? { status: 'SETTLED', settledOn: jour }
      : decision.acte === 'ANNULER'
        ? { status: 'CANCELLED', closedOn: jour, closeReason: decision.motif ?? null }
      : decision.acte === 'RETOURNER'
        ? { status: 'RETURNED', closedOn: jour, closeReason: decision.motif ?? null }
        : { status: 'REFUNDED', closedOn: jour, closeReason: decision.motif ?? null };
    const modifie = { ...prelevement, ...change };
    this.prelevementsEnCours = this.prelevementsEnCours.map(
      (p) => (p.id === directDebitId ? modifie : p));
    return modifie;
  }

  // ------------------------------------------------------------ chèques d'un compte

  async chequiers(legalEntityId: string, accountId: string): Promise<readonly Chequier[]> {
    await this.latence();
    return accountId === COMPTE_INSTRUMENTE ? this.chequiersEnCours : [];
  }

  async delivrerChequier(legalEntityId: string, accountId: string,
                         demande: DemandeChequier): Promise<EnAttentePaiements> {
    await this.latence();
    this.refuserSiObstacle(obstaclesAuChequier(demande));
    // Le socle rend une opération en attente, pas un carnet : la démonstration
    // aussi, sinon l'écran apprendrait à afficher un chéquier qui n'existe pas.
    this.dernierChequier = demande;
    return { operationId: 'op-attente-chequier' };
  }

  async cheques(legalEntityId: string, accountId: string,
                statut: StatutCheque | null): Promise<readonly Cheque[]> {
    await this.latence();
    if (accountId !== COMPTE_INSTRUMENTE) {
      return [];
    }
    return this.chequesEnCours.filter((c) => statut === null || c.status === statut);
  }

  async payerCheque(legalEntityId: string, accountId: string,
                    demande: DemandePaiementCheque): Promise<Cheque> {
    await this.latence();
    this.refuserSiObstacle(obstaclesAuPaiement(demande));
    const cheque = this.requis(this.chequesEnCours.find((c) => c.number === demande.number),
                               'Chèque');
    this.refuserSiHorsEtat(actesSurCheque(cheque).includes('PAYER'), 'Payer', cheque.status);
    const paye: Cheque = {
      ...cheque, status: 'PAID', paidOn: '2026-09-18',
      amount: { amount: demande.amount, currency: demande.currency },
      beneficiary: demande.beneficiary,
    };
    this.chequesEnCours = this.chequesEnCours.map(
      (c) => (c.number === demande.number ? paye : c));
    return paye;
  }

  async opposer(legalEntityId: string, accountId: string,
                demande: DemandeOpposition): Promise<Cheque> {
    await this.latence();
    const cheque = this.requis(this.chequesEnCours.find((c) => c.number === demande.number),
                               'Chèque');
    this.refuserSiHorsEtat(actesSurCheque(cheque).includes('OPPOSER'), 'Faire opposition',
                           cheque.status);
    const oppose: Cheque = {
      ...cheque, status: 'STOPPED', stoppedOn: '2026-09-18', stopReason: demande.reason,
    };
    this.chequesEnCours = this.chequesEnCours.map(
      (c) => (c.number === demande.number ? oppose : c));
    return oppose;
  }

  async incidents(legalEntityId: string, accountId: string): Promise<readonly IncidentCheque[]> {
    await this.latence();
    return accountId === COMPTE_INSTRUMENTE ? INCIDENTS : [];
  }

  // ------------------------------------------------------------ mandats d'un compte

  async mandats(legalEntityId: string, accountId: string): Promise<readonly Mandat[]> {
    await this.latence();
    return accountId === COMPTE_INSTRUMENTE ? this.mandatsEnCours : [];
  }

  async enregistrerMandat(legalEntityId: string, accountId: string,
                          demande: DemandeMandat): Promise<EnAttentePaiements> {
    await this.latence();
    this.refuserSiObstacle(obstaclesAuMandat(demande));
    this.dernierMandat = demande;
    return { operationId: 'op-attente-mandat' };
  }

  async revoquerMandat(legalEntityId: string, mandateId: string, motif: string): Promise<Mandat> {
    await this.latence();
    const mandat = this.requis(this.mandatsEnCours.find((m) => m.id === mandateId), 'Mandat');
    this.refuserSiHorsEtat(peutRevoquer(mandat), 'Révoquer', mandat.status);
    const revoque: Mandat = {
      ...mandat, status: 'REVOKED', revokedOn: '2026-09-18', revocationReason: motif,
    };
    this.mandatsEnCours = this.mandatsEnCours.map((m) => (m.id === mandateId ? revoque : m));
    return revoque;
  }

  /** Ce que la démonstration a reçu : les écrans de bout en bout s'en servent. */
  dernierChequier: DemandeChequier | null = null;
  dernierMandat: DemandeMandat | null = null;

  private page<T>(lignes: readonly T[], page: number, taille: number): Page<T> {
    const debut = page * taille;
    return {
      lignes: lignes.slice(debut, debut + taille),
      numero: page,
      taille,
      precedent: page > 0,
      suivant: debut + taille < lignes.length,
    };
  }

  private requis<T>(valeur: T | undefined, quoi: string): T {
    if (valeur === undefined) {
      throw new RefusMetier(404, 'OBJET_INCONNU', `${quoi} inconnu.`);
    }
    return valeur;
  }

  /** Le même refus que le socle : la demande ne tient pas. */
  private refuserSiObstacle(obstacles: readonly string[]): void {
    if (obstacles.length > 0) {
      throw new RefusMetier(400, 'DEMANDE_INVALIDE', 'La demande ne tient pas.', obstacles[0]);
    }
  }

  /** Le même refus que le socle : l'état de l'objet ne permet pas cet acte. */
  private refuserSiHorsEtat(permis: boolean, acte: string, etat: string): void {
    if (!permis) {
      throw new RefusMetier(409, 'ETAT_INCOMPATIBLE', 'Cet acte ne se fait plus.',
        `L'objet est ${etat} : ${acte.toLowerCase()} n'est plus possible dans cet état.`);
    }
  }

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve()
                                : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
