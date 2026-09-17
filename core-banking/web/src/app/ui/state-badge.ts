import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { EtatOperation, LIBELLE_ETAT } from './etat';

/**
 * Badge d'état. La couleur ne porte jamais l'information seule : le libellé est
 * écrit, et un carré plein sert de repère pour qui distingue mal les teintes.
 */
@Component({
  selector: 'cb-state-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-etat]': 'etat()' },
  template: `<span class="pastille" aria-hidden="true"></span>{{ libelle() }}`,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: var(--cb-space-2);
      height: calc(var(--cb-control-h) - 12px);
      padding: 0 var(--cb-space-2);
      border: var(--cb-border) solid var(--cb-rule-strong);
      border-radius: var(--cb-radius);
      background: var(--cb-panel);
      color: var(--cb-ink-2);
      font-size: var(--cb-fs-xs);
      font-weight: 600;
      letter-spacing: var(--cb-track-caps);
      text-transform: uppercase;
      white-space: nowrap;
    }
    .pastille { width: 6px; height: 6px; background: currentcolor; }

    :host([data-etat='brouillon'])     { color: var(--cb-draft);     background: var(--cb-draft-soft);     border-color: var(--cb-draft-line); }
    :host([data-etat='en-attente'])    { color: var(--cb-pending);   background: var(--cb-pending-soft);   border-color: var(--cb-pending-line); }
    :host([data-etat='comptabilise'])  { color: var(--cb-posted);    background: var(--cb-posted-soft);    border-color: var(--cb-posted-line); }
    :host([data-etat='contre-passe'])  { color: var(--cb-reversed);  background: var(--cb-reversed-soft);  border-color: var(--cb-reversed-line); }
    :host([data-etat='rejete'])        { color: var(--cb-rejected);  background: var(--cb-rejected-soft);  border-color: var(--cb-rejected-line); }
    /* Une approbation non confirmée demande un regard : même famille que
       l'attente. Un échec d'exécution est un échec : même famille qu'un rejet.
       Une expiration n'est ni l'un ni l'autre : elle est neutre et empêchante. */
    :host([data-etat='approuve'])      { color: var(--cb-pending);   background: var(--cb-pending-soft);   border-color: var(--cb-pending-line); }
    :host([data-etat='echoue'])        { color: var(--cb-rejected);  background: var(--cb-rejected-soft);  border-color: var(--cb-rejected-line); }
    :host([data-etat='expire'])        { color: var(--cb-blocked);   background: var(--cb-blocked-soft);   border-color: var(--cb-blocked-line); }
    :host([data-etat='bloque'])        { color: var(--cb-blocked);   background: var(--cb-blocked-soft);   border-color: var(--cb-blocked-line); }
  `,
})
export class CbStateBadge {
  readonly etat = input.required<EtatOperation>();
  readonly libelle = computed(() => LIBELLE_ETAT[this.etat()]);
}
