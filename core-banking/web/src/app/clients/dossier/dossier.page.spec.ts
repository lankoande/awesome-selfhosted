import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CLIENTS } from '../clients.port';
import { ClientsDouble, TIERS_DOUBLE_ID } from '../testing/clients-double';
import { DossierClient } from './dossier.page';

async function calme(fixture: ComponentFixture<DossierClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('clients — dossier', () => {
  let socle: ClientsDouble;
  let fixture: ComponentFixture<DossierClient>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Le double est fourni par référence : on le règle, puis on monte. */
  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(DossierClient);
    fixture.componentRef.setInput('id', TIERS_DOUBLE_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ClientsDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CLIENTS, useValue: socle }] });
  });

  it("répond d'abord à « puis-je ouvrir un compte ? »", async () => {
    await monter();
    const texte = html().textContent ?? '';

    // C'est la question qu'on vient poser ; elle passe avant l'inventaire des
    // pièces.
    expect(texte).toContain('Ce client peut recevoir un nouveau compte');
    expect(texte.indexOf('Ce client peut recevoir un nouveau compte'))
      .toBeLessThan(texte.indexOf('Pièces du dossier'));
  });

  it('nomme ce qui manque plutôt que de dire « dossier incomplet »', async () => {
    socle.dossierRendu = {
      ...socle.dossierRendu,
      complete: false,
      missing: ['ADDRESS_PROOF'],
      expired: ['IDENTITY'],
    };
    await monter();

    const texte = html().textContent ?? '';
    expect(texte).toContain('justificatif de domicile');
    expect(texte).toContain("pièce d'identité");
  });

  it('conserve une pièce remplacée au dossier au lieu de la faire disparaître', async () => {
    socle.piecesRendues = [
      { id: 'p-1', kind: 'IDENTITY', reference: 'CNIB-2019', issuer: 'ONI',
        issuedOn: '2019-02-01', expiresOn: '2029-02-01', collectedOn: '2019-03-04',
        supersededBy: 'p-2' },
      { id: 'p-2', kind: 'IDENTITY', reference: 'CNIB-2024', issuer: 'ONI',
        issuedOn: '2024-02-01', expiresOn: '2034-02-01', collectedOn: '2024-03-04',
        supersededBy: null },
    ];
    await monter();

    // Un dossier client se relit des années après : une pièce disparue est une
    // question sans réponse.
    const texte = html().textContent ?? '';
    expect(texte).toContain('CNIB-2019');
    expect(texte).toContain('CNIB-2024');
    expect(html().querySelectorAll('tr.remplacee').length).toBe(1);
  });

  it("ne montre pas les bénéficiaires effectifs d'une personne physique", async () => {
    // La notion ne s'applique pas : une rubrique vide ferait croire à un oubli.
    await monter();
    expect(html().textContent).not.toContain('Bénéficiaires effectifs');
  });

  it('montre les bénéficiaires effectifs d’une personne morale', async () => {
    socle.tiers = { ...socle.tiers, kind: 'LEGAL_PERSON' };
    socle.beneficiairesRendus = [{
      id: 'b-1', ownerName: 'KABORE Adama', ownershipPercent: '60',
      ownerReference: 'CL-0004118', declaredOn: '2026-01-20', validTo: null,
    }];
    await monter();

    expect(html().textContent).toContain('Bénéficiaires effectifs');
    expect(html().textContent).toContain('KABORE Adama');
    expect(html().textContent).toContain('60 %');
  });
});
