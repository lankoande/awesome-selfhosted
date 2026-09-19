import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  CompteNonAffecte, EtatProduit, Maquette, MaquetteComplete, RegleSaisie, RubriqueSaisie,
} from '../modele/maquettes.modele';
import { SIEGE } from '../siege.port';
import { SiegeDouble } from '../testing/siege-double';
import { Maquettes } from './maquettes.page';

type Fixture = ComponentFixture<Maquettes>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  code: { set(v: string): void };
  intitule: { set(v: string): void };
  debutLe: { set(v: string | null): void };
  fermerAu: { set(v: string | null): void };
  nature: { set(v: 'BALANCE_SHEET' | 'INCOME_STATEMENT' | 'OFF_BALANCE_SHEET'): void };
  rubriques: { set(v: readonly RubriqueSaisie[]): void; (): readonly RubriqueSaisie[] };
  regles: { set(v: readonly RegleSaisie[]): void; (): readonly RegleSaisie[] };
  maquettes(): readonly Maquette[];
  visibles(): readonly Maquette[];
  choisie(): MaquetteComplete | null;
  essai(): EtatProduit | null;
  obstaclesIdentite(): readonly string[];
  obstaclesRubriques(): readonly string[];
  obstaclesRegles(): readonly string[];
  peutEnregistrer(): boolean;
  actes(): readonly string[];
  obstaclesFermeture(): readonly string[];
  acquitte(): string | null;
  saisie(): boolean;
  ouvrir(maquette: { id: string }): Promise<void>;
  essayer(): Promise<void>;
  corriger(compte: CompteNonAffecte): void;
  ouvrirLaSaisie(): void;
  deplacerRegle(index: number, sens: -1 | 1): void;
  estMorte(index: number): boolean;
  enregistrer(): Promise<void>;
  fermer(): Promise<void>;
  retirer(): Promise<void>;
  filtrerNature(nature: 'BALANCE_SHEET' | null): void;
}

