import { base64Url, defi, empreinte, hasard, memeState, urlAutorisation } from './pkce';

describe('PKCE', () => {
  it("tire un verifier dans le domaine autorisé par la RFC", () => {
    const verifier = hasard(64);
    expect(verifier).toHaveLength(64);
    // RFC 7636 : 43 à 128 caractères pris dans [A-Za-z0-9-._~].
    expect(verifier).toMatch(/^[A-Za-z0-9\-._~]+$/);
    expect(hasard(64)).not.toBe(verifier);
  });

  it("encode en base64url : ni remplissage, ni caractère à échapper", () => {
    const octets = new Uint8Array([251, 255, 190, 0, 62, 63]).buffer;
    const encode = base64Url(octets);
    expect(encode).not.toContain('=');
    expect(encode).not.toContain('+');
    expect(encode).not.toContain('/');
  });

  it("calcule le challenge en S256, jamais en clair", async () => {
    // Vecteur de la RFC 7636, annexe B.
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
    expect(await empreinte(verifier)).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('compare le state en temps constant, et refuse une longueur différente', () => {
    expect(memeState('abcdef', 'abcdef')).toBe(true);
    expect(memeState('abcdef', 'abcdeg')).toBe(false);
    expect(memeState('abcdef', 'abcde')).toBe(false);
  });

  it("construit une URL d'autorisation complète, sans secret", async () => {
    const garde = await defi();
    const url = new URL(urlAutorisation('https://kc.test/auth', {
      clientId: 'back-office',
      redirectUri: 'https://bo.test/auth/retour',
      scope: 'openid profile',
      defi: garde,
    }));

    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')).toBe(garde.challenge);
    expect(url.searchParams.get('state')).toBe(garde.state);
    // Le verifier ne part jamais à l'autorisation : c'est tout l'intérêt.
    expect(url.toString()).not.toContain(garde.verifier);
    expect(url.searchParams.get('client_secret')).toBeNull();
  });

  it("demande le renouvellement silencieux avec prompt=none", async () => {
    const url = new URL(urlAutorisation('https://kc.test/auth', {
      clientId: 'back-office', redirectUri: 'https://bo.test/auth/retour',
      scope: 'openid', defi: await defi(), silencieux: true,
    }));
    expect(url.searchParams.get('prompt')).toBe('none');
  });
});
