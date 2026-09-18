import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, model, signal, untracked,
} from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { CbButton, CbInput } from '../../ui';
import { CompteGeneral } from '../modele/produits.modele';
import { SIEGE } from '../siege.port';

/**
 * Choisir un compte d'imputation dans le plan comptable.
 *
 * Un paramétrage désigne des comptes : les intérêts courus, le produit d'une commission, le
 * compte de collecte d'une taxe. Sans ce composant, ces champs se remplissaient avec un
 * identifiant technique recopié d'ailleurs — ce qui n'est pas une interface.
 *
 * Le composant rend l'identifiant au formulaire et **montre en clair ce qui a été retenu** :
 * numéro du compte, sens normal, nature. Un compte de produits retenu là où il fallait un compte
 * de charges ne se verrait jamais sur un UUID ; sur `602100 · débit · résultat`, si.
 */
@Component({
  selector: 'cb-choix-compte-general',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton, CbInput],
  template: `
    @if (choisi(); as compte) {
      <div class="choisi">
        <span class="numero cb-mono">{{ compte.code }}</span>
        <span class="detail">
          {{ compte.normalBalance === 'DEBIT' ? 'débit' : 'crédit' }}
          · {{ compte.nature === 'INCOME_STATEMENT' ? 'résultat' : 'bilan' }}
          · {{ compte.currency }}
          @if (compte.kind !== 'GL') { · {{ compte.kind }} }
        </span>
        <button type="button" cbButton="discret" (click)="changer()">Changer</button>
      </div>
    } @else {
      <div class="quete">
        <input cbInput [id]="champId()" [name]="champId()" autocomplete="off" type="search"
               placeholder="Numéro du compte"
               [attr.aria-label]="'Chercher un compte d’imputation par son numéro'"
               [value]="texte()"
               (input)="texte.set($any($event.target).value)"
               (keydown.enter)="$event.preventDefault(); chercher()" />
        <button type="button" cbButton="secondaire" [disabled]="enCours()" (click)="chercher()">
          Chercher
        </button>
      </div>

      @if (cherche() && resultats().length === 0 && !enCours()) {
        <p class="vide">Aucun compte ne porte ce numéro.</p>
      }

      @if (resultats().length > 0) {
        <ul class="resultats">
          @for (compte of resultats(); track compte.id) {
            <li>
              <button type="button" class="ligne" (click)="retenir(compte)">
                <span class="numero cb-mono">{{ compte.code }}</span>
                <span class="detail">
                  {{ compte.normalBalance === 'DEBIT' ? 'débit' : 'crédit' }}
                  · {{ compte.nature === 'INCOME_STATEMENT' ? 'résultat' : 'bilan' }}
                  · {{ compte.currency }}
                  @if (!compte.postable) { · non mouvementable }
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
    .choisi button { margin-left: auto; }
    .resultats {
      list-style: none;
      margin: var(--cb-space-2) 0 0;
      padding: 0;
      max-height: 14rem;
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
    .vide { margin: var(--cb-space-2) 0 0; color: var(--cb-muted); font-size: var(--cb-fs-sm); }
  `,
})
export class CbChoixCompteGeneral {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);

  /** L'identifiant du compte retenu, rendu au formulaire. Vide tant que rien n'est choisi. */
  readonly compteId = model<string>('');
  readonly champId = input<string>('choix-compte-general');

  protected readonly texte = signal('');
  protected readonly resultats = signal<readonly CompteGeneral[]>([]);
  protected readonly choisi = signal<CompteGeneral | null>(null);
  protected readonly enCours = signal(false);
  protected readonly cherche = signal(false);

  protected readonly vide = computed(() => this.compteId() === '');

  constructor() {
    // Un formulaire rouvert sur une version existante arrive avec l'identifiant déjà posé : le
    // montrer en clair suppose de le résoudre, sinon l'écran afficherait un champ vide au-dessus
    // d'une valeur qui, elle, part au socle.
    effect(() => {
      const pose = this.compteId();
      if (pose === '' || untracked(() => this.choisi()?.id) === pose) {
        return;
      }
      untracked(() => void this.resoudre(pose));
    });
  }

  private async resoudre(compteId: string): Promise<void> {
    try {
      const comptes = await this.siege.comptesGeneraux(this.config.legalEntityId(), '');
      const trouve = comptes.find((compte) => compte.id === compteId);
      if (trouve) {
        this.choisi.set(trouve);
      }
    } catch {
      // Le refus se voit dans l'écran appelant : ce composant ne double pas le message.
    }
  }

  protected async chercher(): Promise<void> {
    this.enCours.set(true);
    try {
      this.resultats.set(
        await this.siege.comptesGeneraux(this.config.legalEntityId(), this.texte()));
      this.cherche.set(true);
    } catch {
      this.resultats.set([]);
      this.cherche.set(true);
    } finally {
      this.enCours.set(false);
    }
  }

  protected retenir(compte: CompteGeneral): void {
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
