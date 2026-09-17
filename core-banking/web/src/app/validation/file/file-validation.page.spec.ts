import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CbConfirm, DemandeConfirmation } from '../../ui';
import { ValidationFactice } from '../validation.factice';
import { VALIDATION } from '../validation.port';
import { FileValidation } from './file-validation.page';

class ConfirmEspion {
  demandes: DemandeConfirmation[] = [];
  reponse = true;
  async demander(demande: DemandeConfirmation): Promise<boolean> {
    this.demandes.push(demande);
    return this.reponse;
  }
}

async function calme(fixture: ComponentFixture<FileValidation>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('file de validation', () => {
  let fixture: ComponentFixture<FileValidation>;
  let confirm: ConfirmEspion;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
  }
  async function ouvrir(reference: string): Promise<void> {
    bouton(reference)!.click();
    await calme(fixture);
  }

  beforeEach(async () => {
    const source = new ValidationFactice();
    source.latenceMs = 0;
    confirm = new ConfirmEspion();
    TestBed.configureTestingModule({
      providers: [
        { provide: VALIDATION, useValue: source },
        { provide: CbConfirm, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(FileValidation);
    await calme(fixture);
  });

  it('affiche la file et rappelle que la requête est rejouée à l’approbation', () => {
    expect(html().textContent).toContain('PND-000101');
    expect(html().textContent).toContain('rejouée à l');
  });

  it("n’offre pas de décider sa propre demande, et dit pourquoi", async () => {
    await ouvrir('PND-000103');

    expect(html().textContent).toContain('Vous avez soumis cette opération');
    expect(bouton('Approuver et exécuter')).toBeUndefined();
    expect(bouton('Rejeter…')).toBeUndefined();
  });

  it("met l’avertissement de rejeu sur la confirmation, pas dans une note de bas de page", async () => {
    await ouvrir('PND-000101');
    bouton('Approuver et exécuter')!.click();
    await calme(fixture);

    expect(confirm.demandes).toHaveLength(1);
    expect(confirm.demandes[0]!.consequence).toContain('rejoue');
    expect(html().textContent).toContain('Décision enregistrée');
  });

  it("n’approuve rien si la confirmation est refusée", async () => {
    confirm.reponse = false;
    await ouvrir('PND-000101');
    bouton('Approuver et exécuter')!.click();
    await calme(fixture);

    expect(html().textContent).not.toContain('Décision enregistrée');
    expect(bouton('Approuver et exécuter')).toBeTruthy();
  });

  it("montre l’état réel quand l’exécution refuse après approbation", async () => {
    await ouvrir('PND-000102');
    bouton('Approuver et exécuter')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('EXECUTION_REFUSEE');
    expect(html().textContent).toContain('Échouée');
    expect(html().textContent).toContain('le demandeur resoumet');
    // La décision est prise : on ne repropose pas de décider.
    expect(bouton('Approuver et exécuter')).toBeUndefined();
  });

  it('exige un motif avant de confirmer un rejet', async () => {
    await ouvrir('PND-000101');
    bouton('Rejeter…')!.click();
    await calme(fixture);

    expect(bouton('Confirmer le rejet')?.disabled).toBe(true);

    const motif = html().querySelector<HTMLTextAreaElement>('#motif')!;
    motif.value = 'Plafond incohérent avec le profil déclaré.';
    motif.dispatchEvent(new Event('input'));
    await calme(fixture);

    expect(bouton('Confirmer le rejet')?.disabled).toBe(false);
    bouton('Confirmer le rejet')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('Décision enregistrée');
    expect(html().textContent).toContain('Plafond incohérent avec le profil déclaré.');
  });

  it("ne propose pas de décider une opération expirée, et l’explique", async () => {
    await ouvrir('PND-000104');

    expect(html().textContent).toContain('Échéance dépassée');
    expect(bouton('Approuver et exécuter')).toBeUndefined();
  });
});
