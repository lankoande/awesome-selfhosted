import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Tranche, VersionComplete, VersionProduit } from '../modele/produits.modele';
import { SIEGE } from '../siege.port';
import {
  SiegeDouble, VERSION_BROUILLON, VERSION_EN_VIGUEUR,
} from '../testing/siege-double';
import { Produits } from './produits.page';

type Fixture = ComponentFixture<Produits>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  famille: { set(v: string): void };
  code: { set(v: string): void };
  intitule: { set(v: string): void };
  devise: { set(v: string): void };
  debutLe: { set(v: string | null): void };
  fermerAu: { set(v: string | null): void };
  valeurs(): Record<string, string>;
  champs(): readonly { nom: string; obligatoire: boolean; compte: boolean }[];
  manques(): readonly string[];
  actes(): readonly string[];
  obstaclesFermeture(): readonly string[];
  journee(): string;
  choisie(): VersionComplete | null;
  ouvrirLaSaisie(): void;
  changerFamille(code: string): void;
  saisir(nom: string, valeur: string): void;
  ouvrir(version: VersionProduit): Promise<void>;
  repartirDe(version: VersionComplete): void;
  enregistrer(): Promise<void>;
  activer(): Promise<void>;
  retirer(): Promise<void>;
  fermer(): Promise<void>;
  demarrer(acte: string): void;
  tranches(): readonly Tranche[];
  ajouterTranche(): void;
  acquitte(): string | null;
}

