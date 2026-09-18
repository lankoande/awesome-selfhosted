import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CREDIT } from '../credit.port';
import { CreditDouble, DEMANDE_ID, DOSSIER_DOUBLE } from '../testing/credit-double';
import { DossierCreditPage } from './dossier.page';

async function calme(fixture: ComponentFixture<DossierCreditPage>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

const analyse = (breaches: string[]) => ({
  id: 'a1', assessedOn: '2026-09-04',
  monthlyIncome: { amount: '450000', currency: 'XOF' },
  monthlyCharges: { amount: '120000', currency: 'XOF' },
  existingCommitments: null, downPayment: null, requestedInstalment: null,
  debtServiceRatioPercent: '40.8', externalScore: 612, scoreSource: 'BIC', breaches,
});

describe('crédit — dossier d’instruction', () => {
  let socle: CreditDouble;
  let fixture: ComponentFixture<DossierCreditPage>;

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

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(DossierCreditPage);
    fixture.componentRef.setInput('id', DEMANDE_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new CreditDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CREDIT, useValue: socle }] });
  });

  it("refuse de décider tant qu'aucune analyse n'a été versée", async () => {
    await monter();
    // Une décision sans analyse ne se motive pas devant un contrôle.
    expect(bouton('Décider')).toBeUndefined();
    expect(html().textContent).toContain("Versez d'abord une analyse");
  });

  it('nomme les dépassements de grille au lieu de rendre un verdict', async () => {
    socle.dossierRendu = {
      ...DOSSIER_DOUBLE,
      analyses: [analyse(['Taux d’endettement 40,8 % au-delà du plafond de 35 %.'])],
    };
    await monter();

    const texte = html().textContent ?? '';
    // Virgule décimale : le socle rend « 40.8 », l'écran écrit « 40,8 ».
    expect(texte).toContain('40,8 %');
    expect(texte).not.toContain('40.8 %');
    expect(texte).toContain('Ce que la grille de risque refuse');
    expect(texte).toContain('au-delà du plafond de 35 %');
    // Un dépassement n'interdit pas : il exige une dérogation motivée.
    expect(texte).toContain('dérogation');
  });

  it('sépare les conditions suspensives des résolutoires', async () => {
    socle.dossierRendu = {
      ...DOSSIER_DOUBLE,
      demande: { ...DOSSIER_DOUBLE.demande, status: 'APPROVED' },
      conditions: [
        { id: 'c1', kind: 'PRECEDENT', description: 'Hypothèque de premier rang',
          dueOn: '2026-10-15', clearedOn: null, evidence: null },
        { id: 'c2', kind: 'SUBSEQUENT', description: 'Justificatifs trimestriels',
          dueOn: null, clearedOn: null, evidence: null },
      ],
    };
    await monter();

    const texte = html().textContent ?? '';
    // La suspensive bloque et est nommée ; la résolutoire se suit sans bloquer.
    expect(texte).toContain('Condition suspensive non levée : Hypothèque de premier rang');
    expect(texte).toContain('Engagements à suivre');
    expect(texte).toContain('Justificatifs trimestriels');
    expect(bouton('Établir le contrat')).toBeUndefined();
  });

  it("propose de contracter quand la décision est favorable et les suspensives levées", async () => {
    socle.dossierRendu = {
      ...DOSSIER_DOUBLE,
      demande: { ...DOSSIER_DOUBLE.demande, status: 'APPROVED' },
      conditions: [{ id: 'c1', kind: 'PRECEDENT', description: 'Hypothèque',
                     dueOn: null, clearedOn: '2026-09-20', evidence: 'RCCM 1207' }],
    };
    await monter();

    expect(html().textContent).toContain('La demande peut être contractée');
    expect(bouton('Établir le contrat')).toBeDefined();
  });

  it('annonce le second regard AVANT de décider, et rend une opération en attente', async () => {
    socle.dossierRendu = { ...DOSSIER_DOUBLE, analyses: [analyse([])] };
    await monter();

    bouton('Décider')!.click();
    await calme(fixture);
    expect(html().textContent).toContain('La décision passe par un second regard');

    saisir('#motif', 'Capacité confirmée.');
    await calme(fixture);
    bouton('Soumettre la décision')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('En attente de validation');
    expect(html().textContent).toContain('PND-000401');
    expect(socle.decisions[0].reason).toBe('Capacité confirmée.');
  });

  it('renouvelle la clé entre deux actes distincts', async () => {
    socle.dossierRendu = { ...DOSSIER_DOUBLE, analyses: [analyse([])] };
    await monter();

    bouton('Décider')!.click();
    await calme(fixture);
    saisir('#motif', 'Premier envoi.');
    await calme(fixture);
    bouton('Soumettre la décision')!.click();
    await calme(fixture);

    bouton('Décider')!.click();
    await calme(fixture);
    saisir('#motif', 'Second envoi.');
    await calme(fixture);
    bouton('Soumettre la décision')!.click();
    await calme(fixture);

    expect(socle.clesDecision.length).toBe(2);
    expect(socle.clesDecision[0]).not.toBe(socle.clesDecision[1]);
  });

  it("montre le refus du socle sans effacer la saisie", async () => {
    socle.dossierRendu = { ...DOSSIER_DOUBLE, analyses: [analyse([])] };
    socle.issueDecision = async () => {
      throw new RefusMetier(422, 'PLAFOND', 'Montant au-delà de votre plafond.');
    };
    await monter();

    bouton('Décider')!.click();
    await calme(fixture);
    saisir('#motif', 'Dossier solide.');
    await calme(fixture);
    bouton('Soumettre la décision')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('Montant au-delà de votre plafond');
    // Le volet reste ouvert : la saisie n'est pas perdue.
    expect(html().querySelector<HTMLInputElement>('#motif')?.value).toBe('Dossier solide.');
  });

  it("dit que le dossier est clos quand la demande est contractée", async () => {
    socle.dossierRendu = {
      ...DOSSIER_DOUBLE,
      demande: { ...DOSSIER_DOUBLE.demande, status: 'CONTRACTED', contractId: 'c-1' },
    };
    await monter();

    expect(html().textContent).toContain('Demande contractée');
    expect(bouton('Décider')).toBeUndefined();
  });
});
