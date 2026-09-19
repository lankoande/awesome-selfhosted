import { describe, expect, it } from 'vitest';
import { CATALOGUE_DEMONSTRATION } from './schemas.demonstration';
import { essaiDeDemonstration } from './schemas.essai-demonstration';
import {
  actesSurSchema, etatEffectif, EvenementSocle, libelleEcart, libelleReference, libelleRefus,
  LigneSaisie, obstaclesALaFermeture, obstaclesALaLigne, obstaclesAlEntete, obstaclesAlEvenement,
  libelleNombreEvenements, parametrables, parModule, phraseDeLaLigne, SchemaComptable,
} from './schemas.modele';

function schema(reglages: Partial<SchemaComptable> = {}): SchemaComptable {
  return {
    id: 'sc-1', code: 'FRAIS', label: 'Frais de tenue', currency: 'XOF',
    validFrom: '2026-01-01', validTo: null, status: 'ACTIVE', createdBy: null, createdAt: null,
    approvedBy: null, approvedAt: null, withdrawnBy: null, withdrawnAt: null, ...reglages,
  };
}

function ligne(reglages: Partial<LigneSaisie> = {}): LigneSaisie {
  return { account: 'CONTRACT', direction: 'DEBIT', amount: 'total', condition: '', label: '',
           ...reglages };
}

const ENTETE = {
  code: 'FRAIS', label: 'Frais de tenue', currency: 'XOF', eventType: 'FEE_CHARGE',
  validFrom: '2026-01-01', validTo: null,
};

describe('l’état d’un schéma', () => {
  it('distingue à venir, en vigueur et échu, à la date comptable de la banque', () => {
    // Le socle ne connaît que ACTIVE pour les trois : les confondre ferait chercher longtemps
    // pourquoi un schéma « en vigueur » ne s'applique plus.
    expect(etatEffectif(schema({ validFrom: '2026-10-01' }), '2026-09-18')).toBe('A_VENIR');
    expect(etatEffectif(schema({ validTo: '2026-08-31' }), '2026-09-18')).toBe('ECHU');
    expect(etatEffectif(schema(), '2026-09-18')).toBe('ACTIVE');
    expect(etatEffectif(schema({ status: 'DRAFT' }), '2026-09-18')).toBe('DRAFT');
  });

  it('propose d’activer ou de retirer un brouillon, de fermer un schéma en vigueur', () => {
    expect(actesSurSchema(schema({ status: 'DRAFT' }))).toEqual(['ACTIVER', 'RETIRER']);
    // Un schéma actif ne se retire pas : les imputations passées se résolvent encore par lui.
    expect(actesSurSchema(schema())).toEqual(['FERMER']);
    expect(actesSurSchema(schema({ status: 'WITHDRAWN' }))).toEqual([]);
  });
});

describe('la fermeture', () => {
  it('refuse une date antérieure à la date comptable de la banque', () => {
    const obstacles = obstaclesALaFermeture(schema(), { validTo: '2026-09-01' }, '2026-09-18');
    expect(obstacles.join(' ')).toContain('arrêté déjà produit');
  });

  it('refuse une fin antérieure au début de validité', () => {
    const obstacles = obstaclesALaFermeture(
      schema({ validFrom: '2026-10-01' }), { validTo: '2026-09-20' }, '2026-09-18');
    expect(obstacles.join(' ')).toContain('avant de commencer');
  });

  it('accepte une fermeture à la date comptable elle-même', () => {
    expect(obstaclesALaFermeture(schema(), { validTo: '2026-09-18' }, '2026-09-18')).toEqual([]);
  });
});

