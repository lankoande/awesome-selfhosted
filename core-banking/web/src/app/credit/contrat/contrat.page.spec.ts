import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CREDIT } from '../credit.port';
import { CONTRAT_DOUBLE, CONTRAT_ID, CreditDouble } from '../testing/credit-double';
import { ContratCredit } from './contrat.page';

async function calme(fixture: ComponentFixture<ContratCredit>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('crédit — contrat', () => {
  let socle: CreditDouble;
  let fixture: ComponentFixture<ContratCredit>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(ContratCredit);
    fixture.componentRef.setInput('id', CONTRAT_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new CreditDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CREDIT, useValue: socle }] });
  });

  it("montre l'exigible sans y compter le capital non échu", async () => {
    await monter();
    // Les séparateurs de milliers sont des espaces insécables : on compare les
    // chiffres, pas la typographie du format.
    const chiffres = (html().textContent ?? '').replace(/[\s\u00a0\u202f]/g, '');
    // 73 229 + 164 338 = 237 567 ; les 9 335 662 de capital à venir n'en sont pas.
    expect(chiffres).toContain('237567');
    expect(chiffres).not.toContain('9573229');
  });

  it("place les créances exigibles avant l'échéancier", async () => {
    await monter();
    const texte = html().textContent ?? '';
    // Au guichet on demande ce qui est dû, pas ce qui était prévu.
    expect(texte).toContain('Créances exigibles');
    expect(texte.indexOf('Créances exigibles')).toBeLessThan(texte.indexOf('Revenir au portefeuille'));
  });

  it('annonce le second regard avant de débloquer, et ne débloque rien à l’envoi', async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'DRAFT', echeancier: [], creances: [] };
    await monter();

    expect(html().textContent).toContain("Le contrat n'est pas débloqué");
    bouton('Débloquer les fonds')!.click();
    await calme(fixture);
    expect(html().textContent).toContain('Le déblocage passe par un second regard');

    bouton('Soumettre le déblocage')!.click();
    await calme(fixture);
    expect(html().textContent).toContain("l'argent n'est pas sorti");
    expect(html().textContent).toContain('PND-000501');
  });

  it("rend l'imputation faite par le socle, ligne par ligne", async () => {
    await monter();
    bouton('Enregistrer un règlement')!.click();
    await calme(fixture);

    const montant = html().querySelector<HTMLInputElement>('#montant-reglement')!;
    montant.value = '250000';
    montant.dispatchEvent(new Event('input'));
    await calme(fixture);
    bouton('Enregistrer le règlement')!.click();
    await calme(fixture);

    const texte = html().textContent ?? '';
    // Le guichetier doit pouvoir expliquer où est allé l'argent.
    expect(texte).toContain('Règlement imputé');
    expect(texte).toContain('Intérêts');
    expect(texte).toContain('Capital échu');
    expect(texte).toContain('Non imputé');
    expect(socle.reglements[0].amount).toBe('250000');
    expect(socle.reglements[0].currency).toBe('XOF');
  });

  it("ne propose ni règlement ni déblocage sur un contrat soldé", async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'CLOSED', creances: [] };
    await monter();

    expect(bouton('Enregistrer un règlement')).toBeUndefined();
    expect(bouton('Débloquer les fonds')).toBeUndefined();
  });

  it('propose les DEUX modes de remboursement anticipé, et dit ce qu’ils changent', async () => {
    await monter();
    bouton('Remboursement anticipé')!.click();
    await calme(fixture);

    const texte = html().textContent ?? '';
    // Ne proposer que l'un des deux est un défaut fonctionnel courant : à
    // capital égal, raccourcir la durée économise bien plus qu'abaisser
    // l'échéance, et le choix appartient à l'emprunteur.
    expect(texte).toContain('Raccourcir la durée');
    expect(texte).toContain("Abaisser l'échéance");
    expect(texte).toContain('les intérêts cessent de courir plus tôt');
    expect(texte).toContain('coûte davantage au total');
    // L'indemnité n'est pas calculée par le poste.
    expect(texte).toContain("l'indemnité");
  });

  it("envoie le mode choisi, et rend une opération en attente", async () => {
    await monter();
    bouton('Remboursement anticipé')!.click();
    await calme(fixture);

    const montant = html().querySelector<HTMLInputElement>('#montant-anticipe')!;
    montant.value = '500000';
    montant.dispatchEvent(new Event('input'));
    const reduire = [...html().querySelectorAll<HTMLInputElement>('input[name="mode-anticipe"]')]
      .find((r) => r.value === 'REDUCE_INSTALMENT')!;
    reduire.checked = true;
    reduire.dispatchEvent(new Event('change'));
    await calme(fixture);
    bouton('Soumettre le remboursement')!.click();
    await calme(fixture);

    expect(socle.anticipes[0].mode).toBe('REDUCE_INSTALMENT');
    expect(socle.anticipes[0].amount).toBe('500000');
    expect(html().textContent).toContain('PND-000601');
  });

  it('exige un motif pour rééchelonner : il se relit lors d’un contrôle', async () => {
    await monter();
    bouton('Rééchelonner')!.click();
    await calme(fixture);

    const echeances = html().querySelector<HTMLInputElement>('#nouvelles-echeances')!;
    echeances.value = '60';
    echeances.dispatchEvent(new Event('input'));
    await calme(fixture);
    expect(bouton('Soumettre le rééchelonnement')?.disabled).toBe(true);

    const motif = html().querySelector<HTMLInputElement>('#motif-reechelonnement')!;
    motif.value = 'Baisse de revenus attestée.';
    motif.dispatchEvent(new Event('input'));
    await calme(fixture);
    expect(bouton('Soumettre le rééchelonnement')?.disabled).toBe(false);

    bouton('Soumettre le rééchelonnement')!.click();
    await calme(fixture);
    expect(socle.reechelonnements[0].instalments).toBe(60);
    expect(socle.reechelonnements[0].reason).toBe('Baisse de revenus attestée.');
  });

  it("renvoie vers l'écran de perte plutôt que d'y loger un bouton de plus", async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    await monter();

    // Un passage en perte se décide avec la provision sous les yeux.
    expect(html().textContent).toContain('Contrat passé en perte');
    expect(html().textContent).toContain('la créance reste due');
    expect(bouton('Voir la perte et les recouvrements')).toBeDefined();
  });

  it('marque la gravité du retard', async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, daysPastDue: 104 };
    await monter();

    expect(html().textContent).toContain('104 jours');
    expect(html().querySelector('[data-gravite="douteux"]')).not.toBeNull();
  });
});
