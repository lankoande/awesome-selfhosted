import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { CLIENTS } from '../../clients/clients.port';
import { ClientsDouble } from '../../clients/testing/clients-double';
import { Cheque, Mandat } from '../modele/compte.modele';
import { PAIEMENTS } from '../paiements.port';
import {
  CHEQUE_EN_CIRCULATION, CHEQUE_PAYE, MANDAT_ACTIF, MANDAT_REVOQUE, PaiementsDouble,
} from '../testing/paiements-double';
import { MoyensDePaiementDuCompte } from './compte.page';

type Fixture = ComponentFixture<MoyensDePaiementDuCompte>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  compteId: { set(valeur: string): void };
  onglet: { set(valeur: string): void };
  montant: { set(valeur: number | null): void };
  mode: { set(valeur: 'CASH' | 'CLEARING'): void };
  porteur: { set(valeur: string): void };
  nostro: { set(valeur: string): void };
  nombreDeCheques: { set(valeur: number): void };
  motifOpposition: { set(valeur: 'LOSS' | 'THEFT'): void };
  motifRevocation: { set(valeur: string): void };
  reference: { set(valeur: string): void };
  chequeChoisi(): Cheque | null;
  mandatChoisi(): Mandat | null;
  actesDuCheque(): readonly string[];
  obstaclesPaiement(): readonly string[];
  obstaclesChequier(): readonly string[];
  peutRevoquer(mandat: Mandat): boolean;
  ouvrirCheque(cheque: Cheque): void;
  choisirMandat(mandat: Mandat): void;
  demarrerSurCheque(acte: 'PAYER' | 'OPPOSER'): void;
  delivrer(): Promise<void>;
  payer(): Promise<void>;
  opposer(): Promise<void>;
  revoquer(): Promise<void>;
  acquitte(): string | null;
}

