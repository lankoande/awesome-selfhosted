import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { REGLEMENTAIRE } from '../reglementaire.port';
import { ETAT_DOUBLE, ETAT_ID, ReglementaireDouble } from '../testing/reglementaire-double';
import { DetailEtat } from './etat.page';

async function calme(fixture: ComponentFixture<DetailEtat>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('réglementaire — détail d’un état', () => {
  let socle: ReglementaireDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<DetailEtat>;

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
    fixture = TestBed.createComponent(DetailEtat);
    fixture.componentRef.setInput('id', ETAT_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ReglementaireDouble();
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }]), { provide: REGLEMENTAIRE, useValue: socle },
        { provide: AUTHENTIFICATION, useValue: session }],
    });
  });

  it('montre le seuil du jour de la production, et dit pourquoi', async () => {
    socle.dossierRendu = { etat: { ...ETAT_DOUBLE, thresholdUsed: '5000000' }, ecarts: [] };
    await monter();
    expect(html().textContent).toContain('celui du jour de la production');
  });

  it('ferme la transmission quand l’état porte des anomalies, et le dit', async () => {
    socle.dossierRendu = {
      etat: { ...ETAT_DOUBLE, anomalies: ['Balance déséquilibrée de 12 400 XOF.'] },
      ecarts: [],
    };
    await monter();
    const texte = html().textContent ?? '';
    expect(texte).toContain('Balance déséquilibrée de 12 400 XOF.');
    expect(texte).toContain('comptes dont on sait');
    expect(bouton('Enregistrer le dépôt')).toBeUndefined();
  });

  it('refuse d’annuler un état transmis et donne la bonne conduite', async () => {
    socle.dossierRendu = {
      etat: { ...ETAT_DOUBLE, status: 'TRANSMITTED', transmittedOn: '2026-09-08',
              transmissionReference: 'BCEAO/2026/08/0417' },
      ecarts: [],
    };
    await monter();
    expect(html().textContent).toContain('rectifie par un dépôt suivant');
    expect(bouton('Annuler cet état')).toBeUndefined();
  });

  it('signale un état transmis qui ne se reproduit plus', async () => {
    socle.dossierRendu = {
      etat: { ...ETAT_DOUBLE, status: 'TRANSMITTED', transmittedOn: '2026-09-08' },
      ecarts: ['Ligne 101 : 84 200 000 déposé, 84 600 000 recalculé.'],
    };
    await monter();
    const texte = html().textContent ?? '';
    expect(texte).toContain("ne se reproduit plus à l'identique");
    expect(texte).toContain('quelque chose a bougé derrière lui');
  });

  it('confirme la reproductibilité quand il n’y a aucun écart', async () => {
    socle.dossierRendu = {
      etat: { ...ETAT_DOUBLE, status: 'TRANSMITTED', transmittedOn: '2026-09-08' },
      ecarts: [],
    };
    await monter();
    expect(html().textContent).toContain("se reproduit à l'identique");
  });

  it('exige le récépissé pour soumettre la transmission', async () => {
    await monter();
    bouton('Enregistrer le dépôt')?.click();
    await calme(fixture);
    expect(bouton('Soumettre la transmission')?.disabled).toBe(true);
    saisir('reference-depot', 'BCEAO/2026/08/0417');
    expect(bouton('Soumettre la transmission')?.disabled).toBe(false);
  });

  it('annonce que rien n’est déposé tant qu’un second regard n’a pas approuvé', async () => {
    await monter();
    bouton('Enregistrer le dépôt')?.click();
    await calme(fixture);
    saisir('reference-depot', 'BCEAO/2026/08/0417');
    bouton('Soumettre la transmission')?.click();
    await calme(fixture);
    expect(socle.transmissions[0].reference).toBe('BCEAO/2026/08/0417');
    expect(html().textContent).toContain("rien n'est déposé");
  });

  it('ferme la transmission à qui ne la porte pas, et dit ce que son profil fait', async () => {
    // Produire et transmettre sont deux droits : c'est le cas que l'habilitation
    // « par écran » rate, et celui que cet écran doit rendre lisible.
    session.autoriser('REGULATORY_READ', 'REGULATORY_REPORT_PRODUCE');
    await monter();
    expect(bouton('Enregistrer le dépôt')).toBeUndefined();
    expect(html().textContent).toContain('ne transmet pas : il produit');
  });

  it('ferme la reprise au même profil : reprendre demande le droit de transmettre', async () => {
    session.autoriser('REGULATORY_READ', 'REGULATORY_REPORT_PRODUCE');
    await monter();
    expect(bouton('Annuler cet état')).toBeUndefined();
    expect(html().textContent).toContain('demande le droit de transmettre');
  });

  it('ouvre les deux à qui porte la transmission', async () => {
    session.autoriser('REGULATORY_READ', 'REGULATORY_REPORT_TRANSMIT');
    await monter();
    expect(bouton('Enregistrer le dépôt')).toBeDefined();
    expect(bouton('Annuler cet état')).toBeDefined();
  });

  it('exige un motif pour annuler, et le transmet', async () => {
    await monter();
    bouton('Annuler cet état')?.click();
    await calme(fixture);
    expect(bouton("Annuler l'état")?.disabled).toBe(true);
    saisir('motif-annulation', 'Écriture de régularisation passée après production.');
    bouton("Annuler l'état")?.click();
    await calme(fixture);
    expect(socle.annulations).toEqual([
      { filingId: ETAT_ID, motif: 'Écriture de régularisation passée après production.' },
    ]);
  });
});
