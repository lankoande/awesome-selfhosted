import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import {
  CbActivity, CbAmount, CbButton, CbDateInput, CbDrawer, CbField, CbInput,
  CbNotice, CbPagination, CbSection, CbStateBadge, CbTable, CbToolbar,
} from '../../ui';
import { BandeauClient } from '../composants/bandeau-client';
import { ContexteCompteTiroir } from '../composants/contexte-compte';
import { ContexteCompte, LigneReleve, RefusMetier, SoldeCompte } from '../modele/guichet.modele';
import { GUICHET } from '../guichet.port';

interface LigneAffichee {
  readonly ligne: LigneReleve;
  readonly debit: string | null;
  readonly credit: string | null;
  /** Cette ligne annule une écriture : son numéro de pièce, s'il est sur la page. */
  readonly annule: { readonly numero: number | null } | null;
  /** Cette ligne a été annulée par une autre, visible sur la même page. */
  readonly annulee: boolean;
  /** Passée après le jour qu'elle affecte : le socle est bitemporel. */
  readonly connueLe: string | null;
}

/**
 * Relevé de compte.
 *
 * Trois choses qu'un relevé de banque doit dire et que la plupart taisent :
 *
 * Une **contre-passation ne remplace pas** l'écriture d'origine, elle s'ajoute.
 * Les deux restent au journal, et le relevé les montre toutes les deux —
 * l'annulée marquée comme telle, l'annulante renvoyant à sa pièce.
 *
 * Une écriture peut être **passée après le jour qu'elle affecte**. Le socle est
 * bitemporel ; quand la date de connaissance diffère du jour comptable, le
 * relevé le dit, parce que c'est ce qui explique un solde qui a « changé » hier.
 *
 * Les totaux affichés sont ceux de **la page**, jamais un solde. Un total de
 * page présenté comme un solde est un mensonge par cadrage.
 */
@Component({
  selector: 'cb-releve',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BandeauClient, CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput,
    CbNotice, CbPagination, CbSection, CbStateBadge, CbTable, CbToolbar,
  ],
  templateUrl: './releve.page.html',
  styleUrl: './releve.page.css',
})
export class Releve {
  private readonly guichet = inject(GUICHET);
  private readonly tiroir = inject(CbDrawer);
  private readonly config = inject(AppConfig);

  protected readonly catalogue = signal<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>([]);
  protected readonly compteId = signal<string>('');
  protected readonly solde = signal<SoldeCompte | null>(null);
  protected readonly contexte = signal<ContexteCompte | null>(null);

  protected readonly du = signal<string | null>(null);
  protected readonly au = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly taille = signal(50);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  protected readonly lignes = signal<readonly LigneReleve[]>([]);
  protected readonly chargement = signal(true);
  protected readonly refus = signal<RefusMetier | null>(null);

  protected readonly affichees = computed<readonly LigneAffichee[]>(() => {
    const lignes = this.lignes();
    // On ne marque « annulée » que ce que la page montre : affirmer qu'une
    // écriture n'est pas contre-passée alors qu'on n'a lu qu'une page serait
    // une affirmation qu'on ne peut pas tenir.
    const annulees = new Set(lignes.map((l) => l.reversalOf).filter((r): r is string => r !== null));
    const numeros = new Map(lignes.map((l) => [l.entryId, l.entryNumber]));
    return lignes.map((ligne) => ({
      ligne,
      debit: ligne.direction === 'DEBIT' ? ligne.amount.amount : null,
      credit: ligne.direction === 'CREDIT' ? ligne.amount.amount : null,
      // On cite un numéro de pièce quand on l'a sous les yeux ; sinon on dit
      // qu'elle est hors de cette page, plutôt que d'afficher un identifiant
      // technique qui ne veut rien dire pour un guichetier.
      annule: ligne.reversalOf ? { numero: numeros.get(ligne.reversalOf) ?? null } : null,
      annulee: annulees.has(ligne.entryId),
      connueLe: ligne.knowledgeTime.slice(0, 10) !== ligne.bookingDate ? ligne.knowledgeTime.slice(0, 10) : null,
    }));
  });

  protected readonly totalDebit = computed(() =>
    this.affichees().reduce((somme, l) => somme + (l.debit ? Number(l.debit) : 0), 0));
  protected readonly totalCredit = computed(() =>
    this.affichees().reduce((somme, l) => somme + (l.credit ? Number(l.credit) : 0), 0));
  protected readonly devise = computed(() => this.solde()?.currency ?? this.config.valeur().affichage.deviseParDefaut);

  constructor() {
    void this.demarrer();
  }

  private async demarrer(): Promise<void> {
    const liste = (await this.guichet.catalogue?.()) ?? [];
    this.catalogue.set(liste);
    const premier = liste[0]?.accountId ?? '';
    if (premier) await this.choisirCompte(premier);
    else this.chargement.set(false);
  }

  protected async choisirCompte(accountId: string): Promise<void> {
    this.compteId.set(accountId);
    this.page.set(0);
    this.chargement.set(true);
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [solde, contexte] = await Promise.all([
        this.guichet.soldes(entite, accountId),
        this.guichet.contexte(entite, accountId),
      ]);
      this.solde.set(solde);
      this.contexte.set(contexte);
      await this.charger();
    } catch (erreur) {
      this.solde.set(null);
      this.contexte.set(null);
      this.refus.set(this.enRefus(erreur));
      this.chargement.set(false);
    }
  }

  protected async charger(page = this.page()): Promise<void> {
    if (!this.compteId()) return;
    this.chargement.set(true);
    this.refus.set(null);
    try {
      const resultat = await this.guichet.releve(
        this.config.legalEntityId(), this.compteId(), this.du(), this.au(), page, this.taille(),
      );
      this.lignes.set(resultat.lignes);
      this.page.set(resultat.numero);
      this.precedent.set(resultat.precedent);
      this.suivant.set(resultat.suivant);
    } catch (erreur) {
      this.lignes.set([]);
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async changerPage(delta: number): Promise<void> {
    await this.charger(Math.max(0, this.page() + delta));
  }

  protected async appliquerPeriode(): Promise<void> {
    this.page.set(0);
    await this.charger(0);
  }

  protected ouvrirContexte(): void {
    const contexte = this.contexte();
    const solde = this.solde();
    if (!contexte || !solde) return;
    this.tiroir.ouvrir(ContexteCompteTiroir, { donnees: { contexte, solde }, etiquette: 'Contexte du compte' });
  }

  protected jour(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