describe("l'écran des chèques et mandats d'un compte", () => {
  let source: PaiementsDouble;

  async function monter(): Promise<Fixture> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: PAIEMENTS, useValue: source },
        // Le choix du compte vit dans l'espace client : l'écran l'emprunte,
        // et la spécification lui rend un double plutôt qu'un vrai socle.
        { provide: CLIENTS, useValue: new ClientsDouble() },
      ],
    });
    const fixture = TestBed.createComponent(MoyensDePaiementDuCompte);
    await calme(fixture);
    return fixture;
  }

  async function surUnCompte(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;
    ecran.compteId.set('cpt-1');
    await calme(fixture);
    return { fixture, ecran };
  }

  beforeEach(() => {
    source = new PaiementsDouble();
  });

  it('ne lit rien tant qu’aucun compte n’est désigné', async () => {
    // Ces actes n'ont pas de file : sans compte, il n'y a rien à montrer, et
    // le dire vaut mieux qu'un écran vide.
    expect(html(await monter()).textContent)
      .toContain('Choisissez un compte pour voir ses chéquiers');
  });

  it('dit « demandé », jamais « délivré » : le chéquier part à la validation', async () => {
    const { ecran } = await surUnCompte();
    ecran.nombreDeCheques.set(25);
    await ecran.delivrer();

    expect(source.dernierChequier).toEqual({ count: 25 });
    // C'est la promesse à ne pas faire : le client attend son carnet au guichet.
    expect(ecran.acquitte()).toContain('demandé');
    expect(ecran.acquitte()).not.toContain('délivré.');
  });

  it('refuse un chéquier hors des bornes du socle', async () => {
    const { ecran } = await surUnCompte();
    ecran.nombreDeCheques.set(500);
    expect(ecran.obstaclesChequier()).toHaveLength(1);
    await ecran.delivrer();
    expect(source.dernierChequier).toBeNull();
  });

  it('ne propose un acte que sur un chèque en circulation', async () => {
    const { fixture, ecran } = await surUnCompte();
    ecran.onglet.set('cheques');

    ecran.ouvrirCheque(CHEQUE_EN_CIRCULATION);
    expect(ecran.actesDuCheque()).toEqual(['PAYER', 'OPPOSER']);

    // Un chèque payé ne se reprend pas : aucun bouton, et l'écran le dit.
    ecran.ouvrirCheque(CHEQUE_PAYE);
    await calme(fixture);
    expect(ecran.actesDuCheque()).toEqual([]);
    expect(html(fixture).textContent).toContain('Ce chèque est dénoué');
  });

  it("n'envoie pas le nostro quand le chèque est payé au guichet", async () => {
    const { ecran } = await surUnCompte();
    ecran.ouvrirCheque(CHEQUE_EN_CIRCULATION);
    ecran.demarrerSurCheque('PAYER');
    ecran.montant.set(420000);
    ecran.porteur.set('SAWADOGO Boukary');
    await ecran.payer();

    // La caisse vient du jeton. Ce qu'on enverrait ici, le socle l'ignorerait
    // — et l'écran ferait croire qu'il choisit la caisse.
    expect(source.dernierPaiement?.mode).toBe('CASH');
    expect(source.dernierPaiement?.nostroAccountId).toBeNull();
    expect(source.dernierPaiement?.beneficiary).toBe('SAWADOGO Boukary');
  });

  it('exige le nostro en compensation, et le porteur au guichet', async () => {
    const { ecran } = await surUnCompte();
    ecran.ouvrirCheque(CHEQUE_EN_CIRCULATION);
    ecran.demarrerSurCheque('PAYER');
    ecran.montant.set(420000);

    // Au guichet, sans porteur : bloqué.
    expect(ecran.obstaclesPaiement()).toHaveLength(1);

    // En compensation, le porteur vient de la banque présentatrice ; le nostro,
    // lui, se désigne.
    ecran.mode.set('CLEARING');
    expect(ecran.obstaclesPaiement()).toHaveLength(1);
    ecran.nostro.set('nos-1');
    expect(ecran.obstaclesPaiement()).toEqual([]);
  });

  it("n'offre que les quatre motifs d'opposition de la loi uniforme", async () => {
    const { fixture, ecran } = await surUnCompte();
    ecran.onglet.set('cheques');
    ecran.ouvrirCheque(CHEQUE_EN_CIRCULATION);
    ecran.demarrerSurCheque('OPPOSER');
    await calme(fixture);

    const motifs = html(fixture).querySelectorAll('input[name="motif-opposition"]');
    expect(motifs.length).toBe(4);
    expect(html(fixture).textContent).toContain('Perte du chèque');
    expect(html(fixture).textContent).toContain('Vol du chèque');
  });

  it("l'opposition enregistrée dit qu'aucun paiement ne passera plus", async () => {
    const { ecran } = await surUnCompte();
    ecran.ouvrirCheque(CHEQUE_EN_CIRCULATION);
    ecran.demarrerSurCheque('OPPOSER');
    ecran.motifOpposition.set('THEFT');
    await ecran.opposer();

    expect(source.derniereOpposition?.reason).toBe('THEFT');
    expect(ecran.acquitte()).toContain('Aucun paiement ne passera plus');
  });

  it('montre les incidents sans proposer de les corriger', async () => {
    const { fixture, ecran } = await surUnCompte();
    ecran.onglet.set('incidents');
    await calme(fixture);

    const texte = html(fixture).textContent ?? '';
    expect(texte).toContain('ne se corrige pas');
    expect(texte).toContain('Provision insuffisante');
    expect(html(fixture).querySelectorAll('.panneau button').length).toBe(0);
  });

  it('un mandat révoqué ne se révoque pas deux fois', async () => {
    const { fixture, ecran } = await surUnCompte();
    ecran.onglet.set('mandats');
    await calme(fixture);

    expect(ecran.peutRevoquer(MANDAT_ACTIF)).toBe(true);
    expect(ecran.peutRevoquer(MANDAT_REVOQUE)).toBe(false);

    ecran.choisirMandat(MANDAT_REVOQUE);
    await calme(fixture);
    expect(html(fixture).textContent).toContain('Ce mandat est révoqué');
  });

  it('exige un motif pour révoquer, et le porte au socle', async () => {
    const { ecran } = await surUnCompte();
    ecran.choisirMandat(MANDAT_ACTIF);

    await ecran.revoquer();
    expect(source.derniereRevocation).toBeNull();

    ecran.motifRevocation.set('Résiliation du contrat');
    await ecran.revoquer();
    expect(source.derniereRevocation)
      .toEqual({ mandateId: MANDAT_ACTIF.id, motif: 'Résiliation du contrat' });
  });

  it('avertit qu’un mandat sans plafond laisse prélever ce qu’on veut', async () => {
    const { fixture, ecran } = await surUnCompte();
    ecran.onglet.set('mandats');
    ecran.choisirMandat({ ...MANDAT_ACTIF, maxAmount: null });
    await calme(fixture);

    expect(html(fixture).textContent).toContain("Ce mandat n'a pas de plafond");
  });
});