describe("l'écran des maquettes d'états financiers", () => {
  let socle: SiegeDouble;

  async function monter(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: socle }] });
    const fixture = TestBed.createComponent(Maquettes);
    await calme(fixture);
    return { fixture, ecran: fixture.componentInstance as unknown as Ecran };
  }

  beforeEach(() => {
    socle = new SiegeDouble();
  });

  it('dit pourquoi l’essai existe', async () => {
    const { fixture } = await monter();
    expect(html(fixture).textContent).toContain('sans rubrique');
  });

  it('filtre par nature d’état', async () => {
    const { ecran } = await monter();
    expect(ecran.maquettes().length).toBeGreaterThan(2);
    ecran.filtrerNature('BALANCE_SHEET');
    expect(ecran.visibles().every((m) => m.kind === 'BALANCE_SHEET')).toBe(true);
  });

  /**
   * L'essai d'un brouillon incomplet : c'est le cas que l'écran existe pour montrer. Sans lui,
   * on l'activait à deux et on lisait un bilan faux — après l'avoir transmis.
   */
  it('montre les comptes qu’une maquette incomplète laisse sans rubrique', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'mq-brouillon' });
    await ecran.essayer();

    const essai = ecran.essai();
    expect(essai?.consistent).toBe(false);
    expect(essai?.unassigned.length).toBeGreaterThan(0);
    expect(essai?.anomalies.length).toBeGreaterThan(0);
  });

  it('ne signale rien sur une maquette complète', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'mq-bilan' });
    await ecran.essayer();
    expect(ecran.essai()?.consistent).toBe(true);
    expect(ecran.essai()?.unassigned).toEqual([]);
  });

  /**
   * Une anomalie qu'on corrige d'un geste vaut mieux qu'une anomalie qu'on recopie : le compte
   * ouvre la rédaction sur la maquette essayée, avec la règle déjà remplie.
   */
  it('transforme un compte sans rubrique en règle à ajouter', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'mq-brouillon' });
    await ecran.essayer();

    const orphelin = ecran.essai()!.unassigned[0]!;
    const existantes = ecran.choisie()!.rules.length;
    ecran.corriger(orphelin);

    // La rédaction part de la maquette essayée — on ne la réécrit pas pour ajouter une règle —
    // et la règle proposée s'ajoute à la fin, avec la nature et le sens du solde déjà repris.
    expect(ecran.saisie()).toBe(true);
    expect(ecran.regles().length).toBe(existantes + 1);
    const ajoutee = ecran.regles()[ecran.regles().length - 1]!;
    expect(ajoutee.accountKind).toBe(orphelin.accountKind);
    expect(ajoutee.balanceSide).toBe(orphelin.side);
    expect(ecran.rubriques().map((r) => r.code))
      .toEqual(ecran.choisie()!.lines.map((r) => r.code));
  });

  it('avertit qu’une maquette en vigueur sans terme interdit la suivante', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.nature.set('BALANCE_SHEET');
    ecran.code.set('BILAN-2028');
    ecran.intitule.set('Bilan 2028');
    ecran.debutLe.set('2028-01-01');
    expect(ecran.obstaclesIdentite().join(' ')).toContain('BILAN-BCEAO');
    expect(ecran.peutEnregistrer()).toBe(false);
  });

  /**
   * L'ordre des règles est la règle : le déplacer change la précédence, et c'est ce déplacement
   * qui rend une règle morte vivante.
   */
  it('déplace une règle, et la règle morte redevient vivante', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.rubriques.set([
      { code: 'A1', label: 'Créances', level: 1, kind: 'DETAIL', side: 'DEBIT', plus: [],
        minus: [] },
      { code: 'A2', label: 'Divers', level: 1, kind: 'DETAIL', side: 'DEBIT', plus: [],
        minus: [] },
    ]);
    ecran.regles.set([
      { lineCode: 'A2', accountKind: 'CUSTOMER', codePrefix: '', balanceSide: '' },
      { lineCode: 'A1', accountKind: 'CUSTOMER', codePrefix: '', balanceSide: 'DEBIT' },
    ]);
    expect(ecran.estMorte(1)).toBe(true);

    ecran.deplacerRegle(1, -1);
    expect(ecran.estMorte(0)).toBe(false);
    expect(ecran.estMorte(1)).toBe(false);
  });

  it('envoie les rubriques et les règles dans l’ordre de la liste', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set('BILAN-NEUF');
    ecran.intitule.set('Bilan neuf');
    ecran.debutLe.set('2028-01-01');
    ecran.nature.set('OFF_BALANCE_SHEET');
    ecran.rubriques.set([
      { code: 'E1', label: 'Engagements donnés', level: 1, kind: 'DETAIL', side: 'DEBIT',
        plus: [], minus: [] },
      { code: 'E2', label: 'Engagements reçus', level: 1, kind: 'DETAIL', side: 'CREDIT',
        plus: [], minus: [] },
    ]);
    ecran.regles.set([
      { lineCode: 'E1', accountKind: '', codePrefix: '', balanceSide: 'DEBIT' },
      { lineCode: 'E2', accountKind: '', codePrefix: '', balanceSide: 'CREDIT' },
    ]);
    expect(ecran.peutEnregistrer()).toBe(true);

    await ecran.enregistrer();
    expect(socle.derniereMaquette?.entete.code).toBe('BILAN-NEUF');
    expect(socle.derniereMaquette?.entete.kind).toBe('OFF_BALANCE_SHEET');
    expect(socle.derniereMaquette?.rubriques.map((r) => r.code)).toEqual(['E1', 'E2']);
    expect(socle.derniereMaquette?.regles.map((r) => r.lineCode)).toEqual(['E1', 'E2']);
    expect(ecran.acquitte()).toContain('Essayez-la');
  });

  it('ne propose que la fermeture sur une maquette en vigueur', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'mq-bilan' });
    expect(ecran.actes()).toEqual(['FERMER']);

    ecran.fermerAu.set('2020-01-01');
    expect(ecran.obstaclesFermeture().join(' ')).toContain('déjà produit');

    ecran.fermerAu.set('2026-12-31');
    await ecran.fermer();
    expect(socle.derniereFermetureMaquette)
      .toEqual({ layoutId: 'mq-bilan', validTo: '2026-12-31' });
    expect(ecran.acquitte()).toContain('maquette suivante');
  });

  it('retire un brouillon sans second regard', async () => {
    const { ecran } = await monter();
    await ecran.ouvrir({ id: 'mq-brouillon' });
    expect(ecran.actes()).toEqual(['ACTIVER', 'RETIRER']);
    await ecran.retirer();
    expect(socle.dernierRetraitMaquette).toBe('mq-brouillon');
    expect(ecran.acquitte()).toContain('reste lisible');
  });
});
