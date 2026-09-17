import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, model } from '@angular/core';
import { CbAmount, CbSection } from '../../ui';
import { Comptage, Coupure, nombreDeCoupures, totalComptage } from '../modele/coupures';

/**
 * Le billetage : le comptage physique, coupure par coupure.
 *
 * Ce n'est pas une commodité d'affichage, c'est un contrôle de caisse. Le
 * montant annoncé et le comptage doivent tomber juste ; l'écart se voit ici, à
 * la remise, pas le soir à l'arrêté où il faudra rappeler le client.
 */
@Component({
  selector: 'cb-billetage',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbAmount, CbSection],
  template: `
    <cb-section [titre]="titre()" [indication]="indication()">
      <div class="colonnes">
        @for (groupe of groupes(); track groupe.genre) {
          <table class="grille">
            <caption class="cb-caps">{{ groupe.titre }}</caption>
            <tbody>
              @for (coupure of groupe.coupures; track coupure.id) {
                <tr>
                  <th scope="row" class="valeur">
                    <label [for]="'cpt-' + coupure.id"><cb-amount [valeur]="coupure.valeur" /></label>
                  </th>
                  <td class="nombre">
                    <input
                      class="cb-input cb-input--nombre"
                      type="text"
                      inputmode="numeric"
                      autocomplete="off"
                      [id]="'cpt-' + coupure.id"
                      [value]="comptage()[coupure.id] || ''"
                      [disabled]="desactive()"
                      (input)="compter(coupure.id, $any($event.target).value)"
                    />
                  </td>
                  <td class="sous-total">
                    @if (comptage()[coupure.id]) {
                      <cb-amount [valeur]="coupure.valeur * comptage()[coupure.id]!" />
                    } @else {
                      <span class="vide">—</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>

      <div class="total" [class.total--ecart]="ecart() !== 0">
        <span class="libelle">
          @if (nombre() === 0) {
            @if (facultatif()) {
              Comptage non saisi — {{ reference() }} fera foi.
            } @else {
              Comptez les coupures : l'écart se calcule au fur et à mesure.
            }
          } @else if (ecart() === 0) {
            Le comptage retrouve {{ reference() }} · {{ nombre() }} coupures
          } @else if (ecart() > 0) {
            Excédent : le comptage dépasse {{ reference() }}.
          } @else {
            Manquant : {{ reference() }} n'est pas atteint.
          }
        </span>
        <span class="chiffres">
          <span class="cb-caps">Total compté</span>
          <cb-amount [valeur]="total()" [devise]="devise()" />
          @if (ecart() !== 0) {
            <span class="cb-caps">Écart</span>
            <cb-amount [valeur]="ecart()" [devise]="devise()" signe="toujours" ton="negatif" />
          }
        </span>
      </div>
    </cb-section>
  `,
  styles: `
    :host { display: block; }
    /* align-items: start est structurel, pas cosmétique : une table étirée
       par la grille redistribue la hauteur excédentaire à ses lignes, et le
       comptage des billets se retrouvait deux fois plus aéré que celui des
       pièces. */
    .colonnes {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      align-items: start;
      gap: var(--cb-space-3) var(--cb-space-5);
    }
    .grille { width: 100%; border-collapse: collapse; }
    caption { text-align: left; padding-bottom: var(--cb-space-1); }
    tr { height: var(--cb-tap-h); }
    .valeur { width: 30%; text-align: right; padding-right: var(--cb-space-2); font-weight: 400; }
    .valeur label { cursor: pointer; }
    .nombre { width: 34%; }
    .nombre input { height: calc(var(--cb-control-h) - 4px); }
    .sous-total { text-align: right; color: var(--cb-ink-2); }
    .vide { color: var(--cb-faint); }

    .total {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--cb-space-2) var(--cb-space-4);
      margin-top: var(--cb-space-3);
      padding-top: var(--cb-space-3);
      border-top: var(--cb-border) solid var(--cb-rule-strong);
    }
    .libelle { flex: 1 1 260px; font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .total--ecart .libelle { color: var(--cb-rejected); font-weight: 600; }
    .chiffres { display: flex; align-items: baseline; gap: var(--cb-space-2); }
    .chiffres cb-amount { font-size: var(--cb-fs-md); }

    @media (max-width: 767px) {
      .colonnes { grid-template-columns: minmax(0, 1fr); }
    }
  `,
})
export class Billetage {
  readonly comptage = model.required<Comptage>();
  /** « Billetage reçu » au versement, « billetage remis » au retrait. */
  readonly titre = input<string>('Billetage');
  /**
   * Ce que le comptage doit retrouver, nommé au sujet : au guichet c'est le
   * montant annoncé, à l'arrêté c'est le solde théorique. Le nommer juste évite
   * de faire lire « montant annoncé » à un guichetier qui compte sa caisse.
   */
  readonly reference = input<string>('le montant annoncé');
  /** Au guichet le comptage est un contrôle ; à l'arrêté, c'est l'opération. */
  readonly facultatif = input(true, { transform: booleanAttribute });
  readonly coupures = input.required<readonly Coupure[]>();
  readonly devise = input.required<string>();
  /** Montant annoncé par le guichetier : c'est lui que le comptage doit retrouver. */
  readonly montantAnnonce = input<number | null>(null);
  readonly desactive = input(false, { transform: booleanAttribute });

  readonly total = computed(() => totalComptage(this.comptage(), this.coupures()));
  readonly nombre = computed(() => nombreDeCoupures(this.comptage()));

  /** Positif : on a compté plus que le montant annoncé. Zéro : ça tombe juste. */
  readonly ecart = computed(() => {
    const annonce = this.montantAnnonce();
    if (this.nombre() === 0 || annonce === null) return 0;
    return this.total() - annonce;
  });

  readonly indication = computed(() => {
    if (this.nombre() > 0) return `${this.nombre()} coupures comptées`;
    return this.facultatif() ? 'facultatif, mais contrôlé dès la première coupure' : 'obligatoire';
  });

  readonly groupes = computed(() => {
    const coupures = this.coupures();
    return [
      { genre: 'billet', titre: 'Billets', coupures: coupures.filter((c) => c.genre === 'billet') },
      { genre: 'piece', titre: 'Pièces', coupures: coupures.filter((c) => c.genre === 'piece') },
    ].filter((groupe) => groupe.coupures.length > 0);
  });

  compter(id: string, saisie: string): void {
    const chiffres = saisie.replace(/[^0-9]/g, '');
    const nombre = chiffres === '' ? 0 : Number.parseInt(chiffres, 10);
    this.comptage.set({ ...this.comptage(), [id]: nombre });
  }
}
