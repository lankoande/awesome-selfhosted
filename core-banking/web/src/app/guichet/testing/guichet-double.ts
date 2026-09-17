import {
  ContexteCompte, DemandeEspeces, DemandeVirement, IssueVersement, PageReleve, Recu, SoldeCompte,
} from '../modele/guichet.modele';
import { Guichet } from '../guichet.port';

export const COMPTE_DOUBLE = '11111111-1111-4111-8111-000000000417';
export const montantDouble = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

export const RECU_DOUBLE: Recu = {
  entryId: 'e-1', entryNumber: 5001, bookingDate: '2026-09-17', valueDate: '2026-09-17',
  amount: montantDouble(100000), fee: montantDouble(1000), tax: montantDouble(170),
  balanceAfter: montantDouble(1139330), branchId: 'OUA2', remote: false, replayed: false,
};

/**
 * Un socle sous contrôle pour les tests d'écran : tout le port implémenté avec
 * des valeurs par défaut plausibles, chaque test ne redéfinissant que ce qui
 * l'intéresse. Une seule classe à faire suivre quand le port grandit.
 *
 * Le compte par défaut porte un blocage : c'est là que solde et disponible
 * divergent, et c'est ce que les écrans doivent savoir montrer.
 */
export class GuichetDouble implements Guichet {
  readonly especes: DemandeEspeces[] = [];
  readonly virements: DemandeVirement[] = [];

  issue: () => Promise<IssueVersement> = async () => ({ genre: 'comptabilise', recu: RECU_DOUBLE });
  releveRendu: PageReleve = { lignes: [], numero: 0, taille: 50, precedent: false, suivant: false };
  comptes: readonly { accountId: string; code: string; intitule: string; pourquoi: string }[] = [
    { accountId: COMPTE_DOUBLE, code: 'BF12001025100000000417', intitule: 'SANKARA Aminata', pourquoi: '' },
  ];
  soldesParCompte = new Map<string, SoldeCompte>();

  async catalogue() {
    return this.comptes;
  }

  async soldes(_entite: string, accountId: string): Promise<SoldeCompte> {
    const connu = this.soldesParCompte.get(accountId);
    if (connu) return connu;
    return {
      accountId, code: 'BF12001025100000000417', currency: 'XOF',
      current: montantDouble(1240500), available: montantDouble(1190500),
      asOf: '2026-09-17', status: 'ACTIVE', branchId: 'OUA2',
    };
  }

  async contexte(): Promise<ContexteCompte> {
    return {
      intitule: 'SANKARA Aminata', partyId: 'CL-0004217', reference: 'BF12001025100000000417',
      nature: 'Particulier', produit: 'Compte chèque particulier', ouvertLe: '2019-06-14',
      kyc: { etat: 'À jour', revuLe: '2026-03-12' }, blocages: [], lacunes: [],
    };
  }

  async verser(demande: DemandeEspeces): Promise<IssueVersement> {
    this.especes.push(demande);
    return this.issue();
  }

  async retirer(demande: DemandeEspeces): Promise<IssueVersement> {
    this.especes.push(demande);
    return this.issue();
  }

  async virer(demande: DemandeVirement): Promise<IssueVersement> {
    this.virements.push(demande);
    return this.issue();
  }

  async releve(): Promise<PageReleve> {
    return this.releveRendu;
  }
}
