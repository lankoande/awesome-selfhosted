import { Injectable } from '@angular/core';
import { formaterMontant } from '../core/format/montant';
import {
  ContexteCompte,
  DemandeVersement,
  IssueVersement,
  Montant,
  Recu,
  RefusMetier,
  SoldeCompte,
} from './modele/guichet.modele';
import { Guichet } from './guichet.port';

/**
 * Source de démonstration. Elle rejoue le comportement du socle pour que
 * l'écran soit jugeable sans serveur — elle n'est pas le socle, et l'écran
 * affiche un bandeau qui le dit.
 *
 * Elle est choisie par `config.json` (`sourceDonnees: "factice"`). En
 * production, `sourceDonnees: "api"` et cette classe n'est jamais instanciée.
 *
 * Chaque compte du catalogue existe pour montrer une issue : le cas nominal, le
 * refus de plafond, la mise en attente d'un second regard, le compte bloqué, et
 * le réseau qui tombe. Ce sont des cas de démonstration, pas des règles
 * métier : aucune de ces décisions n'appartient au front.
 */

interface CompteDemo {
  readonly accountId: string;
  readonly code: string;
  readonly intitule: string;
  readonly partyId: string;
  readonly nature: string;
  readonly produit: string;
  readonly ouvertLe: string;
  readonly statut: string;
  readonly solde: number;
  readonly blocage: number;
  readonly kyc: { etat: string; revuLe: string };
  readonly cumulEspeces30j: number;
  readonly plafondEspeces30j: number;
  readonly scenario: 'nominal' | 'validation' | 'bloque' | 'panne';
  readonly pourquoi: string;
}

const DEVISE = 'XOF';
const COMMISSION = 1000;
const TAUX_TAF = 0.17;

export const COMPTES_DEMO: readonly CompteDemo[] = [
  {
    accountId: '11111111-1111-4111-8111-000000000417',
    code: 'BF12001025100000000417',
    intitule: 'SANKARA Aminata',
    partyId: 'CL-0004217',
    nature: 'Particulier',
    produit: 'Compte chèque particulier',
    ouvertLe: '2019-06-14',
    statut: 'ACTIVE',
    solde: 1240500,
    blocage: 50000,
    kyc: { etat: 'À jour', revuLe: '2026-03-12' },
    cumulEspeces30j: 2100000,
    plafondEspeces30j: 10000000,
    scenario: 'nominal',
    pourquoi: 'Cas nominal — un blocage judiciaire ampute le disponible.',
  },
  {
    accountId: '22222222-2222-4222-8222-000000001182',
    code: 'BF12001025100000001182',
    intitule: 'OUEDRAOGO Salif',
    partyId: 'CL-0009114',
    nature: 'Particulier',
    produit: 'Compte chèque particulier',
    ouvertLe: '2021-02-03',
    statut: 'ACTIVE',
    solde: 3450000,
    blocage: 0,
    kyc: { etat: 'À jour', revuLe: '2026-01-20' },
    cumulEspeces30j: 7900000,
    plafondEspeces30j: 10000000,
    scenario: 'nominal',
    pourquoi: 'Plafond espèces proche : au-delà de 2 100 000 le socle refuse.',
  },
  {
    accountId: '33333333-3333-4333-8333-000000002044',
    code: 'BF12001025100000002044',
    intitule: 'ETS KABORE & Fils',
    partyId: 'CL-0002044',
    nature: 'Entreprise',
    produit: 'Compte courant commerçant',
    ouvertLe: '2016-11-08',
    statut: 'ACTIVE',
    solde: 18900000,
    blocage: 0,
    kyc: { etat: 'À jour', revuLe: '2026-05-04' },
    cumulEspeces30j: 12000000,
    plafondEspeces30j: 60000000,
    scenario: 'validation',
    pourquoi: 'Le socle répond 202 : second regard requis avant comptabilisation.',
  },
  {
    accountId: '44444444-4444-4444-8444-000000003390',
    code: 'BF12001025100000003390',
    intitule: 'TRAORE Fatimata',
    partyId: 'CL-0003390',
    nature: 'Particulier',
    produit: 'Compte chèque particulier',
    ouvertLe: '2018-09-30',
    statut: 'BLOCKED',
    solde: 620000,
    blocage: 620000,
    kyc: { etat: 'Revue échue', revuLe: '2024-07-15' },
    cumulEspeces30j: 0,
    plafondEspeces30j: 10000000,
    scenario: 'bloque',
    pourquoi: 'Compte bloqué : la saisie ne s’ouvre pas.',
  },
  {
    accountId: '55555555-5555-4555-8555-000000004265',
    code: 'BF12001025100000004265',
    intitule: 'COMPAORE Issa',
    partyId: 'CL-0004265',
    nature: 'Particulier',
    produit: 'Compte épargne',
    ouvertLe: '2022-04-19',
    statut: 'ACTIVE',
    solde: 875000,
    blocage: 0,
    kyc: { etat: 'À jour', revuLe: '2025-12-01' },
    cumulEspeces30j: 300000,
    plafondEspeces30j: 10000000,
    scenario: 'panne',
    pourquoi: 'Le socle tombe à la première tentative : « Réessayer » rejoue la même clé.',
  },
];

function montant(valeur: number): Montant {
  return { amount: String(Math.round(valeur)), currency: DEVISE };
}

