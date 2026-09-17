import { Directive } from '@angular/core';

/**
 * Table dense. Un vrai `<table>` : l'en-tête est annoncé avec chaque cellule,
 * la sélection au clavier fonctionne, et le copier-coller vers un tableur
 * garde les colonnes — ce qu'un empilement de `<div>` perd toujours.
 *
 * Les colonnes de chiffres portent `class="cb-num"`, les références `class="cb-ref"`.
 */
@Directive({
  selector: 'table[cbTable]',
  host: { class: 'cb-table' },
})
export class CbTable {}
