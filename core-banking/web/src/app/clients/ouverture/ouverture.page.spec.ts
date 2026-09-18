import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CLIENTS } from '../clients.port';
import { ClientsDouble, TIERS_DOUBLE_ID } from '../testing/clients-double';
import { OuvertureCompte } from './ouverture.page';

async function calme(fixture: ComponentFixture<OuvertureCompte>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe("clients — ouverture de compte", () => {
  let socle: ClientsDouble;
  let fixture: ComponentFixture<OuvertureCompte>;

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
    TestBed.configureTestingModule({ providers: [{ provide: CLIENTS, useValue: socle }] });
    fixture = TestBed.createComponent(OuvertureCompte);
    fixture.componentRef.setInput('id', TIERS_DOUBLE_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ClientsDouble();
  });

  it("annonce le second regard AVANT l'envoi, pas après", async () => {
    await monter();
    // Le contrat ne déclare que 202 : promettre un compte immédiat puis afficher
    // « en attente » est la meilleure façon de faire resaisir la demande.
    expect(html().textContent).toContain("L'ouverture passe par un second regard");
    expect(bouton("Soumettre l'ouverture")).toBeDefined();
  });

  it('ne reproche rien tant que rien n’a été saisi', async () => {
    await monter();
    // Un formulaire vierge qui affiche déjà « le code produit est obligatoire »
    // gronde l'opérateur avant qu'il ait touché un champ.
    expect(html().textContent).not.toContain('Le code produit est obligatoire');

    saisir('#produit', 'C');
    saisir('#produit', '');
    await calme(fixture);
    expect(html().textContent).toContain('Le code produit est obligatoire');
  });

  it("n'ouvre rien tant que le dossier n'est pas en règle, et dit pourquoi", async () => {
    socle.dossierRendu = { ...socle.dossierRendu, complete: false, missing: ['ADDRESS_PROOF'] };
    await monter();

    expect(html().textContent).toContain('Aucun compte ne peut être ouvert à ce client');
    expect(html().textContent).toContain('justificatif de domicile');
    saisir('#produit', 'CPTE-CHQ-PART');
    await calme(fixture);
    expect(bouton("Soumettre l'ouverture")?.disabled).toBe(true);
  });

  it('propose les produits ouvrables et reprend la devise du produit choisi', async () => {
    socle.produitsRendus = [
      { code: 'CPTE-CHQ-PART', libelle: 'Compte chèque particulier', devise: 'XOF' },
      { code: 'CPTE-DEVISE-EUR', libelle: 'Compte en devise', devise: 'EUR' },
    ];
    await monter();

    const produit = html().querySelector<HTMLSelectElement>('#produit')!;
    expect(produit.tagName).toBe('SELECT');
    produit.value = 'CPTE-DEVISE-EUR';
    produit.dispatchEvent(new Event('change'));
    await calme(fixture);

    // La devise appartient au produit : la laisser libre produirait un couple
    // impossible, refusé par le socle après que le client a signé.
    const devise = html().querySelector<HTMLInputElement>('#devise')!;
    expect(devise.value).toBe('EUR');
    expect(devise.disabled).toBe(true);
  });

  it("rend l'identifiant de l'opération en attente, jamais un numéro de compte", async () => {
    await monter();
    saisir('#produit', 'CPTE-CHQ-PART');
    await calme(fixture);
    bouton("Soumettre l'ouverture")!.click();
    await calme(fixture);

    expect(html().textContent).toContain('En attente de validation');
    expect(html().textContent).toContain('PND-000207');
    expect(html().textContent).toContain("aucun compte n'est ouvert");
    expect(socle.ouvertures[0].productCode).toBe('CPTE-CHQ-PART');
    // Le numéro de compte est composé par le socle : l'écran ne l'invente pas.
    expect(socle.ouvertures[0].code).toBeNull();
  });

  it("ne propose AUCUN rejeu quand l'issue est incertaine : la demande a pu passer", async () => {
    socle.issueOuverture = async () => {
      throw new RefusMetier(0, 'RESEAU', 'Le socle est injoignable.');
    };
    await monter();
    saisir('#produit', 'CPTE-CHQ-PART');
    await calme(fixture);
    bouton("Soumettre l'ouverture")!.click();
    await calme(fixture);

    // Sans clé d'idempotence honorée sur cette route, rejouer ouvrirait un
    // second compte au même client.
    expect(html().textContent).toContain('La demande a peut-être été enregistrée');
    expect(bouton('Réessayer')).toBeUndefined();
    expect(bouton('Voir la file de validation')).toBeDefined();
  });

  it("laisse reprendre la saisie quand le socle a refusé pour de bon", async () => {
    socle.issueOuverture = async () => {
      throw new RefusMetier(422, 'PRODUIT_INCONNU', 'Ce produit n’existe pas.');
    };
    await monter();
    saisir('#produit', 'INEXISTANT');
    await calme(fixture);
    bouton("Soumettre l'ouverture")!.click();
    await calme(fixture);

    expect(html().textContent).not.toContain('La demande a peut-être été enregistrée');
    expect(bouton('Reprendre la saisie')).toBeDefined();
  });

  it('renouvelle la clé quand la saisie change, la conserve quand elle ne change pas', async () => {
    socle.issueOuverture = async () => {
      throw new RefusMetier(422, 'PRODUIT_INCONNU', 'Ce produit n’existe pas.');
    };
    await monter();
    saisir('#produit', 'CPTE-A');
    await calme(fixture);
    bouton("Soumettre l'ouverture")!.click();
    await calme(fixture);

    bouton('Reprendre la saisie')!.click();
    await calme(fixture);
    saisir('#produit', 'CPTE-B');
    await calme(fixture);
    bouton("Soumettre l'ouverture")!.click();
    await calme(fixture);

    expect(socle.clesOuverture.length).toBe(2);
    expect(socle.clesOuverture[0]).not.toBe(socle.clesOuverture[1]);
  });
});