@Injectable()
export class GuichetFactice implements Guichet {
  /** Clé d'idempotence → issue déjà rendue. C'est tout le mécanisme du rejeu. */
  private readonly dejaVu = new Map<string, IssueVersement>();
  private readonly pannesConsommees = new Set<string>();
  private readonly cumuls = new Map<string, number>();
  private numeroEcriture = 4127;

  /** Latence simulée d'un aller-retour réseau. Mise à zéro par les tests. */
  latenceMs = 320;

  async catalogue(): Promise<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]> {
    return COMPTES_DEMO.map((c) => ({
      accountId: c.accountId,
      code: c.code,
      intitule: c.intitule,
      pourquoi: c.pourquoi,
    }));
  }

  async soldes(_entite: string, accountId: string): Promise<SoldeCompte> {
    const compte = this.compte(accountId);
    return {
      accountId: compte.accountId,
      code: compte.code,
      currency: DEVISE,
      current: montant(compte.solde),
      available: montant(compte.solde - compte.blocage),
      asOf: this.journee(),
      status: compte.statut,
      branchId: 'OUA2',
    };
  }

  async contexte(_entite: string, accountId: string): Promise<ContexteCompte> {
    const compte = this.compte(accountId);
    return {
      intitule: compte.intitule,
      partyId: compte.partyId,
      reference: compte.code,
      nature: compte.nature,
      produit: compte.produit,
      ouvertLe: compte.ouvertLe,
      kyc: { etat: compte.kyc.etat, revuLe: compte.kyc.revuLe },
      blocages:
        compte.blocage > 0
          ? [
              {
                id: 'blk-1',
                motif: compte.statut === 'BLOCKED' ? 'Compte bloqué — revue KYC échue' : 'Opposition judiciaire',
                montant: montant(compte.blocage),
                poseLe: '2026-08-04',
                reference: 'Greffe TGI Ouaga',
              },
            ]
          : [],
      lacunes: [],
    };
  }

  async verser(demande: DemandeVersement): Promise<IssueVersement> {
    await this.latence();

    const deja = this.dejaVu.get(demande.cleIdempotence);
    if (deja) return this.marquerRejeu(deja);

    const compte = this.compte(demande.accountId);
    const valeur = Number(demande.amount);

    if (compte.statut !== 'ACTIVE') {
      throw new RefusMetier(422, 'COMPTE_NON_ACTIF',
        `Le compte est ${compte.statut === 'BLOCKED' ? 'bloqué' : 'clôturé'}.`,
        "Aucune opération n’est acceptée tant que le blocage n’est pas levé par un agent habilité.");
    }

    if (compte.scenario === 'panne' && !this.pannesConsommees.has(demande.cleIdempotence)) {
      // Une panne ne consomme pas la clé : le rejeu doit aboutir.
      this.pannesConsommees.add(demande.cleIdempotence);
      throw new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.',
        "La saisie est intacte et la clé d’idempotence conservée : « Réessayer » rejoue la même opération sans risque de double comptabilisation.");
    }

    const cumul = (this.cumuls.get(compte.accountId) ?? compte.cumulEspeces30j) + valeur;
    if (cumul > compte.plafondEspeces30j) {
      throw new RefusMetier(422, 'PLAFOND_ESPECES_30J_DEPASSE',
        'Le plafond de versements en espèces sur 30 jours glissants serait dépassé.',
        `Le cumul atteindrait ${formaterMontant(cumul)} ${DEVISE} pour un plafond déclaré de `
          + `${formaterMontant(compte.plafondEspeces30j)} ${DEVISE} `
          + "(profil d’activité du 04/02/2026). Aucune écriture n’a été passée.");
    }

    if (compte.scenario === 'validation') {
      const issue: IssueVersement = {
        genre: 'en-attente',
        operationId: `PND-${String(this.numeroEcriture++).padStart(6, '0')}`,
        attenduDe: 'un second agent habilité',
      };
      this.dejaVu.set(demande.cleIdempotence, issue);
      return issue;
    }

    const frais = COMMISSION;
    const taxe = Math.round(frais * TAUX_TAF);
    this.cumuls.set(compte.accountId, cumul);

    const recu: Recu = {
      entryId: crypto.randomUUID(),
      entryNumber: this.numeroEcriture++,
      bookingDate: this.journee(),
      valueDate: this.journee(), // espèces : valeur du jour
      amount: montant(valeur),
      fee: montant(frais),
      tax: montant(taxe),
      balanceAfter: montant(compte.solde + valeur - frais - taxe),
      branchId: 'OUA2',
      remote: false,
      replayed: false,
    };
    const issue: IssueVersement = { genre: 'comptabilise', recu };
    this.dejaVu.set(demande.cleIdempotence, issue);
    return issue;
  }

  private marquerRejeu(issue: IssueVersement): IssueVersement {
    return issue.genre === 'comptabilise' ? { genre: 'comptabilise', recu: { ...issue.recu, replayed: true } } : issue;
  }

  private compte(accountId: string): CompteDemo {
    const compte = COMPTES_DEMO.find((c) => c.accountId === accountId);
    if (!compte) throw new RefusMetier(404, 'COMPTE_INTROUVABLE', 'Compte inconnu.');
    return compte;
  }

  private journee(): string {
    return new Date().toISOString().slice(0, 10);
  }

  /** Un aller-retour réseau n'est jamais instantané ; l'écran doit le supporter. */
  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((resoudre) => setTimeout(resoudre, this.latenceMs));
  }
}
