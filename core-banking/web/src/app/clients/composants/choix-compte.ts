import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, model, signal, untracked,
} from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { CbButton, CbInput } from '../../ui';
import { CLIENTS } from '../clients.port';
import { CompteClient } from '../modele/clients.modele';

/**
 * Choisir un compte, par son numéro ou par son titulaire.
 *
 * Remplace la saisie d'un identifiant technique. Un guichetier ne connaît pas
 * l'identifiant d'un compte — il connaît son numéro, ou le nom du client. Le
 * faire taper un UUID, c'était lui demander d'aller le chercher ailleurs, et
 * de le recopier.
 *
 * Le composant rend l'identifiant au formulaire, et **montre en clair ce qui a
 * été choisi** : numéro, titulaire, agence. Ce qui est envoyé au socle doit se
 * relire avant d'être envoyé.
 *
 * Un écran qui arrive avec un compte déjà désigné passe son **numéro** par
 * `preselection` — pas son identifiant. Le numéro se lit dans une URL, se
 * recopie, se dicte au téléphone ; l'identifiant technique ne dit rien à
 * personne. Le composant le résout, et l'écran reçoit l'identifiant comme si
 * quelqu'un l'avait cherché.
 */
@Component({
  selector: 'cb-choix-compte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton, CbInput],
  template: `
    @if (choisi(); as compte) {
      <div class="choisi">
        <span class="numero cb-mono">{{ compte.code }}</span>
        <span class="detail">
          {{ compte.holderName || 'titulaire inconnu' }}
          @if (compte.branchCode) { · agence {{ compte.branchCode }} }
          · {{ compte.currency }}
        </span>
        <button type="button" cbButton="discret" (click)="changer()">Changer</button>
      </div>
    } @else {
      <div class="quete">
        <input cbInput [id]="champId()" name="compte" autocomplete="off" type="search"
               placeholder="Numéro de compte, nom ou référence du client"
               [attr.aria-label]="'Chercher un compte : numéro, nom ou référence du client'"
               [value]="texte()"
               (input)="texte.set($any($event.target).value)"
               (keydown.enter)="$event.preventDefault(); chercher()" />
        <button type="button" cbButton="secondaire" [disabled]="enCours()" (click)="chercher()">
          Chercher
        </button>
      </div>

      @if (cherche() && resultats().length === 0 && !enCours()) {
        <p class="vide">
          Aucun compte ne correspond. La recherche porte sur le numéro, le nom du titulaire et sa
          référence client.
        </p>
      }

      @if (resultats().length > 0) {
        <ul class="resultats">
          @for (compte of resultats(); track compte.id) {
            <li>
              <button type="button" class="ligne" (click)="retenir(compte)">
                <span class="numero cb-mono">{{ compte.code }}</span>
                <span class="detail">
                  {{ compte.holderName || '—' }}
                  @if (compte.branchCode) { · agence {{ compte.branchCode }} }
                  · {{ compte.currency }}
                  @if (compte.status !== 'ACTIVE') { · {{ compte.status }} }
                </span>
              </button>
            </li>
          }
        </ul>
      }
    }
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .quete { display: flex; gap: var(--cb-space-2); }
    .quete input { flex: 1 1 auto; min-width: 0; }
    .numero { display: block; }
    .detail { display: block; font-size: var(--cb-fs-xs); color: var(--cb-muted); }
    .choisi {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cb-space-2) var(--cb-space-3);
      padding: var(--cb-space-2) var(--cb-space-3);
      border: var(--cb-border) solid var(--cb-rule);
      border-radius: var(--cb-radius);
    }
    .choisi .numero, .choisi .detail { flex: 0 1 auto; }
    .choisi button { margin-left: auto; }
    .resultats {
      list-style: none;
      margin: var(--cb-space-2) 0 0;
      padding: 0;
      max-height: 16rem;
      overflow-y: auto;
      border: var(--cb-border) solid var(--cb-rule);
      border-radius: var(--cb-radius);
    }
    .resultats li + li { border-top: var(--cb-border) solid var(--cb-rule); }
    .ligne {
      display: block;
      width: 100%;
      min-height: var(--cb-tap-h);
      padding: var(--cb-space-2) var(--cb-space-3);
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .ligne:hover { background: var(--cb-panel); }
    .vide {
      margin: var(--cb-space-2) 0 0;
      color: var(--cb-muted);
      font-size: var(--cb-fs-sm);
    }
  `,
})
export class CbChoixCompte {
  private readonly clients = inject(CLIENTS);
  private readonly config = inject(AppConfig);

  /** L'identifiant du compte retenu, rendu au formulaire. Vide tant que rien n'est choisi. */
  readonly compteId = model<string>('');
  readonly champId = model<string>('choix-compte');

  /** Un numéro de compte venu d'ailleurs : d'une URL, d'un écran qui renvoie ici. */
  readonly preselection = input<string>('');

  protected readonly texte = signal('');
  protected readonly resultats = signal<readonly CompteClient[]>([]);
  protected readonly choisi = signal<CompteClient | null>(null);
  protected readonly enCours = signal(false);
  protected readonly cherche = signal(false);

  protected readonly vide = computed(() => this.compteId() === '');

  constructor() {
    effect(() => {
      const numero = this.preselection().trim();
      if (numero === '' || untracked(() => this.compteId()) !== '') {
        return;
      }
      untracked(() => void this.resoudre(numero));
    });
  }

  /**
   * Résout un numéro venu d'ailleurs.
   *
   * On ne retient d'office que si la recherche ramène **un** compte : un numéro
   * désigne un compte et un seul, et s'il en ramène plusieurs, ce n'était pas un
   * numéro. Dans ce cas l'opérateur choisit — retenir le premier serait ouvrir
   * le dossier d'un autre client sans le dire.
   */
  private async resoudre(numero: string): Promise<void> {
    this.texte.set(numero);
    await this.chercher();
    const trouves = this.resultats();
    if (trouves.length === 1 && trouves[0]) {
      this.retenir(trouves[0]);
    }
  }

  protected async chercher(): Promise<void> {
    this.enCours.set(true);
    try {
      const rendu = await this.clients.comptes(
        this.config.legalEntityId(), { texte: this.texte() }, 0, 20);
      this.resultats.set(rendu.comptes);
      this.cherche.set(true);
    } catch {
      // Le refus se voit dans l'écran appelant : ce composant ne double pas le message.
      this.resultats.set([]);
      this.cherche.set(true);
    } finally {
      this.enCours.set(false);
    }
  }

  protected retenir(compte: CompteClient): void {
    this.choisi.set(compte);
    this.compteId.set(compte.id);
    this.resultats.set([]);
    this.cherche.set(false);
  }

  protected changer(): void {
    this.choisi.set(null);
    this.compteId.set('');
    this.texte.set('');
  }
}
