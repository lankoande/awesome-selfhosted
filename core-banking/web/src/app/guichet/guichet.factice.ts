import { Injectable } from '@angular/core';
import { formaterMontant } from '../core/format/montant';
import {
  ContexteCompte,
  DemandeEspeces,
  DemandeVirement,
  IssueVersement,
  LigneReleve,
  Montant,
  PageReleve,
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
  readonly plafondRetraitJournalier: number;
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
    plafondRetraitJournalier: 500000,
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
    plafondRetraitJournalier: 2000000,
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
    plafondRetraitJournalier: 10000000,
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
    plafondRetraitJournalier: 200000,
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
    plafondRetraitJournalier: 200000,
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
  private readonly retraits = new Map<string, number>();
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

  /**
   * Le retrait. Deux règles que le socle applique et que l'écran ne fait
   * qu'afficher : le **disponible** commande — pas le solde comptable, dont une
   * part peut être retenue par un blocage — et le plafond journalier de retrait
   * du produit s'impose au-dessus.
   */
  async retirer(demande: DemandeEspeces): Promise<IssueVersement> {
    await this.latence();

    const deja = this.dejaVu.get(demande.cleIdempotence);
    if (deja) return this.marquerRejeu(deja);

    const compte = this.compte(demande.accountId);
    const valeur = Number(demande.amount);

    if (compte.statut !== 'ACTIVE') {
      throw new RefusMetier(422, 'COMPTE_NON_ACTIF',
        `Le compte est ${compte.statut === 'BLOCKED' ? 'bloqué' : 'clôturé'}.`,
        "Aucune opération n\u2019est acceptée tant que le blocage n\u2019est pas levé par un agent habilité.");
    }

    const frais = COMMISSION;
    const taxe = Math.round(frais * TAUX_TAF);
    const disponible = compte.solde - compte.blocage;
    const aDebiter = valeur + frais + taxe;

    if (aDebiter > disponible) {
      throw new RefusMetier(422, 'PROVISION_INSUFFISANTE',
        'Le disponible ne couvre pas le retrait.',
        `Disponible ${formaterMontant(disponible)} ${DEVISE}, débit demandé ${formaterMontant(aDebiter)} ${DEVISE} `
          + `(dont ${formaterMontant(frais + taxe)} ${DEVISE} de frais et taxe)`
          + (compte.blocage > 0
              ? `. Le solde comptable est de ${formaterMontant(compte.solde)} ${DEVISE}, mais `
                + `${formaterMontant(compte.blocage)} ${DEVISE} sont retenus par un blocage.`
              : '.'));
    }

    if (valeur > compte.plafondRetraitJournalier) {
      throw new RefusMetier(422, 'PLAFOND_RETRAIT_JOURNALIER_DEPASSE',
        'Le plafond de retrait journalier du produit serait dépassé.',
        `Plafond ${formaterMontant(compte.plafondRetraitJournalier)} ${DEVISE} pour ce produit. `
          + "Au-delà, l\u2019opération passe par un virement ou par une dérogation du chef d\u2019agence.");
    }

    const recu: Recu = {
      entryId: crypto.randomUUID(),
      entryNumber: this.numeroEcriture++,
      bookingDate: this.journee(),
      valueDate: this.journee(),
      amount: montant(valeur),
      fee: montant(frais),
      tax: montant(taxe),
      balanceAfter: montant(compte.solde - aDebiter),
      branchId: 'OUA2',
      remote: false,
      replayed: false,
    };
    const issue: IssueVersement = { genre: 'comptabilise', recu };
    this.dejaVu.set(demande.cleIdempotence, issue);
    this.retraits.set(compte.accountId, (this.retraits.get(compte.accountId) ?? 0) + valeur);
    return issue;
  }

  /**
   * Le virement interne. Le socle débite et crédite dans la même transaction :
   * il n'y a jamais d'instant où l'argent n'est nulle part. Les refus restent
   * les siens — comptes distincts, même devise, disponible suffisant.
   */
  async virer(demande: DemandeVirement): Promise<IssueVersement> {
    await this.latence();

    const deja = this.dejaVu.get(demande.cleIdempotence);
    if (deja) return this.marquerRejeu(deja);

    if (demande.sourceAccountId === demande.destinationAccountId) {
      throw new RefusMetier(422, 'COMPTES_IDENTIQUES', 'Le débiteur et le bénéficiaire sont le même compte.');
    }

    const source = this.compte(demande.sourceAccountId);
    const destination = this.compte(demande.destinationAccountId);
    for (const compte of [source, destination]) {
      if (compte.statut !== 'ACTIVE') {
        throw new RefusMetier(422, 'COMPTE_NON_ACTIF',
          `Le compte ${compte.code.slice(-4)} est ${compte.statut === 'BLOCKED' ? 'bloqué' : 'clôturé'}.`,
          "Un virement suppose deux comptes actifs : celui qui paie comme celui qui reçoit.");
      }
    }

    const valeur = Number(demande.amount);
    const frais = COMMISSION;
    const taxe = Math.round(frais * TAUX_TAF);
    const disponible = source.solde - source.blocage;
    const aDebiter = valeur + frais + taxe;

    if (aDebiter > disponible) {
      throw new RefusMetier(422, 'PROVISION_INSUFFISANTE',
        'Le disponible du débiteur ne couvre pas le virement.',
        `Disponible ${formaterMontant(disponible)} ${DEVISE}, débit demandé ${formaterMontant(aDebiter)} ${DEVISE} `
          + `(dont ${formaterMontant(frais + taxe)} ${DEVISE} de frais et taxe).`);
    }

    const recu: Recu = {
      entryId: crypto.randomUUID(),
      entryNumber: this.numeroEcriture++,
      bookingDate: this.journee(),
      valueDate: this.journee(),
      amount: montant(valeur),
      fee: montant(frais),
      tax: montant(taxe),
      balanceAfter: montant(source.solde - aDebiter),
      branchId: 'OUA2',
      remote: false,
      replayed: false,
    };
    const issue: IssueVersement = { genre: 'comptabilise', recu };
    this.dejaVu.set(demande.cleIdempotence, issue);
    return issue;
  }

  /**
   * Un relevé plausible : des opérations de guichet, des frais, une écriture
   * contre-passée et sa contre-passation — toutes les deux présentes, parce
   * qu'une contre-passation ne remplace pas, elle s'ajoute. Une ligne porte une
   * date de connaissance postérieure à son jour comptable : c'est une écriture
   * passée après coup, et le relevé doit le dire.
   */
  async releve(_entite: string, accountId: string, du: string | null, au: string | null,
               page: number, taille: number): Promise<PageReleve> {
    await this.latence();
    this.compte(accountId);

    const toutes = this.lignesDe(accountId)
      .filter((l) => (du === null || l.bookingDate >= du) && (au === null || l.bookingDate <= au));
    const debut = page * taille;
    return {
      lignes: toutes.slice(debut, debut + taille),
      numero: page,
      taille,
      precedent: page > 0,
      suivant: debut + taille < toutes.length,
    };
  }

  private lignesDe(accountId: string): readonly LigneReleve[] {
    const compte = this.compte(accountId);
    const jour = (recul: number) => new Date(Date.now() - recul * 86_400_000).toISOString().slice(0, 10);
    const contrepassee = 'ecr-4102';
    const ligne = (
      n: number, recul: number, direction: 'DEBIT' | 'CREDIT', valeur: number, label: string,
      type: string, extra: Partial<LigneReleve> = {},
    ): LigneReleve => ({
      entryId: `ecr-${n}`,
      entryNumber: n,
      lineNumber: 1,
      accountCode: compte.code,
      bookingDate: jour(recul),
      valueDate: jour(recul),
      knowledgeTime: `${jour(recul)}T09:12:00Z`,
      direction,
      amount: montant(valeur),
      label,
      narrative: null,
      transactionType: type,
      reversalOf: null,
      ...extra,
    });

    return [
      ligne(4108, 0, 'CREDIT', 2500000, "Versement d'espèces", 'CASH_DEPOSIT'),
      ligne(4107, 0, 'DEBIT', 1170, 'Commission de versement et TAF', 'FEE'),
      ligne(4106, 1, 'DEBIT', 120000, "Retrait d'espèces", 'CASH_WITHDRAWAL'),
      // Passée deux jours après le jour qu'elle affecte : le socle est bitemporel.
      ligne(4105, 3, 'CREDIT', 85000, 'Virement reçu — OUEDRAOGO Salif', 'TRANSFER',
            { knowledgeTime: `${jour(1)}T16:40:00Z` }),
      ligne(4104, 4, 'DEBIT', 600000, "Contre-passation de l'écriture n° 4102", 'REVERSAL',
            { reversalOf: contrepassee }),
      ligne(4102, 4, 'CREDIT', 600000, 'Virement reçu — saisie erronée', 'TRANSFER'),
      ligne(4101, 6, 'DEBIT', 2500, 'Frais de tenue de compte', 'FEE'),
      ligne(4100, 8, 'CREDIT', 450000, "Versement d'espèces", 'CASH_DEPOSIT'),
    ];
  }

  async verser(demande: DemandeEspeces): Promise<IssueVersement> {
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
