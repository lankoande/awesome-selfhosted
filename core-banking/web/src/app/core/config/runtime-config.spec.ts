import { TestBed } from '@angular/core/testing';
import { AppConfig, CONFIG_PAR_DEFAUT } from './runtime-config';

/**
 * L'accent appartient à la banque, la structure nous appartient. Ce test tient
 * les deux bouts : l'accent déclaré arrive bien dans les deux thèmes, et
 * `config.json` — qui est du contenu de déploiement, pas du code — ne peut pas
 * écrire autre chose qu'une couleur dans la feuille de style.
 */
describe('accent de déploiement', () => {
  function feuille(): string {
    return document.getElementById('cb-accent')?.textContent ?? '';
  }

  it('écrit une règle par thème, pas un style en ligne qui figerait le sombre', () => {
    const config = TestBed.inject(AppConfig);

    config.appliquer({
      banque: {
        ...CONFIG_PAR_DEFAUT.banque,
        accent: '#8c2f1e',
        accentSurvol: '#6d2417',
        accentContraste: '#fbf9f4',
        sombre: { accent: '#e0897a', accentSurvol: '#f0a89b', accentContraste: '#2c1e1a' },
      },
    });

    const css = feuille();
    expect(css).toContain(':root{--cb-accent:#8c2f1e;');
    expect(css).toContain("[data-theme='dark']{--cb-accent:#e0897a;");
    expect(document.documentElement.getAttribute('style') ?? '').not.toContain('--cb-accent');
  });

  it('laisse le thème sombre sur ses tokens quand la banque ne le déclare pas', () => {
    TestBed.inject(AppConfig).appliquer({
      banque: { ...CONFIG_PAR_DEFAUT.banque, accent: '#1f4e46', sombre: null },
    });

    expect(feuille()).toContain(':root{');
    expect(feuille()).not.toContain('data-theme');
  });

  it("refuse ce qui n’a pas la forme d’une couleur", () => {
    TestBed.inject(AppConfig).appliquer({
      banque: { ...CONFIG_PAR_DEFAUT.banque, accent: '#123456} body{display:none}', sombre: null },
    });

    expect(feuille()).not.toContain('display:none');
    expect(feuille()).not.toContain('--cb-accent:');
  });
});
