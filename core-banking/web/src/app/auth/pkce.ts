/**
 * PKCE — RFC 7636.
 *
 * Un client public ne peut pas garder un secret : n'importe qui peut lire le
 * bundle. PKCE remplace le secret par une preuve à usage unique — le poste
 * tire un `code_verifier` au hasard, n'envoie que son empreinte SHA-256 à
 * l'autorisation, et ne révèle le verifier qu'à l'échange du code. Un code
 * intercepté ne sert donc à rien sans le verifier, qui n'a jamais transité.
 *
 * Tout est ici en fonctions pures : c'est la partie qu'on veut pouvoir tester
 * sans navigateur ni serveur.
 */

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

/** Chaîne aléatoire non ambiguë, tirée du générateur cryptographique. */
export function hasard(longueur = 64): string {
  const octets = new Uint8Array(longueur);
  crypto.getRandomValues(octets);
  return Array.from(octets, (octet) => ALPHABET[octet % ALPHABET.length]).join('');
}

/** Base64 sans remplissage ni caractère à échapper dans une URL. */
export function base64Url(octets: ArrayBuffer): string {
  const binaire = String.fromCharCode(...new Uint8Array(octets));
  return btoa(binaire).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** `code_challenge` = BASE64URL(SHA-256(verifier)). Méthode S256, jamais `plain`. */
export async function empreinte(verifier: string): Promise<string> {
  const condense = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(condense);
}

export interface DefiPkce {
  readonly verifier: string;
  readonly challenge: string;
  readonly state: string;
  readonly nonce: string;
}

export async function defi(): Promise<DefiPkce> {
  const verifier = hasard(64);
  return { verifier, challenge: await empreinte(verifier), state: hasard(32), nonce: hasard(32) };
}

/**
 * Le `state` est comparé au retour : il protège du CSRF sur la redirection.
 * La comparaison est faite en temps constant — une comparaison qui s'arrête au
 * premier caractère différent renseigne un attaquant sur ce qu'il a deviné.
 */
export function memeState(attendu: string, recu: string): boolean {
  if (attendu.length !== recu.length) return false;
  let ecart = 0;
  for (let i = 0; i < attendu.length; i++) ecart |= attendu.charCodeAt(i) ^ recu.charCodeAt(i);
  return ecart === 0;
}

/** Les paramètres d'une URL d'autorisation, construits une seule fois, ici. */
export function urlAutorisation(
  endpoint: string,
  options: { clientId: string; redirectUri: string; scope: string; defi: DefiPkce; silencieux?: boolean },
): string {
  const parametres = new URLSearchParams({
    response_type: 'code',
    client_id: options.clientId,
    redirect_uri: options.redirectUri,
    scope: options.scope,
    state: options.defi.state,
    nonce: options.defi.nonce,
    code_challenge: options.defi.challenge,
    code_challenge_method: 'S256',
  });
  // `prompt=none` : renouvellement silencieux. Si la session du fournisseur est
  // tombée, il répond par une erreur plutôt que par un écran de connexion — ce
  // qui est exactement ce qu'on veut savoir sans déranger l'opérateur.
  if (options.silencieux) parametres.set('prompt', 'none');
  return `${endpoint}?${parametres.toString()}`;
}
