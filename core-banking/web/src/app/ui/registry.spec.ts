import { TestBed } from '@angular/core/testing';
import { Atelier } from '../atelier/atelier';
import { PRIMITIVES_UI } from './registry';

/**
 * Le garde-fou de l'atelier, du même ordre que le test de contrat OpenAPI :
 * un mécanisme, pas une intention.
 *
 * Deux sens, tous les deux vérifiés :
 *   - toute primitive déclarée au registre a sa section dans l'atelier ;
 *   - tout fichier de `ui/` est déclaré au registre.
 * Ajouter un composant sans le montrer fait échouer le build.
 */

/** Fichiers d'infrastructure du dossier `ui/` : ni primitives, ni à déclarer. */
const HORS_REGISTRE = new Set(['index', 'registry', 'etat']);

describe('jeu fermé de primitives', () => {
  it('montre chaque primitive du registre dans l’atelier', async () => {
    const fixture = TestBed.createComponent(Atelier);
    await fixture.whenStable();
    const racine = fixture.nativeElement as HTMLElement;

    for (const primitive of PRIMITIVES_UI) {
      expect(racine.querySelector(`section#${primitive.id}`), `section manquante : ${primitive.id}`).toBeTruthy();
    }
  });

  it('déclare au registre chaque fichier de ui/', () => {
    // @ts-expect-error — API du lanceur de tests, résolue à la compilation.
    const fichiers: Record<string, unknown> = import.meta.glob('./*.ts');

    const souches = Object.keys(fichiers)
      .map((chemin) => chemin.replace(/^\.\//, '').replace(/\.ts$/, ''))
      .filter((souche) => !souche.endsWith('.spec'))
      .filter((souche) => !HORS_REGISTRE.has(souche));

    const declarees = new Set(PRIMITIVES_UI.map((primitive) => primitive.id));
    for (const souche of souches) {
      expect(declarees.has(souche), `primitive non déclarée au registre : ui/${souche}.ts`).toBe(true);
    }
    expect(souches.length).toBe(PRIMITIVES_UI.length);
  });
});