describe('ce qu’un schéma peut remplacer', () => {
  it('refuse un événement que le socle impute lui-même, en le nommant', () => {
    // C'est le piège central : un tel schéma s'activerait à deux et ne serait lu par personne.
    const obstacles = obstaclesAlEntete({ ...ENTETE, eventType: 'LOAN_DISBURSEMENT' },
                                        CATALOGUE_DEMONSTRATION);
    expect(obstacles.join(' ')).toContain('Déblocage de crédit');
    expect(obstacles.join(' ')).toContain('jamais résolu');
  });

  it('refuse un événement inconnu du socle', () => {
    const obstacles = obstaclesAlEntete({ ...ENTETE, eventType: 'FEE_CHRGE' },
                                        CATALOGUE_DEMONSTRATION);
    expect(obstacles.join(' ')).toContain('inconnu du socle');
  });

  it('accepte l’événement que le catalogue déclare paramétrable', () => {
    expect(obstaclesAlEntete(ENTETE, CATALOGUE_DEMONSTRATION)).toEqual([]);
  });

  /**
   * Le jugement vient du catalogue servi, jamais d'une liste tenue ici : le jour où un module
   * deviendra paramétrable, l'écran l'apprendra du socle — et il ne l'inventera jamais avant lui.
   */
  it('suit le catalogue, pas une liste écrite dans le poste', () => {
    const catalogue: readonly EvenementSocle[] = CATALOGUE_DEMONSTRATION.map((evenement) =>
      evenement.eventType === 'LOAN_DISBURSEMENT'
        ? { ...evenement, source: 'PARAMETRABLE' as const, schemaCode: 'LOAN_STANDARD' }
        : evenement);
    expect(obstaclesAlEntete({ ...ENTETE, eventType: 'LOAN_DISBURSEMENT' }, catalogue)).toEqual([]);
  });

  it('exige un code, un libellé, une devise et une entrée en vigueur', () => {
    const obstacles = obstaclesAlEntete(
      { code: '', label: '', currency: '', eventType: 'FEE_CHARGE', validFrom: null,
        validTo: null },
      CATALOGUE_DEMONSTRATION);
    expect(obstacles).toHaveLength(4);
  });
});

describe('les lignes', () => {
  it('refuse une référence de compte non reconnue', () => {
    expect(obstaclesALaLigne(ligne({ account: 'COMPTE-70611' })).join(' '))
      .toContain('non reconnue');
    expect(obstaclesALaLigne(ligne({ account: 'GL:' })).join(' ')).toContain('non reconnue');
    expect(obstaclesALaLigne(ligne({ account: 'GL:70611' }))).toEqual([]);
    expect(obstaclesALaLigne(ligne({ account: 'PARAM:fee_income' }))).toEqual([]);
  });

  it('exige un montant et un sens', () => {
    expect(obstaclesALaLigne(ligne({ amount: '' })).join(' ')).toContain('montant');
    expect(obstaclesALaLigne(ligne({ direction: 'DEB' })).join(' ')).toContain('débit ou au crédit');
  });

  /**
   * Un schéma tout au débit est une faute de saisie, pas un cas limite. Le socle le refuserait
   * par tirage, avec un contre-exemple chiffré qui n'explique rien.
   */
  it('refuse une écriture qui n’a qu’un sens', () => {
    const obstacles = obstaclesAlEvenement([ligne(), ligne({ amount: 'autre' })]);
    expect(obstacles.join(' ')).toContain('Aucune ligne au crédit');
  });

  it('refuse une écriture à une seule ligne', () => {
    expect(obstaclesAlEvenement([ligne()]).join(' ')).toContain('deux lignes');
  });
});

describe('la lecture', () => {
  it('lit une ligne comme une phrase, condition comprise', () => {
    expect(phraseDeLaLigne({ account: 'PARAM:fee_tax', direction: 'CREDIT', amount: 'tax_booked',
                             condition: 'tax_booked > 0', label: 'Taxe' }))
      .toBe('Crédite le compte du rôle « fee_tax » de tax_booked, si tax_booked > 0.');
  });

  it('nomme chaque forme de référence', () => {
    expect(libelleReference('CONTRACT')).toBe('le compte du contrat');
    expect(libelleReference('GL:70611')).toBe('le compte général 70611');
    expect(libelleReference('RESOLVE:cash')).toBe('le compte résolu « cash »');
  });

  it('traduit les codes du socle, et rend le code brut s’il est inconnu', () => {
    expect(libelleEcart('CONDITION')).toBe('Condition fausse');
    expect(libelleEcart(null)).toBe('');
    expect(libelleEcart('AUTRE_CHOSE')).toBe('AUTRE_CHOSE');
    expect(libelleRefus({ code: 'DESEQUILIBRE', detail: '' })).toBe('Débit et crédit différents');
    expect(libelleRefus(null)).toBe('');
  });
});

