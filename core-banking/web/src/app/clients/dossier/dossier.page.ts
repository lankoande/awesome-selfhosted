import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CbActivity, CbButton, CbNotice, CbSection, CbStateBadge, CbTable } from '../../ui';
import { CLIENTS } from '../clients.port';
import { IdentiteTiers } from '../composants/identite-tiers';
import {
  BeneficiaireEffectif, CompteClient, Dossier, LIBELLE_PIECE, Piece, Tiers,
  obstaclesAOuverture,
} from '../modele/clients.modele';

/**
 * Le dossier d'un client.
 *
 * L'écran répond d'abord à la question qu'on se pose en l'ouvrant : **puis-je
 * ouvrir un compte à cette personne ?** La réponse est en tête, avant les
 * pièces — et quand elle est non, elle dit quoi faire, pièce par pièce.
 * Reléguer cette réponse en bas de page obligerait à lire tout le dossier pour
 * apprendre qu'on ne pouvait rien en faire.
 *
 * Les pièces remplacées restent affichées, grisées : un dossier client se
 * relit des années après, et une pièce disparue est une question sans réponse.
 */
@Component({
  selector: 'cb-dossier-client',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbNotice, CbSection, CbStateBadge, CbTable, IdentiteTiers],
  templateUrl: './dossier.page.html',
  styleUrl: './dossier.page.css',
})
export class DossierClient {
  /** Lié depuis la route (`withComponentInputBinding`). */
  readonly id = input.required<string>();

  private readonly clients = inject(CLIENTS);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly tiers = signal<Tiers | null>(null);
  protected readonly dossier = signal<Dossier | null>(null);
  protected readonly pieces = signal<readonly Piece[]>([]);
  protected readonly beneficiaires = signal<readonly BeneficiaireEffectif[]>([]);

  /**
   * Ses comptes.
   *
   * Le dossier répondait à « puis-je ouvrir un compte à cette personne ? » sans
   * jamais dire ce qu'elle en avait déjà. Un chargé de clientèle devait aller
   * les chercher ailleurs — et, jusqu'à ce que le socle publie la liste, il
   * n'avait nulle part où aller.
   */
  protected readonly comptes = signal<readonly CompteClient[]>([]);
  protected readonly chargement = signal(true);
  protected readonly refus = signal<RefusMetier | null>(null);

  protected readonly LIBELLE_PIECE = LIBELLE_PIECE;

  /** Ce que le socle refusera, annoncé avant la saisie. */
  protected readonly obstacles = computed(() => {
    const t = this.tiers();
    return t ? obstaclesAOuverture(t, this.dossier()) : [];
  });

  /** Les pièces en vigueur d'abord ; les remplacées suivent, sans disparaître. */
  protected readonly piecesTriees = computed(() =>
    [...this.pieces()].sort((a, b) => Number(!!a.supersededBy) - Number(!!b.supersededBy)));

  /**
   * Le client à charger suit l'URL. Un `effect` plutôt qu'un appel au
   * constructeur : l'entrée de route n'est pas encore posée à la construction
   * (NG0950), et passer d'un client à l'autre sans quitter l'écran doit
   * recharger — le routeur réutilise le composant quand seul `:id` change.
   */
  constructor() {
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const tiers = await this.clients.lire(entite, id);
      this.tiers.set(tiers);
      // Le dossier, les pièces, les bénéficiaires et les comptes sont quatre
      // lectures indépendantes : les enchaîner quadruplerait l'attente pour rien.
      const [dossier, pieces, beneficiaires, comptes] = await Promise.all([
        this.clients.dossier(entite, id),
        this.clients.pieces(entite, id),
        this.clients.beneficiaires(entite, id),
        this.clients.comptes(entite, { partyId: id }, 0, 50),
      ]);
      this.dossier.set(dossier);
      this.pieces.set(pieces);
      this.beneficiaires.set(beneficiaires);
      this.comptes.set(comptes.comptes);
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirCompte(): void {
    void this.router.navigate(['/clients', this.id(), 'compte']);
  }

  protected retour(): void {
    void this.router.navigate(['/clients/recherche']);
  }

  /**
   * Les moyens de paiement de ce compte, compte déjà désigné.
   *
   * Le dossier sait quel compte on regarde ; faire retaper son numéro dans
   * l'autre espace serait le lui faire chercher deux fois.
   */
  protected moyensDePaiement(compte: CompteClient): void {
    // Le **numéro**, pas l'identifiant : l'adresse se recopie et se met en
    // favori, et un numéro de compte se relit.
    void this.router.navigate(['/paiements/compte'], { queryParams: { compte: compte.code } });
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }

  /** Une pièce expirée se voit sans lire la date. */
  protected perimee(piece: Piece): boolean {
    return piece.expiresOn !== null && piece.expiresOn < this.aujourdhui();
  }

  private aujourdhui(): string {
    return new Date().toISOString().slice(0, 10);
  }
}
