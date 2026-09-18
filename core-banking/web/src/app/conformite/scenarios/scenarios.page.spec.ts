import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CONFORMITE } from '../conformite.port';
import { ConformiteDouble } from '../testing/conformite-double';
import { Scenarios } from './scenarios.page';

async function calme(fixture: ComponentFixture<Scenarios>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('conformité — scénarios de surveillance', () => {
  let socle: ConformiteDouble;
  let fixture: ComponentFixture<Scenarios>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function saisir(id: string, valeur: string): void {
    const champ = html().querySelector<HTMLInputElement>(`#${id}`);
    if (!champ) throw new Error(`champ ${id} absent`);
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(Scenarios);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ConformiteDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CONFORMITE, useValue: socle }] });
  });

  it('ne demande que ce que la méthode exige', async () => {
    await monter();
    bouton('Déclarer un scénario')?.click();
    await calme(fixture);
    // Seuil d'espèces : un seuil et une fenêtre, pas de nombre minimal ni de facteur.
    expect(html().querySelector('#seuil-scenario')).not.toBeNull();
    expect(html().querySelector('#fenetre-scenario')).not.toBeNull();
    expect(html().querySelector('#minimum-scenario')).toBeNull();
    expect(html().querySelector('#facteur-scenario')).toBeNull();

    bouton('Fractionnement')?.click();
    fixture.detectChanges();
    expect(html().querySelector('#minimum-scenario')).not.toBeNull();

    bouton('Activité atypique')?.click();
    fixture.detectChanges();
    expect(html().querySelector('#facteur-scenario')).not.toBeNull();
    expect(html().querySelector('#seuil-scenario')).toBeNull();
  });

  it('efface les paramètres quand la méthode change : ils ne veulent plus dire la même chose',
     async () => {
    await monter();
    bouton('Déclarer un scénario')?.click();
    await calme(fixture);
    saisir('fenetre-scenario', '30');
    bouton('Réveil de compte dormant')?.click();
    fixture.detectChanges();
    bouton("Seuil d'espèces")?.click();
    fixture.detectChanges();
    expect(html().querySelector<HTMLInputElement>('#fenetre-scenario')?.value).toBe('');
  });

  it('ne gronde pas un formulaire vierge', async () => {
    await monter();
    bouton('Déclarer un scénario')?.click();
    await calme(fixture);
    expect(html().textContent).not.toContain('Il manque quelque chose');
  });

  it('nomme ce qui manque plutôt que de laisser partir un scénario incomplet', async () => {
    await monter();
    bouton('Déclarer un scénario')?.click();
    await calme(fixture);
    saisir('code-scenario', 'ESP-9M');
    bouton('Soumettre le scénario')?.click();
    await calme(fixture);
    expect(socle.scenariosDeclares.length).toBe(0);
    expect(html().textContent).toContain('Il manque quelque chose');
  });

  it('soumet un scénario complet et annonce qu’il ne surveille rien avant approbation',
     async () => {
    await monter();
    bouton('Déclarer un scénario')?.click();
    await calme(fixture);
    saisir('code-scenario', 'ESP-9M');
    saisir('libelle-scenario', 'Espèces au-delà de 9 000 000 sur 30 jours');
    saisir('seuil-scenario', '9000000');
    saisir('fenetre-scenario', '30');
    saisir('effet-scenario', '01/10/2026'); // le champ se saisit en jj/mm/aaaa
    bouton('Soumettre le scénario')?.click();
    await calme(fixture);
    expect(socle.scenariosDeclares.length).toBe(1);
    expect(socle.scenariosDeclares[0].code).toBe('ESP-9M');
    expect(socle.scenariosDeclares[0].thresholdAmount).toBe('9000000');
    expect(html().textContent).toContain('le scénario ne surveille rien');
  });

  it('avertit quand aucun scénario n’est déclaré : la surveillance ne lève rien', async () => {
    socle.scenariosRendus = [];
    await monter();
    expect(html().textContent).toContain('ne lève rien');
  });

  it('dit pourquoi un scénario se déclare à deux', async () => {
    await monter();
    expect(html().textContent).toContain('ne regardera pas');
  });
});