describe('le catalogue', () => {
  it('groupe par module sans perdre un seul événement', () => {
    const modules = parModule(CATALOGUE_DEMONSTRATION);
    const rendus = modules.reduce((total, module) => total + module.evenements.length, 0);
    expect(rendus).toBe(CATALOGUE_DEMONSTRATION.length);
    expect(modules.map((module) => module.code)).toEqual(['OPERATIONS', 'LOAN', 'FEE']);
    expect(modules[0].label).not.toBe(modules[0].code);
  });

  it('accorde le mot « événement » au nombre', () => {
    // Le pluriel systématique ne se voit plus au bout de trois relectures ; le premier lecteur,
    // lui, le voit tout de suite.
    expect(libelleNombreEvenements(1)).toBe('1 événement');
    expect(libelleNombreEvenements(14)).toBe('14 événements');
    expect(libelleNombreEvenements(0)).toBe('0 événement');
  });

  it('ne déclare paramétrable que la commission', () => {
    expect(parametrables(CATALOGUE_DEMONSTRATION).map((e) => e.eventType))
      .toEqual(['FEE_CHARGE']);
  });
});

/**
 * L'essai de démonstration tient le rôle du socle : ses réponses doivent obéir aux mêmes règles,
 * sans quoi la démonstration montrerait une écriture que la production refuserait.
 */
describe('l’essai de démonstration', () => {
  const COMMISSION: readonly LigneSaisie[] = [
    ligne({ amount: 'net_booked + tax_booked', label: 'Commission' }),
    ligne({ account: 'PARAM:fee_income', direction: 'CREDIT', amount: 'net_booked' }),
    ligne({ account: 'PARAM:fee_tax', direction: 'CREDIT', amount: 'tax_booked',
            condition: 'tax_booked > 0' }),
  ];
  const DERIVATIONS = [['net_booked', 'round(net, 0)'], ['tax_booked', 'round(tax, 0)']] as const;

  it('produit l’écriture et ses totaux', () => {
    const essai = essaiDeDemonstration('FEE_CHARGE', 0, COMMISSION, DERIVATIONS,
                                       { net: '1234.56', tax: '222.22' });
    expect(essai.rejection).toBeNull();
    expect(essai.variables).toEqual(['net', 'tax']);
    expect(essai.debit).toBe(1457);
    expect(essai.credit).toBe(1457);
  });

  it('dit pourquoi une ligne est écartée', () => {
    const essai = essaiDeDemonstration('FEE_CHARGE', 0, COMMISSION, DERIVATIONS,
                                       { net: '1000', tax: '0' });
    expect(essai.lines[2].skipped).toBe('CONDITION');
    expect(essai.debit).toBe(1000);
  });

  it('refuse un montant non comptabilisable plutôt que de l’arrondir', () => {
    const brut: readonly LigneSaisie[] = [
      ligne({ amount: 'net' }),
      ligne({ account: 'PARAM:fee_income', direction: 'CREDIT', amount: 'net' }),
    ];
    const essai = essaiDeDemonstration('FEE_CHARGE', 0, brut, [], { net: '1234.56' });
    expect(essai.rejection?.code).toBe('MONTANT_NON_COMPTABILISABLE');
  });

  it('refuse un montant négatif : le sens est porté par la direction', () => {
    const inverse: readonly LigneSaisie[] = [
      ligne({ amount: '0 - net' }),
      ligne({ account: 'PARAM:fee_income', direction: 'CREDIT', amount: '0 - net' }),
    ];
    const essai = essaiDeDemonstration('FEE_CHARGE', 0, inverse, [], { net: '100' });
    expect(essai.rejection?.code).toBe('MONTANT_NEGATIF');
  });

  it('arrondit au pair, comme le socle', () => {
    // 0,5 va au chiffre pair : c'est HALF_EVEN, et c'est ce qui fait qu'un schéma « naturel »
    // se déséquilibre là où personne ne l'attend.
    const essai = essaiDeDemonstration('FEE_CHARGE', 0, COMMISSION, DERIVATIONS,
                                       { net: '0.5', tax: '1.5' });
    expect(essai.derived[0].value).toBe(0);
    expect(essai.derived[1].value).toBe(2);
  });

  it('rend le schéma du socle sur un cas réel', () => {
    const retrait = CATALOGUE_DEMONSTRATION.find((e) => e.eventType === 'CASH_WITHDRAWAL')!;
    const essai = essaiDeDemonstration('CASH_WITHDRAWAL', 0,
      retrait.lines.map((l) => ({ account: l.account, direction: l.direction, amount: l.amount,
                                  condition: l.condition ?? '', label: l.label ?? '' })),
      retrait.derivations.map((d) => [d.name, d.expression] as const),
      { amount: '5000', fee: '500', tax: '90' });
    expect(essai.rejection).toBeNull();
    expect(essai.debit).toBe(5590);
    expect(essai.credit).toBe(5590);
  });
});
