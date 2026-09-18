#!/usr/bin/env python3
"""Contrôle des liens internes de la documentation.

Un lien cassé dans une documentation de plusieurs milliers de lignes ne se voit
pas à la relecture : il se voit le jour où quelqu'un le suit. Ce contrôle les
attrape au moment où on les casse — c'est-à-dire en déplaçant ou en renommant
un fichier, ce qui arrive à chaque réorganisation.

Il ne vérifie que ce qui est vérifiable sans réseau : les cibles de fichiers et
les ancres de titres à l'intérieur du dépôt. Les liens http(s) ne sont pas
suivis — un contrôle qui dépend d'un site tiers échoue le jour où ce site est
lent, et une barrière qui échoue sans raison est une barrière qu'on désactive.

Il couvre tout le dépôt :

    python3 docs/outils/liens.py

Il était restreint à `docs/` et `core-banking/` tant que la racine portait les
listes héritées d'awesome-selfhosted, dont les ancres suivent une autre
convention. Ces fichiers sont partis avec le fork ; la restriction aussi.
"""
import os
import re
import sys

LIEN = re.compile(r'(?<!\!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
IMAGE = re.compile(r'!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
TITRE = re.compile(r'^(#{1,6})\s+(.*?)\s*$', re.M)


def ancre(titre: str) -> str:
    """L'ancre que GitHub fabrique pour un titre.

    Minuscules, ponctuation retirée, espaces en tirets. Les accents **restent** :
    « 13. États financiers » donne `13-états-financiers`. Les dépouiller ferait
    passer ce contrôle pour un défaut de la documentation alors que c'est le
    contrôle qui aurait tort — et on corrigerait alors des liens justes.
    """
    t = re.sub(r'[`*_~]', '', titre.lower())
    t = re.sub(r'[^\w\s-]', '', t)          # \w est déjà unicode en Python 3
    # Chaque espace devient un tiret, les suites ne sont pas réduites :
    # « Clients & KYC » perd l'esperluette et garde ses deux espaces, donc
    # `clients--kyc`. Réduire donnerait une ancre qui n'existe pas.
    return re.sub(r'\s', '-', t.strip())


def ancres_de(chemin: str) -> set[str]:
    with open(chemin, encoding='utf-8') as f:
        texte = f.read()
    vues: dict[str, int] = {}
    sortie = set()
    for _, titre in TITRE.findall(texte):
        a = ancre(titre)
        n = vues.get(a, 0)
        vues[a] = n + 1
        sortie.add(a if n == 0 else f'{a}-{n}')
    return sortie


def controler(racine: str) -> list[str]:
    fichiers = []
    for dossier, sous, noms in os.walk(racine):
        if any(p in dossier for p in ('/.git', 'node_modules', '/target', '/dist')):
            sous[:] = []
            continue
        fichiers += [os.path.join(dossier, n) for n in noms if n.endswith('.md')]

    cache: dict[str, set[str]] = {}
    fautes = []
    for f in sorted(fichiers):
        with open(f, encoding='utf-8') as fh:
            texte = fh.read()
        base = os.path.dirname(f)
        for motif in (LIEN, IMAGE):
            for cible in motif.findall(texte):
                if cible.startswith(('http://', 'https://', 'mailto:')):
                    continue
                fichier, _, anc = cible.partition('#')
                chemin = os.path.normpath(os.path.join(base, fichier)) if fichier else f
                if not os.path.exists(chemin):
                    fautes.append(f'{f} → {cible} (fichier absent)')
                    continue
                if anc and chemin.endswith('.md'):
                    if chemin not in cache:
                        cache[chemin] = ancres_de(chemin)
                    if anc.lower() not in cache[chemin]:
                        fautes.append(f'{f} → {cible} (ancre absente)')
    return fautes


if __name__ == '__main__':
    fautes = controler(sys.argv[1] if len(sys.argv) > 1 else '.')
    for faute in fautes:
        print(faute)
    print(f'{len(fautes)} lien(s) cassé(s)' if fautes else 'Tous les liens internes résolvent.')
    sys.exit(1 if fautes else 0)
