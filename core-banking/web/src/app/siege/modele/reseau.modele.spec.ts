import { describe, expect, it } from 'vitest';
import {
  Agence, ConditionsDeBanque, enArbre, HeureLimite, obstaclesALAgence, obstaclesALaRegle,
  obstaclesALHeureLimite, obstaclesAuFerie, phraseDeLaRegle, RegleDateValeur,
} from './reseau.modele';

function agence(id: string, code: string, kind: Agence['kind'],
                parentId: string | null): Agence {
  return { id, code, name: code, kind, parentId, status: 'ACTIVE', openedOn: '2020-01-01',
           closedOn: null };
}

const CONDITIONS: ConditionsDeBanque = {
  calendarCode: 'BF', calendarLabel: 'Burkina Faso', coversFrom: '2026-01-01',
  coversTo: '2026-12-31', weekend: [6, 7],
  holidays: [{ date: '2026-08-05', label: 'Fête nationale' }],
  rules: [], cutoffs: [],
};

describe('l’arbre du réseau', () => {
  it('place le siège en tête, puis les régions, puis les agences', () => {
    const noeuds = enArbre([
      agence('ag-2', '00021', 'BRANCH', 'reg-1'),
      agence('siege', 'SIEGE', 'HEAD_OFFICE', null),
      agence('reg-1', 'REG-CENTRE', 'REGION', 'siege'),
    ]);
    expect(noeuds.map((n) => n.agence.code)).toEqual(['SIEGE', 'REG-CENTRE', '00021']);
    expect(noeuds.map((n) => n.profondeur)).toEqual([0, 1, 2]);
  });

  it('montre une agence dont le parent manque plutôt que de la perdre', () => {
    // Une ligne qui disparaît parce que son parent est introuvable serait le pire des deux mondes.
    const noeuds = enArbre([agence('ag-9', '00099', 'BRANCH', 'inconnu')]);
    expect(noeuds).toHaveLength(1);
    expect(noeuds[0].profondeur).toBe(0);
  });

  it('ne boucle pas sur un cycle, et ne perd aucune des deux agences', () => {
    // Un cycle n'a pas de racine : l'arbre seul les ferait disparaître toutes les deux, et on
    // chercherait l'agence au lieu de chercher le cycle.
    const noeuds = enArbre([
      agence('a', 'A', 'BRANCH', 'b'),
      agence('b', 'B', 'BRANCH', 'a'),
    ]);
    expect(noeuds.map((n) => n.agence.code).sort()).toEqual(['A', 'B']);
  });

  it('rend chaque agence une fois et une seule', () => {
    const reseau = [
      agence('siege', 'SIEGE', 'HEAD_OFFICE', null),
      agence('reg', 'REG', 'REGION', 'siege'),
      agence('ag', '00021', 'BRANCH', 'reg'),
      agence('orphe', '00099', 'BRANCH', 'disparue'),
    ];
    const codes = enArbre(reseau).map((n) => n.agence.code);
    expect(codes).toHaveLength(reseau.length);
    expect(new Set(codes).size).toBe(reseau.length);
  });
});

describe('la création d’une agence', () => {
  const demande = {
    code: '00023', name: 'Agence Gounghin', kind: 'BRANCH' as const, parentId: 'siege',
    openedOn: '2026-10-01', liaisonAccounts: { XOF: 'gl-liaison' },
  };

  it('passe quand tout est nommé', () => {
    expect(obstaclesALAgence(demande, ['00021', '00022'])).toEqual([]);
  });

  it('refuse un code déjà porté, quelle que soit la casse', () => {
    expect(obstaclesALAgence(demande, ['00023'])).toHaveLength(1);
    expect(obstaclesALAgence({ ...demande, code: 'siege' }, ['SIEGE'])).toHaveLength(1);
  });

  it('exige un rattachement, sauf pour le siège', () => {
    expect(obstaclesALAgence({ ...demande, parentId: null }, [])).toHaveLength(1);
    expect(obstaclesALAgence({ ...demande, kind: 'HEAD_OFFICE', parentId: null }, []))
      .toEqual([]);
  });

  it('exige au moins un compte de liaison, et qu’il soit désigné', () => {
    // Sans lui, la première opération déplacée échoue — en agence, devant un client.
    expect(obstaclesALAgence({ ...demande, liaisonAccounts: {} }, [])).toHaveLength(1);
    expect(obstaclesALAgence({ ...demande, liaisonAccounts: { XOF: '' } }, [])).toHaveLength(1);
  });
});

