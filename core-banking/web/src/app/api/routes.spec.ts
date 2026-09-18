import { chemin } from './routes';

describe('chemin', () => {
  it('substitue les variables du gabarit', () => {
    expect(chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balance',
                  { legalEntityId: 'BF-01', accountId: 'a-42' }))
      .toBe('/v1/entities/BF-01/accounts/a-42/balance');
  });

  it('rend tel quel un chemin sans variable', () => {
    expect(chemin('/v1/openapi.json')).toBe('/v1/openapi.json');
  });

  it("encode les valeurs : un identifiant qui porte une barre changerait de route", () => {
    expect(chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balance',
                  { legalEntityId: 'e', accountId: 'a/../admin' }))
      .toBe('/v1/entities/e/accounts/a%2F..%2Fadmin/balance');
  });

  it('refuse une variable vide plutôt que de fabriquer une autre route', () => {
    expect(() => chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balance',
                        { legalEntityId: 'e', accountId: '' }))
      .toThrow(/\{accountId\}/);
  });
});
