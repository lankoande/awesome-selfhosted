import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { EvenementSocle, LigneSaisie } from '../modele/schemas.modele';
import { SIEGE } from '../siege.port';
import { SiegeDouble } from '../testing/siege-double';
import { Schemas } from './schemas.page';

type Fixture = ComponentFixture<Schemas>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  onglet: { set(v: string): void };
  code: { set(v: string): void };
  intitule: { set(v: string): void };
  devise: { set(v: string): void };
  evenement: { set(v: string): void };
  debutLe: { set(v: string | null): void };
  fermerAu: { set(v: string | null): void };
  lignes: { set(v: readonly LigneSaisie[]): void };
  derivations: { set(v: readonly (readonly [string, string])[]): void };
  valeurs: { set(v: Record<string, string>): void };
  remplacables(): readonly EvenementSocle[];
  modules(): readonly { code: string; evenements: readonly EvenementSocle[] }[];
  obstaclesIdentite(): readonly string[];
  obstaclesLignes(): readonly string[];
  peutEnregistrer(): boolean;
  essai(): { variables: readonly string[]; debit: number; rejection: unknown } | null;
  actes(): readonly string[];
  obstaclesFermeture(): readonly string[];
  acquitte(): string | null;
  deplier(eventType: string): void;
  ouvrirLaSaisie(): void;
  changerEvenement(eventType: string): void;
  essayer(): Promise<void>;
  essayerLeSocle(evenement: EvenementSocle): Promise<void>;
  relancerEssai(): Promise<void>;
  enregistrer(): Promise<void>;
  ouvrir(schema: { id: string }): Promise<void>;
  fermer(): Promise<void>;
  retirer(): Promise<void>;
}

describe("l'écran des schémas comptables", () => {
  let socle: SiegeDouble;

  async function monter(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: socle }] });
    const fixture = TestBed.createComponent(Schemas);
    await calme(fixture);
    return { fixture, ecran: fixture.componentInstance as unknown as Ecran };
  }

  beforeEach(() => {
    socle = new SiegeDouble();
  });

  it('dit d’emblée que la plupart des schémas se lisent et ne se remplacent pas', async () => {
    const { fixture } = await monter();
    expect(html(fixture).textContent).toContain('ne serait lu par personne');
  });

  /**
   * Le catalogue est la seule source : le poste ne tient aucune liste d'événements, et n'en
   * perd aucun en les groupant.
   */
  it('montre tout le catalogue du socle, groupé par module', async () => {
    const { ecran } = await monter();
    const rendus = ecran.modules().reduce((total, m) => total + m.evenements.length, 0);
    expect(rendus).toBe(socle.catalogue.length);
    expect(ecran.remplacables().map((e) => e.eventType)).toEqual(['FEE_CHARGE']);
  });

  it('essaie un schéma du socle sur un cas, sans rien rédiger', async () => {
    const { ecran } = await monter();
    const retrait = socle.catalogue.find((e) => e.eventType === 'CASH_WITHDRAWAL')!;
    await ecran.essayerLeSocle(retrait);
    expect(ecran.essai()?.variables).toEqual(['amount', 'fee', 'tax']);

    ecran.valeurs.set({ amount: '5000', fee: '500', tax: '90' });
    await ecran.relancerEssai();
    expect(ecran.essai()?.debit).toBe(5590);
  });

  /**
   * Le bouton « relancer » relançait le premier événement du catalogue, quelle que soit la ligne
   * dépliée : l'écran montrait alors l'écriture d'un versement en croyant montrer celle d'un
   * retrait. C'est le genre d'erreur qu'on ne voit pas, parce que les deux sont plausibles.
   */
  it('relance l’essai de l’événement ouvert, pas du premier de la liste', async () => {
    const { ecran } = await monter();
    const retrait = socle.catalogue.find((e) => e.eventType === 'CASH_WITHDRAWAL')!;
    await ecran.essayerLeSocle(retrait);
    await ecran.relancerEssai();
    expect(ecran.essai()?.variables).toEqual(['amount', 'fee', 'tax']);
  });

  it('refuse de rédiger pour un événement que le socle impute lui-même', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set('MORT');
    ecran.intitule.set('Schéma mort');
    ecran.debutLe.set('2026-10-01');
    ecran.evenement.set('LOAN_DISBURSEMENT');
    expect(ecran.obstaclesIdentite().join(' ')).toContain('jamais résolu');
    expect(ecran.peutEnregistrer()).toBe(false);
  });

  /**
   * Repartir du schéma du socle plutôt que d'une page blanche : un schéma de commission qu'on
   * remplace en change une ligne sur trois.
   */
  it('part du schéma du socle quand on ouvre la rédaction', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    expect(ecran.obstaclesLignes()).toEqual([]);
    expect(socle.dernierSchema).toBeNull();
  });

  it('envoie ce qui est écrit, sans interpréter les expressions', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set('FRAIS-2027');
    ecran.intitule.set('Frais de tenue 2027');
    ecran.debutLe.set('2027-01-01');
    await ecran.enregistrer();

    expect(socle.dernierSchema?.entete.code).toBe('FRAIS-2027');
    expect(socle.dernierSchema?.entete.eventType).toBe('FEE_CHARGE');
    expect(socle.dernierSchema?.lignes.length).toBeGreaterThan(1);
    expect(ecran.acquitte()).toContain('tirage');
  });

  /**
   * Les grandeurs à demander viennent de la réponse du socle, jamais d'une lecture des
   * expressions par le poste : c'est ce qui garantit qu'elles suivent la saisie.
   */
  it('aligne le jeu de valeurs sur ce que le socle réclame', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.valeurs.set({ obsolete: '42' });
    ecran.derivations.set([]);
    ecran.lignes.set([
      { account: 'CONTRACT', direction: 'DEBIT', amount: 'base', condition: '', label: '' },
      { account: 'PARAM:fee_income', direction: 'CREDIT', amount: 'base', condition: '',
        label: '' },
    ]);
    await ecran.essayer();
    expect(ecran.essai()?.variables).toEqual(['base']);
    expect(Object.keys((ecran as unknown as { valeurs: { (): Record<string, string> } })
      .valeurs())).toEqual(['base']);
  });

  it('ne propose que la fermeture sur un schéma en vigueur, et la borne à la date comptable',
     async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'sc-2' });
    expect(ecran.actes()).toEqual(['FERMER']);

    ecran.fermerAu.set('2020-01-01');
    expect(ecran.obstaclesFermeture().join(' ')).toContain('arrêté déjà produit');
  });

  it('demande la fermeture et dit que le code sera libre pour un schéma suivant', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'sc-2' });
    ecran.fermerAu.set('2026-12-31');
    await ecran.fermer();
    expect(socle.derniereFermetureSchema).toEqual({ schemaId: 'sc-2', validTo: '2026-12-31' });
    expect(ecran.acquitte()).toContain('libre pour un schéma suivant');
  });

  it('retire un brouillon sans second regard, et dit qu’il reste lisible', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'sc-1' });
    await ecran.retirer();
    expect(socle.dernierRetraitSchema).toBe('sc-1');
    expect(ecran.acquitte()).toContain('reste lisible');
  });
});
