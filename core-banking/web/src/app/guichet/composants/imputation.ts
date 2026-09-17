import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CbAmount, CbSection } from '../../ui';
import { Recu } from '../modele/guichet.modele';

/**
 * Le récapitulatif d'imputation : ce que la comptabilité va enregistrer, montré
 * avant de valider. C'est la pièce qui distingue ce back-office des autres.
 *
 * Une règle, et elle n'est pas négociable : **le front ne calcule ni les frais,
 * ni la taxe, ni la date de valeur**. Ce sont des paramètres du socle ; les
 * recopier ici garantirait qu'ils divergent un jour, et un écart de frais au
 * guichet est un écart de caisse le soir.
 *
 * Alors l'écran dit ce qu'il sait, et dit ce qu'il ne sait pas encore :
 * en projection, les deux lignes certaines et une mention explicite ; après la
 * comptabilisation, les chiffres réels du reçu.
 */
@Component({
  selector: 'cb-imputation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbAmount, CbSection],
  template: `
    <cb-section titre="Imputation" [indication]="indication()">
      <div class="bloc">
        <div class="ligne">
          <span class="glyphe glyphe--debit">D</span>
          <span class="texte">
            <span class="libelle">Caisse de l'agence</span>
            <span class="compte">compte choisi par le schéma comptable</span>
          </span>
          <span class="montant"><cb-amount [valeur]="montant()" /></span>
        </div>

        <div class="ligne">
          <span class="glyphe glyphe--credit">C</span>
          <span class="texte">
            <span class="libelle">{{ intitule() }}</span>
            <span class="compte cb-mono">{{ compteCode() }}</span>
          </span>
          <span class="montant"><cb-amount [valeur]="montant()" /></span>
        </div>

        @if (recu(); as r) {
          <div class="ligne">
            <span class="glyphe glyphe--debit">D</span>
            <span class="texte">
              <span class="libelle">Commission et taxe</span>
              <span class="compte cb-mono">{{ compteCode() }}</span>
            </span>
            <span class="montant"><cb-amount [valeur]="fraisTotal()" /></span>
          </div>
          <div class="ligne ligne--detail">
            <span class="glyphe-vide"></span>
            <span class="texte"><span class="libelle">dont commission</span></span>
            <span class="montant"><cb-amount [valeur]="r.fee.amount" ton="discret" /></span>
          </div>
          <div class="ligne ligne--detail">
            <span class="glyphe-vide"></span>
            <span class="texte"><span class="libelle">dont taxe sur activités financières</span></span>
            <span class="montant"><cb-amount [valeur]="r.tax.amount" ton="discret" /></span>
          </div>
        }

        <div class="pied">
          @if (recu(); as r) {
            <dl>
              <dt>Pièce</dt><dd class="cb-mono">n° {{ r.entryNumber }}</dd>
              <dt>Comptabilisée le</dt><dd class="cb-mono">{{ jour(r.bookingDate) }}</dd>
              <dt>Date de valeur</dt><dd class="cb-mono">{{ jour(r.valueDate) }}</dd>
              <dt>Solde après</dt>
              <dd><cb-amount [valeur]="r.balanceAfter.amount" [devise]="r.balanceAfter.currency" ton="accent" /></dd>
            </dl>
          } @else {
            <p class="mention">
              Les frais, la taxe et la date de valeur sont calculés par le socle à la comptabilisation,
              d'après le barème du produit. Le poste ne les recalcule pas : un barème recopié finit par
              diverger, et l'écart se paie à l'arrêté de caisse.
            </p>
          }
        </div>
      </div>
    </cb-section>
  `,
  styles: `
    :host { display: block; }
    .bloc {
      display: flex;
      flex-direction: column;
      padding: var(--cb-space-3);
      border: var(--cb-border) solid var(--cb-rule);
      border-radius: var(--cb-radius);
      background: var(--cb-card);
    }
    /* Trois colonnes seulement : le repère débit/crédit, le texte, le montant.
       Le numéro de compte passe sous le libellé — dans une colonne de 400 px,
       une quatrième colonne casserait les libellés mot à mot. */
    .ligne {
      display: grid;
      grid-template-columns: 20px minmax(0, 1fr) minmax(0, max-content);
      align-items: baseline;
      gap: var(--cb-space-2) var(--cb-space-3);
      padding: var(--cb-space-2) 0;
      border-bottom: var(--cb-border) solid var(--cb-rule);
    }
    .ligne--detail { padding-block: var(--cb-space-1); }
    .ligne--detail .libelle { color: var(--cb-muted); font-size: var(--cb-fs-sm); }
    .glyphe, .glyphe-vide { width: 20px; height: 20px; }
    .glyphe {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-family: var(--cb-font-mono);
      font-size: var(--cb-fs-xs);
      box-sizing: border-box;
    }
    .glyphe--debit { background: var(--cb-ink); color: var(--cb-paper); }
    .glyphe--credit { border: var(--cb-border) solid var(--cb-ink); color: var(--cb-ink); }
    .texte { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .libelle { overflow-wrap: anywhere; }
    .compte { font-size: var(--cb-fs-xs); color: var(--cb-muted); overflow-wrap: anywhere; }
    .montant { text-align: right; white-space: nowrap; }

    .pied { padding-top: var(--cb-space-3); }
    dl {
      display: grid;
      grid-template-columns: max-content minmax(0, 1fr);
      gap: var(--cb-space-1) var(--cb-space-3);
      margin: 0;
    }
    dt { color: var(--cb-muted); font-size: var(--cb-fs-sm); }
    dd { margin: 0; text-align: right; }
    .mention { font-size: var(--cb-fs-sm); color: var(--cb-muted); line-height: var(--cb-lh-base); }
  `,
})
export class Imputation {
  readonly montant = input.required<number | null>();
  readonly devise = input.required<string>();
  readonly compteCode = input.required<string>();
  readonly intitule = input.required<string>();
  /** Présent une fois l'opération comptabilisée : les chiffres du socle. */
  readonly recu = input<Recu | null>(null);

  readonly fraisTotal = computed(() => {
    const r = this.recu();
    return r ? Number(r.fee.amount) + Number(r.tax.amount) : 0;
  });

  readonly indication = computed(() =>
    this.recu() ? "écriture passée par le socle" : "projection — le socle fait foi",
  );

  jour(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }
}
