import { signal } from '@angular/core';
import { EtatSession, Habilitations, Porteur } from './auth.port';
import { HABILITATIONS_INCONNUES } from './habilitations';

/**
 * Ce qu'une session tient en mémoire, et rien d'autre.
 *
 * **Aucun jeton ne va dans `localStorage`.** Un XSS de back-office bancaire,
 * c'est une session de guichetier volée ; tout ce qui est écrit dans le
 * stockage du navigateur est lisible par n'importe quel script de la page.
 * Le jeton vit ici, dans une variable, et meurt avec l'onglet. Le prix à payer
 * est un renouvellement silencieux au rechargement — c'est le bon prix.
 */
export class Session {
  readonly etat = signal<EtatSession>('inconnue');

  private acces: string | null = null;
  private rafraichissement: string | null = null;
  private expiration = 0;
  /**
   * Porteur et habilitations sont des **signaux**, pas des champs.
   *
   * Ils n'arrivent pas au même instant que la session : les habilitations sont
   * lues par un appel au socle, qui peut aboutir après le premier rendu, et
   * elles sont relues à chaque reprise. Un champ ordinaire laisserait la barre
   * et les boutons figés sur ce qu'ils savaient au démarrage — une porte
   * resterait fermée alors qu'elle vient de s'ouvrir.
   */
  private readonly titulaire = signal<Porteur | null>(null);
  private readonly droits = signal<Habilitations>(HABILITATIONS_INCONNUES);

  ouvrir(acces: string, rafraichissement: string | null, dureeSecondes: number, porteur: Porteur): void {
    this.acces = acces;
    this.rafraichissement = rafraichissement;
    // On considère le jeton périmé un peu avant l'heure : une horloge décalée
    // de trente secondes ne doit pas produire un 401 en pleine saisie.
    this.expiration = Date.now() + Math.max(0, dureeSecondes - 30) * 1000;
    this.titulaire.set(porteur);
    this.etat.set('ouverte');
  }

  poserHabilitations(habilitations: Habilitations): void {
    this.droits.set(habilitations);
  }

  jeton(): string | null {
    return this.etat() === 'ouverte' ? this.acces : null;
  }

  jetonDeRafraichissement(): string | null {
    return this.rafraichissement;
  }

  perime(): boolean {
    return this.acces === null || Date.now() >= this.expiration;
  }

  porteur(): Porteur | null {
    return this.titulaire();
  }

  habilitations(): Habilitations {
    return this.droits();
  }

  /**
   * Verrouiller n'est pas fermer. Le jeton reste, l'écran se couvre, et la
   * saisie en cours survit : un guichetier qui perd sa saisie parce qu'il est
   * allé chercher un dossier ne verrouille plus son poste.
   */
  verrouiller(): void {
    if (this.etat() === 'ouverte') this.etat.set('verrouillee');
  }

  deverrouiller(): void {
    if (this.etat() === 'verrouillee') this.etat.set('ouverte');
  }

  fermer(): void {
    this.acces = null;
    this.rafraichissement = null;
    this.expiration = 0;
    this.titulaire.set(null);
    this.droits.set(HABILITATIONS_INCONNUES);
    this.etat.set('anonyme');
  }
}