describe('les jours fériés', () => {
  it('refuse un doublon et un libellé vide', () => {
    expect(obstaclesAuFerie({ date: '2026-08-05', label: 'Fête' }, CONDITIONS)).toHaveLength(1);
    expect(obstaclesAuFerie({ date: '2026-08-06', label: ' ' }, CONDITIONS)).toHaveLength(1);
  });

  it('refuse une date hors de la période que le calendrier couvre', () => {
    // Au-delà, le calendrier refuse de répondre plutôt que de présumer un jour ouvré.
    expect(obstaclesAuFerie({ date: '2027-01-01', label: 'Jour de l’an' }, CONDITIONS))
      .toHaveLength(1);
    expect(obstaclesAuFerie({ date: '2025-12-25', label: 'Noël' }, CONDITIONS)).toHaveLength(1);
  });

  it('accepte un férié dans la période', () => {
    expect(obstaclesAuFerie({ date: '2026-12-25', label: 'Noël' }, CONDITIONS)).toEqual([]);
  });
});

describe('les règles de date de valeur', () => {
  const regle: RegleDateValeur = {
    id: 'r-1', operationType: 'TRANSFER', channel: 'CLEARING', direction: 'DEBIT', offset: 2,
    unit: 'BUSINESS_DAYS', convention: 'FOLLOWING', validFrom: '2026-01-01', validTo: null,
  };

  it('se dit en une phrase, celle qu’on répète au client', () => {
    const phrase = phraseDeLaRegle(regle);
    expect(phrase).toContain('TRANSFER');
    expect(phrase).toContain('par CLEARING');
    expect(phrase).toContain('+2 jours ouvrés');
    expect(phrase).toContain('jour ouvré suivant');
  });

  it('dit « le jour même » quand le décalage est nul', () => {
    expect(phraseDeLaRegle({ ...regle, offset: 0 })).toContain('le jour même');
  });

  it('accorde le singulier : un écran de banque s’écrit en français', () => {
    expect(phraseDeLaRegle({ ...regle, offset: 1 })).toContain('+1 jour ouvré');
    expect(phraseDeLaRegle({ ...regle, offset: 1 })).not.toContain('jours');
    expect(phraseDeLaRegle({ ...regle, offset: -1, unit: 'CALENDAR_DAYS' }))
      .toContain('-1 jour calendaire');
    expect(phraseDeLaRegle({ ...regle, offset: 3 })).toContain('+3 jours ouvrés');
  });

  it('dit « tous canaux » quand aucun canal n’est visé', () => {
    expect(phraseDeLaRegle({ ...regle, channel: null })).toContain('tous canaux');
  });

  it('exige un type d’opération, un décalage entier et une entrée en vigueur', () => {
    const demande = {
      operationType: 'TRANSFER', channel: null, direction: 'DEBIT' as const, offset: 1,
      unit: 'BUSINESS_DAYS' as const, convention: 'FOLLOWING' as const,
      validFrom: '2026-01-01', validTo: null,
    };
    expect(obstaclesALaRegle(demande)).toEqual([]);
    expect(obstaclesALaRegle({ ...demande, operationType: ' ' })).toHaveLength(1);
    expect(obstaclesALaRegle({ ...demande, offset: null })).toHaveLength(1);
    expect(obstaclesALaRegle({ ...demande, validFrom: null })).toHaveLength(1);
    expect(obstaclesALaRegle({ ...demande, validTo: '2025-12-31' })).toHaveLength(1);
  });
});

describe('les heures limites', () => {
  const existante: HeureLimite = {
    id: 'c-1', channel: 'CLEARING', cutoffTime: '14:30', closesChannel: false,
    validFrom: '2026-01-01', validTo: null,
  };
  const demande = {
    channel: 'CLEARING', cutoffTime: '15:00', closesChannel: false,
    validFrom: '2026-06-01', validTo: null,
  };

  it('refuse une heure mal écrite', () => {
    expect(obstaclesALHeureLimite({ ...demande, cutoffTime: '25:00' }, [])).toHaveLength(1);
    expect(obstaclesALHeureLimite({ ...demande, cutoffTime: '9:00' }, [])).toHaveLength(1);
    expect(obstaclesALHeureLimite({ ...demande, cutoffTime: '09:00' }, [])).toEqual([]);
  });

  it('refuse un chevauchement de même portée, avant que le socle ne le fasse', () => {
    // Le refus du socle arriverait après le second regard : autant le dire à la saisie.
    expect(obstaclesALHeureLimite(demande, [existante])).toHaveLength(1);
    // Un autre canal ne chevauche pas.
    expect(obstaclesALHeureLimite({ ...demande, channel: 'BRANCH' }, [existante])).toEqual([]);
    // Une période qui commence après la fin de l'existante ne chevauche pas non plus.
    expect(obstaclesALHeureLimite(
      { ...demande, validFrom: '2027-01-01' },
      [{ ...existante, validTo: '2026-12-31' }])).toEqual([]);
  });
});
