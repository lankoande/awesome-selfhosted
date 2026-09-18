import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { CbConfirm, DemandeConfirmation } from '../../ui';
import { ValidationFactice } from '../validation.factice';
import { VALIDATION } from '../validation.port';
import { FileValidation } from './file-validation.page';

class ConfirmEspion {
  async demander(_demande: DemandeConfirmation): Promise<boolean> {
    return true;
  }
}

async function calme(fixture: ComponentFixture<FileValidation>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

/**
 * Approuver une opération, c'est l'exécuter : il faut le droit de cette
 * opération-là, pas celui de lire la file.
 */
describe('file de validation — le droit porte sur l’opération soumise', () => {
  let fixture: ComponentFixture<FileValidation>;
  let session: AuthentificationDouble;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')]
      .find((b) => b.textContent?.trim().startsWith(libelle));
  }
  async function ouvrir(reference: string): Promise<void> {
    bouton(reference)!.click();
    await calme(fixture);
  }
  async function monter(): Promise<void> {
    const source = new ValidationFactice();
    source.latenceMs = 0;
    TestBed.configureTestingModule({
      providers: [
        { provide: VALIDATION, useValue: source },
        { provide: CbConfirm, useValue: new ConfirmEspion() },
        { provide: AUTHENTIFICATION, useValue: session },
      ],
    });
    fixture = TestBed.createComponent(FileValidation);
    await calme(fixture);
  }

  beforeEach(() => {
    session = new AuthentificationDouble();
  });

  it('refuse la décision sur une opération que le profil ne porte pas, et le dit', async () => {
    // Le profil lit la file, mais ne porte pas le rééchelonnement.
    session.autoriser('ACCOUNT_LIMIT_MANAGE');
    await monter();
    await ouvrir('PND-000102');

    expect(html().textContent).toContain("Cette demande n'est pas pour vous");
    expect(html().textContent).toContain('LOAN_RESCHEDULE');
    expect(bouton('Approuver et exécuter')).toBeUndefined();
  });

  it('laisse décider ce que le profil porte', async () => {
    session.autoriser('LOAN_RESCHEDULE');
    await monter();
    await ouvrir('PND-000102');

    expect(bouton('Approuver et exécuter')).toBeDefined();
  });

  it('laisse la ligne dans la file : elle attend quelqu’un, pas rien', async () => {
    session.autoriser('ACCOUNT_LIMIT_MANAGE');
    await monter();

    // La file est partagée : masquer ferait croire la banque à jour.
    expect(html().textContent).toContain('PND-000102');
    expect(html().textContent).toContain('hors de vos droits');
  });

  it('compte séparément ce qui attend l’équipe et ce qui attend le valideur', async () => {
    session.autoriser('ACCOUNT_LIMIT_MANAGE');
    await monter();
    expect(html().textContent).toMatch(/en attente sur cette page,\s*\d+ pour vous/);
  });

  it('filtre sur demande, et dit que la file en porte d’autres', async () => {
    session.autoriser('AUCUNE_OPERATION');
    await monter();
    bouton('Ce que je peux décider')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('retirez le filtre');
  });

  it('ne filtre rien tant que les habilitations sont inconnues', async () => {
    await monter();
    expect(html().textContent).toContain('PND-000102');
    expect(html().textContent).not.toContain('hors de vos droits');
  });
});
