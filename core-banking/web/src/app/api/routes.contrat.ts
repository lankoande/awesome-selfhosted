import { chemin } from './routes';

/**
 * Les garanties de `chemin()`, vérifiées à la compilation.
 *
 * Ce fichier n'est importé par personne : il n'entre donc pas dans le paquet
 * livré. Il est en revanche compilé par `tsconfig.app.json`, donc par
 * `ng build` — et c'est tout l'intérêt. Si une de ces trois erreurs cessait
 * d'en être une, `@ts-expect-error` deviendrait inutile et la compilation
 * échouerait sur TS2578 : le garde-fou se dénonce lui-même quand il tombe.
 *
 * Vérifié en rendant un appel valide : TS2578 apparaît bien.
 */
export function assertionsDeChemin(): void {
  // Un chemin du contrat, avec toutes ses variables : accepté.
  chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balance',
         { legalEntityId: 'e1', accountId: 'a1' });

  // @ts-expect-error un chemin absent du contrat (ici « balances ») est refusé
  chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balances',
         { legalEntityId: 'e1', accountId: 'a1' });

  // @ts-expect-error une variable mal nommée est refusée
  chemin('/v1/entities/{legalEntityId}/transfers', { entityId: 'e1' });

  // @ts-expect-error une variable oubliée est refusée
  chemin('/v1/entities/{legalEntityId}/accounts/{accountId}/balance', { legalEntityId: 'e1' });
}
