import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import {
  actesSurOrdre, actesSurPrelevement, actesSurRemise, DemandeOrdre, DemandeRemise, OrdrePaiement,
  Prelevement, Remise, SensPrelevement, StatutOrdre, StatutPrelevement, StatutRemise,
} from './modele/paiements.modele';
import { Decision, Page, Paiements } from './paiements.port';

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
 * La source de démonstration des moyens de paiement.
 *
 * Elle applique les mêmes gardes que le socle — c'est le sujet : un écran qui
 * n'aurait jamais vu un refus d'état ne saurait pas le présenter.
 */
@Injectable()
export class PaiementsFactice implements Paiements {
  latenceMs = 300;

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
