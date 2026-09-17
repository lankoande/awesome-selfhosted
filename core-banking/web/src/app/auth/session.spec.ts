import { Session } from './session';

const PORTEUR = {
  subjectId: 'u-1', username: 'a.kabore', nom: 'Abdoulaye Kaboré',
  roles: ['TELLER'], agence: 'OUA2', caisse: 'OUA2-C02',
};

describe('session', () => {
  it("ne rend le jeton que lorsque la session est ouverte", () => {
    const session = new Session();
    expect(session.jeton()).toBeNull();

    session.ouvrir('jeton', null, 3600, PORTEUR);
    expect(session.jeton()).toBe('jeton');

    // Verrouillé, le jeton n'est plus servi : rien ne part au socle au nom de
    // quelqu'un qui n'est plus devant l'écran.
    session.verrouiller();
    expect(session.jeton()).toBeNull();
    expect(session.porteur()).toEqual(PORTEUR);

    session.deverrouiller();
    expect(session.jeton()).toBe('jeton');
  });

  it('ferme en effaçant tout', () => {
    const session = new Session();
    session.ouvrir('jeton', 'refresh', 3600, PORTEUR);
    session.fermer();

    expect(session.jeton()).toBeNull();
    expect(session.jetonDeRafraichissement()).toBeNull();
    expect(session.porteur()).toBeNull();
    expect(session.etat()).toBe('anonyme');
  });

  it("considère le jeton périmé un peu avant l’heure", () => {
    const session = new Session();
    // 20 secondes de durée : la marge de 30 s le rend périmé tout de suite.
    session.ouvrir('jeton', null, 20, PORTEUR);
    expect(session.perime()).toBe(true);

    session.ouvrir('jeton', null, 3600, PORTEUR);
    expect(session.perime()).toBe(false);
  });

  it("ne verrouille pas une session qui n’est pas ouverte", () => {
    const session = new Session();
    session.verrouiller();
    expect(session.etat()).toBe('inconnue');
  });
});
