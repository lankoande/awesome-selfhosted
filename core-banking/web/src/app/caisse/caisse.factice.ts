import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { ArreteCaisse, EtatCaisse } from './modele/caisse.modele';
import { Caisse } from './caisse.port';

const DEVISE = 'XOF';

/**
 * Source de démonstration de la caisse. Le solde théorique est celui d'une
 * journée de guichet plausible : une dotation du matin, des versements, des
 * retraits. Le comptage, lui, vient des doigts du guichetier.
 */
@Injectable()
export class CaisseFactice implements Caisse {
  latenceMs = 260;

  private readonly theorique = 4_317_500;
  private arrete: ArreteCaisse | null = null;

  async etat(_entite: string): Promise<EtatCaisse> {
    await this.latence();
    return {
      tillId: 'till-02',
      tillCode: 'OUA2-C02',
      businessDate: new Date().toISOString().slice(0, 10),
      currency: DEVISE,
      book: { amount: String(this.theorique), currency: DEVISE },
      mouvements: 37,
      arretee: this.arrete !== null,
      lacunes: [],
    };
  }

  async arreter(_entite: string, tillId: string, compte: number, devise: string): Promise<ArreteCaisse> {
    await this.latence();
    if (this.arrete) {
      throw new RefusMetier(409, 'CAISSE_DEJA_ARRETEE', 'Cette caisse est déjà arrêtée pour la journée.',
        "Un second comptage ne remplace pas le premier : il faudrait rouvrir la journée, ce qui relève "
        + "de l’exploitation comptable, pas du guichet.");
    }
    const ecart = compte - this.theorique;
    this.arrete = {
      id: crypto.randomUUID(),
      tillId,
      tillCode: 'OUA2-C02',
      businessDate: new Date().toISOString().slice(0, 10),
      book: { amount: String(this.theorique), currency: devise },
      counted: { amount: String(compte), currency: devise },
      difference: { amount: String(ecart), currency: devise },
      entryId: ecart === 0 ? null : crypto.randomUUID(),
    };
    return this.arrete;
  }

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
