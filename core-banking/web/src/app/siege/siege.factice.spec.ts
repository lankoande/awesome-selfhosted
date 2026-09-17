import { RefusMetier } from '../guichet/modele/guichet.modele';
import { nonTentee } from './modele/siege.modele';
import { SiegeFactice } from './siege.factice';

const ENTITE = '00000000-0000-4000-8000-000000000001';
const JOURNEE = '2026-09-17';

describe('source de démonstration du siège', () => {
  let siege: SiegeFactice;

  beforeEach(() => {
    siege = new SiegeFactice();
    siege.latenceMs = 0;
  });

  it("l’essai à blanc exécute les étapes sans rien écrire", async () => {
    siege.echecPrevu = null;
    const run = await siege.lancerTfj(ENTITE, JOURNEE, 'DRY_RUN');

    expect(run.mode).toBe('DRY_RUN');
    expect(run.status).toBe('COMPLETED');
    expect(run.steps.every((e) => e.written === 0)).toBe(true);
    // Les étapes ont bien tourné : elles ont lu.
    expect(run.steps.some((e) => e.read > 0)).toBe(true);
  });

  it("suit l’ordre réel des étapes du socle", async () => {
    const run = await siege.lancerTfj(ENTITE, JOURNEE, 'DRY_RUN');
    const noms = run.steps.map((e) => e.name);

    expect(noms[0]).toBe('PRE_CHECKS');
    expect(noms.at(-1)).toBe('OPEN_NEXT_DAY');
    expect(noms).toContain('AML_MONITORING');
    expect(noms.indexOf('BALANCE_SNAPSHOT')).toBeLessThan(noms.indexOf('RECONCILIATION'));
    expect(run.steps.map((e) => e.order)).toEqual(run.steps.map((_, i) => i + 1));
  });

  it("un échec bloquant arrête la chaîne : les suivantes ne sont jamais tentées", async () => {
    // L'essai à blanc trouve le blocage avant que la journée soit engagée :
    // c'est exactement ce pour quoi il existe.
    const run = await siege.lancerTfj(ENTITE, JOURNEE, 'REAL');

    expect(run.status).toBe('FAILED');
    const echec = run.steps.find((e) => e.status === 'FAILED')!;
    expect(echec.name).toBe('PRE_CHECKS');
    expect(echec.blocking).toBe(true);
    // Le refus renvoie à l'arrêté de caisse : c'est la boucle guichet → siège.
    expect(echec.error).toContain('caisse');

    const jamais = run.steps.filter((e) => nonTentee(e, run));
    expect(jamais.length).toBe(run.steps.length - 1);
  });

  it('ne reprend que ce qui est en échec', async () => {
    siege.echecPrevu = null;
    const essai = await siege.lancerTfj(ENTITE, JOURNEE, 'DRY_RUN');
    await expect(siege.reprendreTfj(ENTITE, essai.id)).rejects.toMatchObject({ code: 'REPRISE_IMPOSSIBLE' });

    siege.echecPrevu = 'PRE_CHECKS';
    const reel = await siege.lancerTfj(ENTITE, JOURNEE, 'REAL');
    const repris = await siege.reprendreTfj(ENTITE, reel.id);
    expect(repris.status).toBe('COMPLETED');
    expect(repris.steps.every((e) => e.status === 'COMPLETED')).toBe(true);
  });

  it('annule un passage, et refuse de l’annuler deux fois', async () => {
    const run = await siege.lancerTfj(ENTITE, JOURNEE, 'DRY_RUN');
    const annule = await siege.annulerTfj(ENTITE, run.id);
    expect(annule.status).toBe('CANCELLED');
    await expect(siege.annulerTfj(ENTITE, run.id)).rejects.toBeInstanceOf(RefusMetier);
  });

  it('rend une balance équilibrée, ouverture et clôture comprises', async () => {
    const [totaux] = await siege.totauxBalance(ENTITE, { du: null, au: null, kind: null });

    expect(totaux!.balanced).toBe(true);
    expect(totaux!.openingDebit.amount).toBe(totaux!.openingCredit.amount);
    expect(totaux!.movementDebit.amount).toBe(totaux!.movementCredit.amount);
    expect(totaux!.closingDebit.amount).toBe(totaux!.closingCredit.amount);
  });

  it('filtre la balance par nature de compte, totaux compris', async () => {
    const page = await siege.balance(ENTITE, { du: null, au: null, kind: 'CUSTOMER' }, 0, 50);
    expect(page.lignes.length).toBeGreaterThan(0);
    expect(page.lignes.every((l) => l.kind === 'CUSTOMER')).toBe(true);

    const [totaux] = await siege.totauxBalance(ENTITE, { du: null, au: null, kind: 'CUSTOMER' });
    expect(totaux!.accounts).toBe(page.lignes.length);
  });
});