describe("l'écran du paramétrage produit", () => {
  let socle: SiegeDouble;

  async function monter(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: socle }] });
    const fixture = TestBed.createComponent(Produits);
    await calme(fixture);
    return { fixture, ecran: fixture.componentInstance as unknown as Ecran };
  }

  beforeEach(() => {
    socle = new SiegeDouble();
  });

  it('dit qu’un produit se versionne, et pourquoi', async () => {
    const { fixture } = await monter();
    expect(html(fixture).textContent).toContain('chaque date de valeur traitée');
  });

  it('avertit qu’une version sans terme interdit la suivante', async () => {
    // C'est la contrainte d'exclusion du socle : sans elle, on croit pouvoir activer une nouvelle
    // version et le refus arrive sans explication.
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_EN_VIGUEUR);
    await calme(fixture);
    expect(html(fixture).textContent).toContain("Cette version n'a pas de terme");
  });

  // -------------------------------------------------------------- la saisie pilotée

  it('construit la saisie à partir de ce que la famille déclare', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.changerFamille('CURRENT_ACCOUNT');

    const noms = ecran.champs().map((c) => c.nom);
    expect(noms).toContain('interest.day_count');
    expect(noms).toContain('dormancy.months');
    expect(ecran.champs().find((c) => c.nom === 'interest.day_count')?.obligatoire).toBe(true);
    expect(ecran.champs().find((c) => c.nom === 'interest.credit_account')?.compte).toBe(true);
  });

  it('rend obligatoire ce qu’une saisie déclenche, pendant la saisie', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.changerFamille('CURRENT_ACCOUNT');
    expect(ecran.champs().find((c) => c.nom === 'overdraft.debit_account')?.obligatoire)
      .toBe(false);

    ecran.saisir('overdraft.rate', '13.5');
    expect(ecran.champs().find((c) => c.nom === 'overdraft.debit_account')?.obligatoire).toBe(true);
  });

  it('change de famille remet les paramètres à zéro', async () => {
    // Un paramètre d'une famille ne veut rien dire dans une autre : le socle refuse un paramètre
    // inconnu de la famille, et c'est le seul moyen de distinguer l'inutile du mal nommé.
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.changerFamille('CURRENT_ACCOUNT');
    ecran.saisir('interest.rate', '3');
    ecran.changerFamille('CURRENT_ACCOUNT');
    expect(ecran.valeurs()).toEqual({});
  });

  it('annonce ce qui manquera à l’activation sans empêcher d’enregistrer', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.changerFamille('CURRENT_ACCOUNT');
    ecran.code.set('CPTE-NEUF');
    ecran.intitule.set('Compte neuf');
    ecran.devise.set('XOF');
    ecran.debutLe.set('2027-01-01');

    expect(ecran.manques().length).toBeGreaterThan(0);

    // Un brouillon a le droit d'être incomplet : c'est ce qui en fait un brouillon.
    await ecran.enregistrer();
    expect(socle.derniereVersion?.entete.code).toBe('CPTE-NEUF');
    expect(ecran.acquitte()).toContain('brouillon');
  });

  it('refuse d’enregistrer une version sans identité', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    await ecran.enregistrer();
    expect(socle.derniereVersion).toBeNull();
  });

  it('envoie le barème avec son discriminant', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.changerFamille('CURRENT_ACCOUNT');
    ecran.code.set('EP-NEUF');
    ecran.intitule.set('Épargne');
    ecran.devise.set('XOF');
    ecran.debutLe.set('2027-01-01');
    ecran.ajouterTranche();
    await ecran.enregistrer();

    expect(Object.keys(socle.derniereVersion?.baremes ?? {})).toEqual(['INTEREST']);
  });

  it('repart d’une version existante sans copier sa date d’entrée en vigueur', async () => {
    // Une nouvelle version change deux lignes sur quarante ; tout ressaisir serait la meilleure
    // façon d'introduire une faute. Mais la date, elle, ne se copie jamais.
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_EN_VIGUEUR);
    await calme(fixture);
    const lue = ecran.choisie();
    expect(lue).not.toBeNull();

    ecran.repartirDe(lue!);
    expect(ecran.valeurs()['interest.rate']).toBe('3');
    await ecran.enregistrer();
    expect(socle.derniereVersion).toBeNull();
  });

  // -------------------------------------------------------------- les actes

  it('un brouillon s’active ou se retire ; une version en vigueur se ferme', async () => {
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_BROUILLON);
    await calme(fixture);
    expect(ecran.actes()).toEqual(['ACTIVER', 'RETIRER']);

    await ecran.ouvrir(VERSION_EN_VIGUEUR);
    await calme(fixture);
    expect(ecran.actes()).toEqual(['FERMER']);
    expect(ecran.actes()).not.toContain('RETIRER');
  });

  it('active un brouillon et dit que le socle a vérifié la complétude', async () => {
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_BROUILLON);
    await calme(fixture);
    await ecran.activer();

    expect(socle.derniereActivationProduit).toBe(VERSION_BROUILLON.id);
    expect(ecran.acquitte()).toContain('famille');
  });

  it('retire un brouillon en disant qu’il reste lisible', async () => {
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_BROUILLON);
    await calme(fixture);
    await ecran.retirer();

    expect(socle.dernierRetrait).toBe(VERSION_BROUILLON.id);
    expect(ecran.acquitte()).toContain('lisible');
  });

  it('propose la date comptable pour une fermeture, et refuse avant elle', async () => {
    const { fixture, ecran } = await monter();
    await ecran.ouvrir(VERSION_EN_VIGUEUR);
    await calme(fixture);
    ecran.demarrer('FERMER');
    expect(ecran.obstaclesFermeture()).toEqual([]);

    // Après l'entrée en vigueur, mais avant la date comptable : un seul obstacle, le bon.
    ecran.fermerAu.set('2026-06-01');
    expect(ecran.obstaclesFermeture()).toHaveLength(1);
    await ecran.fermer();
    expect(socle.derniereFermeture).toBeNull();

    ecran.fermerAu.set('2026-12-31');
    await ecran.fermer();
    expect(socle.derniereFermeture)
      .toEqual({ versionId: VERSION_EN_VIGUEUR.id, validTo: '2026-12-31' });
  });

  it('borne la fermeture sur la date comptable de la banque, pas sur le jour civil', async () => {
    const { ecran } = await monter();
    expect(ecran.journee()).toBe('2026-09-18');
  });
});
