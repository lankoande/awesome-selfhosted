import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CLIENTS } from '../clients.port';
import { ClientsDouble } from '../testing/clients-double';
import { NouveauClient } from './nouveau.page';

async function calme(fixture: ComponentFixture<NouveauClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('clients — nouveau client', () => {
  let socle: ClientsDouble;
  let fixture: ComponentFixture<NouveauClient>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function saisir(selecteur: string, valeur: string): void {
    const champ = html().querySelector<HTMLInputElement>(selecteur)!;
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
  }

  beforeEach(async () => {
    socle = new ClientsDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CLIENTS, useValue: socle }] });
    fixture = TestBed.createComponent(NouveauClient);
    await calme(fixture);
  });

  it("n'envoie rien sans nom ni pays valide", async () => {
    expect(bouton('Créer le client')?.disabled ?? bouton('Créer')?.disabled).toBe(true);

    saisir('#nom', 'KABORE Adama');
    saisir('#pays', 'XX1');
    await calme(fixture);
    expect(html().textContent).toContain('code ISO de deux lettres');
  });

  it("dit à la création qu'un client naît non vérifié", async () => {
    saisir('#nom', 'KABORE Adama');
    await calme(fixture);
    (bouton('Créer le client') ?? bouton('Créer'))!.click();
    await calme(fixture);

    // Laisser croire qu'un compte s'ouvrira dans la foulée fait revenir le
    // client pour rien.
    expect(html().textContent).toContain('Client créé');
    expect(html().textContent).toContain('connaissance client est');
    expect(socle.creations[0].displayName).toBe('KABORE Adama');
  });

  it("n'envoie que l'identité : ni pièce, ni niveau de connaissance client", async () => {
    saisir('#nom', 'KABORE Adama');
    await calme(fixture);
    (bouton('Créer le client') ?? bouton('Créer'))!.click();
    await calme(fixture);

    expect(Object.keys(socle.creations[0]).sort()).toEqual([
      'birthOrRegistrationDate', 'countryCode', 'displayName', 'kind',
      'legalEntityId', 'reference', 'segment',
    ]);
  });

  it("ne propose AUCUN rejeu quand l'issue est incertaine : renvoyer ferait un doublon", async () => {
    socle.issueCreation = async () => {
      throw new RefusMetier(0, 'RESEAU', 'Le socle est injoignable.');
    };
    saisir('#nom', 'KABORE Adama');
    await calme(fixture);
    (bouton('Créer le client') ?? bouton('Créer'))!.click();
    await calme(fixture);

    expect(html().textContent).toContain('Le client a peut-être été créé');
    expect(bouton('Réessayer')).toBeUndefined();
    expect(bouton('Chercher')).toBeDefined();
  });

  it('laisse reprendre la saisie quand le socle a refusé pour de bon', async () => {
    socle.issueCreation = async () => {
      throw new RefusMetier(422, 'NOM_INVALIDE', 'Le nom est refusé.');
    };
    saisir('#nom', 'K');
    saisir('#nom', 'KABORE Adama');
    await calme(fixture);
    (bouton('Créer le client') ?? bouton('Créer'))!.click();
    await calme(fixture);

    expect(html().textContent).not.toContain('Le client a peut-être été créé');
    expect(bouton('Reprendre la saisie')).toBeDefined();
  });
});
