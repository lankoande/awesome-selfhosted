import { describe, expect, it } from 'vitest';
import {
  apercu, cleDeControle, DemandeRegle, obstaclesAuGabarit, segmentNeuf,
} from './etablissement.modele';

const CONTEXTE = { bankCode: '10015', branchCode: '00031', jour: '2026-09-18', compteur: 42 };

function regle(partiel: Partial<DemandeRegle> = {}): DemandeRegle {
  return {
    domain: 'ACCOUNT',
    label: 'Numéro de compte',
    segments: [segmentNeuf('SEQUENCE')],
    sequenceScope: 'ENTITY',
    sequenceReset: 'NEVER',
    sequenceStart: 1,
    ...partiel,
  };
}

describe('la clé de contrôle', () => {
  it('ferme le RIB : le numéro entier est divisible par 97', () => {
    const numero = '100150003100000000004';
    const cle = cleDeControle('RIB_97', numero);
    expect(BigInt(numero + cle) % 97n).toBe(0n);
  });

  it('tient toujours sur deux chiffres', () => {
    for (let i = 1; i < 300; i++) {
      expect(cleDeControle('RIB_97', String(i))).toHaveLength(2);
    }
  });

  it('transcode les lettres selon la table du RIB', () => {
    // S vaut 2, pas 1 : les trois séries de la table ne sont pas alignées, et
    // un modulo naïf donnerait une clé fausse sur tout numéro contenant un S.
    expect(cleDeControle('RIB_97', 'S0015')).toBe(cleDeControle('RIB_97', '20015'));
    expect(cleDeControle('RIB_97', 'A0015')).toBe(cleDeControle('RIB_97', '10015'));
    expect(cleDeControle('RIB_97', 'Z0015')).toBe(cleDeControle('RIB_97', '90015'));
  });

  it('rend la clé de Luhn sur un chiffre', () => {
    expect(cleDeControle('LUHN', '7992739871')).toBe('3');
  });
});

describe("l'aperçu", () => {
  it('compose un RIB complet', () => {
    const numero = apercu(regle({
      segments: [
        { kind: 'BANK_CODE', literalValue: null, length: 5, padChar: '0', datePattern: null,
          algorithm: null },
        { kind: 'BRANCH_CODE', literalValue: null, length: 5, padChar: '0', datePattern: null,
          algorithm: null },
        { kind: 'SEQUENCE', literalValue: null, length: 12, padChar: '0', datePattern: null,
          algorithm: null },
        { kind: 'CHECK_DIGITS', literalValue: null, length: 2, padChar: '0', datePattern: null,
          algorithm: 'RIB_97' },
      ],
    }), CONTEXTE);

    expect(numero).toHaveLength(24);
    expect(numero.startsWith('1001500031000000000042')).toBe(true);
    expect(BigInt(numero) % 97n).toBe(0n);
  });

  it('cadre le compteur et rend la date au format demandé', () => {
    const numero = apercu(regle({
      segments: [
        { kind: 'LITERAL', literalValue: 'DC-', length: null, padChar: null, datePattern: null,
          algorithm: null },
        { kind: 'DATE', literalValue: null, length: null, padChar: null, datePattern: 'yyyy',
          algorithm: null },
        { kind: 'LITERAL', literalValue: '-', length: null, padChar: null, datePattern: null,
          algorithm: null },
        { kind: 'SEQUENCE', literalValue: null, length: 4, padChar: '0', datePattern: null,
          algorithm: null },
      ],
    }), CONTEXTE);

    expect(numero).toBe('DC-2026-0042');
  });

  it('montre ce qui manque plutôt que de refuser', () => {
    // L'aperçu sert à lire un gabarit en cours d'écriture. Un code banque que
    // l'établissement ne déclare pas encore se voit ici, avant l'activation.
    const numero = apercu(regle({
      segments: [
        { kind: 'BANK_CODE', literalValue: null, length: 5, padChar: '0', datePattern: null,
          algorithm: null },
        { kind: 'SEQUENCE', literalValue: null, length: 3, padChar: '0', datePattern: null,
          algorithm: null },
      ],
    }), { ...CONTEXTE, bankCode: null });

    expect(numero).toBe('0000?042');
  });
});

describe('les obstacles au gabarit', () => {
  it('laisse passer un gabarit complet', () => {
    expect(obstaclesAuGabarit(regle())).toEqual([]);
  });

  it('refuse un gabarit sans compteur', () => {
    expect(obstaclesAuGabarit(regle({ segments: [segmentNeuf('LITERAL')] })))
      .toEqual(expect.arrayContaining([expect.stringContaining('porte un compteur')]));
  });

  it('refuse une clé de contrôle qui n\'est pas le dernier segment', () => {
    expect(obstaclesAuGabarit(regle({
      segments: [segmentNeuf('SEQUENCE'), segmentNeuf('CHECK_DIGITS'), segmentNeuf('LITERAL')],
    }))).toEqual(expect.arrayContaining([expect.stringContaining('dernier segment')]));
  });

  it('refuse une série par agence sans code agence dans le numéro', () => {
    // Sans le code agence, deux agences composeraient le même numéro : le
    // doublon n'apparaîtrait qu'à l'insertion, des mois plus tard.
    expect(obstaclesAuGabarit(regle({ sequenceScope: 'BRANCH' })))
      .toEqual(expect.arrayContaining([expect.stringContaining('même numéro')]));
  });

  it('accepte une série par agence quand le code agence est au gabarit', () => {
    expect(obstaclesAuGabarit(regle({
      sequenceScope: 'BRANCH',
      segments: [segmentNeuf('BRANCH_CODE'), segmentNeuf('SEQUENCE')],
    }))).toEqual([]);
  });
});
