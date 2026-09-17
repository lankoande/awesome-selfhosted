import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { appConfig } from './app.config';
import { AppConfig } from './core/config/runtime-config';
import { Apparence } from './core/apparence/apparence';

/**
 * Le démarrage, joué pour de bon. Un initialiseur qui appelle `inject()` après
 * un `await` compile, passe les tests de composants, et casse l'application au
 * premier chargement (NG0203) : seule l'exécution de la séquence le montre.
 */
describe('séquence de démarrage', () => {
  it("exécute l’initialiseur jusqu’au bout", async () => {
    TestBed.configureTestingModule({ providers: [...appConfig.providers] });

    await TestBed.inject(ApplicationInitStatus).donePromise;

    // `config.json` est injoignable dans le lanceur de tests : l'application
    // doit démarrer quand même, sur les valeurs par défaut.
    expect(TestBed.inject(AppConfig).valeur().locale).toBe('fr-FR');
    expect(TestBed.inject(Apparence).theme()).toBe('light');
  });
});
