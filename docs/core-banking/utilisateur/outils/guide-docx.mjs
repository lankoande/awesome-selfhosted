import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const D = require('docx');
const {
  Document, Packer, Paragraph, TextRun, ImageRun, HeadingLevel, AlignmentType, PageBreak,
  Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle, LevelFormat,
  TableOfContents, Header, Footer, PageNumber, ExternalHyperlink, convertMillimetersToTwip,
} = D;

const SRC = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const SORTIE = process.argv[2] ?? path.join(SRC, 'guide-utilisateur-back-office.docx');

// --------------------------------------------------------------- mise en page
const MARGE = convertMillimetersToTwip(22);
const LARGEUR_PAGE = convertMillimetersToTwip(210);
const LARGEUR_UTILE = LARGEUR_PAGE - 2 * MARGE;       // twips
const LARGEUR_UTILE_PX = Math.round((LARGEUR_UTILE / 1440) * 96); // ≈ 627 px

const ENCRE = '1B1917';
const ENCRE2 = '4A4540';
const ACCENT = '1F4E46';
const REGLE = 'D8D2C6';
const ENTETE_FOND = 'EFEBE2';
const CITATION_FOND = 'F6F3EC';

let compteurFigure = 0;
const enfants = [];

// ------------------------------------------------------------------- outillage
const dimensions = (fichier) => {
  const buf = fs.readFileSync(fichier);
  return { largeur: buf.readUInt32BE(16), hauteur: buf.readUInt32BE(20) };
};

function figure(relatif, legende) {
  const chemin = path.join(SRC, relatif);
  if (!fs.existsSync(chemin)) throw new Error('capture absente : ' + chemin);
  const { largeur, hauteur } = dimensions(chemin);
  // les captures sont en densité 2 ; on les rend à la largeur utile
  const l = LARGEUR_UTILE_PX;
  const h = Math.round((hauteur / largeur) * l);
  compteurFigure++;
  return [
    new Paragraph({
      spacing: { before: 200, after: 60 },
      alignment: AlignmentType.CENTER,
      keepNext: true,
      children: [new ImageRun({ type: 'png', data: fs.readFileSync(chemin), transformation: { width: l, height: h } })],
    }),
    new Paragraph({
      spacing: { after: 280 },
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: `Figure ${compteurFigure} — ${legende}`, size: 17, italics: true, color: ENCRE2 })],
    }),
  ];
}

/** Rend le balisage en ligne : **gras**, `code`, *italique*, [texte](lien). */
function runs(texte, base = {}) {
  const sortie = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\)|\*[^*]+\*)/g;
  let i = 0, m;
  const brut = (t) => { if (t) sortie.push(new TextRun({ text: t, ...base })); };
  while ((m = re.exec(texte))) {
    brut(texte.slice(i, m.index));
    const jeton = m[0];
    if (jeton.startsWith('**')) sortie.push(new TextRun({ text: jeton.slice(2, -2), bold: true, ...base }));
    else if (jeton.startsWith('`')) sortie.push(new TextRun({ text: jeton.slice(1, -1), font: 'Consolas', size: (base.size ?? 21) - 2, color: ACCENT, ...base, bold: base.bold }));
    else if (jeton.startsWith('[')) {
      // Le texte d'un lien porte parfois du balisage (`nom-de-fichier.md`) ; le
      // laisser tel quel afficherait les accents graves dans le document rendu.
      const [, t] = jeton.match(/\[([^\]]+)\]/);
      for (const bout of t.split(/(`[^`]+`)/).filter(Boolean)) {
        const code = bout.startsWith('`');
        sortie.push(new TextRun({
          text: code ? bout.slice(1, -1) : bout.replace(/\*\*/g, ''),
          ...base,
          font: code ? 'Consolas' : base.font,
          size: code ? (base.size ?? 21) - 2 : base.size,
          color: ACCENT,
          underline: {},
        }));
      }
    } else sortie.push(new TextRun({ text: jeton.slice(1, -1), italics: true, ...base }));
    i = m.index + jeton.length;
  }
  brut(texte.slice(i));
  return sortie.length ? sortie : [new TextRun({ text: '', ...base })];
}

const cellules = (ligne) => ligne.replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());

function tableau(lignes) {
  const entete = cellules(lignes[0]);
  const corps = lignes.slice(2).map(cellules);
  const n = entete.length;
  const colonnes = repartir(entete, corps, n);
  const cellule = (texte, { gras = false, fond } = {}, i) => new TableCell({
    width: { size: colonnes[i], type: WidthType.DXA },
    shading: fond ? { type: ShadingType.CLEAR, fill: fond, color: 'auto' } : undefined,
    margins: { top: 70, bottom: 70, left: 110, right: 110 },
    children: [new Paragraph({ spacing: { before: 0, after: 0 }, children: runs(texte, { size: 19, bold: gras, color: ENCRE }) })],
  });
  return new Table({
    columnWidths: colonnes,
    width: { size: LARGEUR_UTILE, type: WidthType.DXA },
    borders: ['top', 'bottom', 'left', 'right', 'insideHorizontal', 'insideVertical'].reduce((a, k) => {
      a[k] = { style: BorderStyle.SINGLE, size: 2, color: REGLE }; return a;
    }, {}),
    rows: [
      new TableRow({ tableHeader: true, children: entete.map((t, i) => cellule(t, { gras: true, fond: ENTETE_FOND }, i)) }),
      ...corps.map((r) => new TableRow({ children: Array.from({ length: n }, (_, i) => cellule(r[i] ?? '', {}, i)) })),
    ],
  });
}

/** Largeurs proportionnelles au contenu, bornées pour qu'aucune colonne ne s'écrase. */
function repartir(entete, corps, n) {
  const poids = Array.from({ length: n }, (_, i) => {
    const l = [entete[i], ...corps.map((r) => r[i] ?? '')].map((t) => t.replace(/\*\*|`/g, '').length);
    return Math.max(8, Math.min(70, l.reduce((a, b) => a + b, 0) / l.length * 0.6 + Math.max(...l) * 0.4));
  });
  const total = poids.reduce((a, b) => a + b, 0);
  const largeurs = poids.map((p) => Math.round((p / total) * LARGEUR_UTILE));
  largeurs[n - 1] += LARGEUR_UTILE - largeurs.reduce((a, b) => a + b, 0);
  return largeurs;
}

function citation(lignes) {
  return lignes.map((t, i) => new Paragraph({
    spacing: { before: i === 0 ? 160 : 0, after: i === lignes.length - 1 ? 200 : 0 },
    indent: { left: 240, right: 200 },
    shading: { type: ShadingType.CLEAR, fill: CITATION_FOND, color: 'auto' },
    border: { left: { style: BorderStyle.SINGLE, size: 18, color: ACCENT, space: 10 } },
    children: runs(t, { size: 21, color: ENCRE }),
  }));
}

// -------------------------------------------------------------- page de garde
const config = JSON.parse(fs.readFileSync('/home/user/awesome-selfhosted/core-banking/web/public/config.json', 'utf8'));
const banque = config?.banque?.nom ?? 'Socle bancaire';
const aujourdhui = new Date().toLocaleDateString('fr-FR', { day: '2-digit', month: 'long', year: 'numeric' });

enfants.push(
  new Paragraph({ spacing: { before: 2600, after: 0 }, children: [new TextRun({ text: banque.toUpperCase(), size: 20, color: ENCRE2, characterSpacing: 60 })] }),
  new Paragraph({
    spacing: { before: 280, after: 0 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 10, color: ACCENT, space: 14 } },
    children: [new TextRun({ text: "Guide de l'utilisateur", size: 58, bold: true, color: ENCRE })],
  }),
  new Paragraph({ spacing: { before: 260, after: 0 }, children: [new TextRun({ text: 'Back-office agence', size: 34, color: ACCENT })] }),
  new Paragraph({ spacing: { before: 520, after: 0 }, children: [new TextRun({ text: 'Guichet · Validation · Siège', size: 22, color: ENCRE2 })] }),
  new Paragraph({ spacing: { before: 120, after: 0 }, children: [new TextRun({ text: `Version du ${aujourdhui}`, size: 22, color: ENCRE2 })] }),
  new Paragraph({
    spacing: { before: 2200, after: 0 },
    border: { top: { style: BorderStyle.SINGLE, size: 4, color: REGLE, space: 12 } },
    children: [new TextRun({ text: "Les captures de ce guide viennent de l'application, en mode démonstration : les noms, les comptes et les montants qui y figurent sont fictifs.", size: 18, italics: true, color: ENCRE2 })],
  }),
  new Paragraph({ children: [new PageBreak()] }),
  new Paragraph({
    spacing: { after: 300 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: ACCENT, space: 8 } },
    children: [new TextRun({ text: 'Sommaire', bold: true, size: 36, color: ENCRE })],
  }),
  new TableOfContents('Sommaire', { hyperlink: true, headingStyleRange: '1-3' }),
  new Paragraph({ children: [new PageBreak()] }),
);

// ------------------------------------------------------------------ chapitres
const CHAPITRES = ['README.md', '01-prise-en-main.md', '02-especes.md', '03-virement-releve.md',
  '04-arrete-de-caisse.md', '05-validation.md', '06-clients.md', '07-credit.md', '08-siege.md',
  '09-conformite.md', '10-messages.md'];

for (const [n, nom] of CHAPITRES.entries()) {
  if (n > 0) enfants.push(new Paragraph({ children: [new PageBreak()] }));
  const lignes = fs.readFileSync(path.join(SRC, nom), 'utf8').split('\n');
  for (let i = 0; i < lignes.length; i++) {
    const ligne = lignes[i];
    const t = ligne.trim();

    if (!t) continue;
    if (/^---+$/.test(t)) continue;

    const image = t.match(/^!\[([^\]]*)\]\(([^)]+)\)$/);
    if (image) { enfants.push(...figure(image[2], image[1])); continue; }

    const titre = t.match(/^(#{1,4})\s+(.*)$/);
    if (titre) {
      const niveau = titre[1].length;
      const texte = titre[2].replace(/^\d+\.\s*/, '').replace(/\*\*/g, '');
      enfants.push(new Paragraph({
        heading: [HeadingLevel.HEADING_1, HeadingLevel.HEADING_1, HeadingLevel.HEADING_2, HeadingLevel.HEADING_3][niveau - 1],
        spacing: { before: niveau === 1 ? 0 : niveau === 2 ? 420 : 320, after: niveau === 1 ? 260 : 140 },
        border: niveau <= 2 ? { bottom: { style: BorderStyle.SINGLE, size: niveau === 1 ? 8 : 3, color: niveau === 1 ? ACCENT : REGLE, space: 8 } } : undefined,
        children: [new TextRun({ text: texte, bold: true, color: ENCRE, size: niveau === 1 ? 36 : niveau === 2 ? 27 : 23 })],
      }));
      continue;
    }

    if (t.startsWith('|')) {
      const bloc = [];
      while (i < lignes.length && lignes[i].trim().startsWith('|')) bloc.push(lignes[i].trim(), i++);
      i--;
      enfants.push(tableau(bloc.filter((x) => typeof x === 'string')));
      enfants.push(new Paragraph({ spacing: { after: 220 }, children: [] }));
      continue;
    }

    if (t.startsWith('> ')) {
      const bloc = [];
      while (i < lignes.length && lignes[i].trim().startsWith('>')) {
        const l = lignes[i].trim().replace(/^>\s?/, '');
        if (l) bloc.length && !lignes[i - 1].trim().endsWith('') ? bloc.push(l) : bloc.push(l);
        i++;
      }
      i--;
      enfants.push(...citation([bloc.join(' ')]));
      continue;
    }

    const puce = t.match(/^[-*]\s+(.*)$/);
    const numero = t.match(/^(\d+)\.\s+(.*)$/);
    if (puce || numero) {
      let texte = (puce ? puce[1] : numero[2]);
      while (i + 1 < lignes.length && lignes[i + 1].startsWith('  ') && lignes[i + 1].trim() && !/^\s*[-*\d]/.test(lignes[i + 1])) {
        texte += ' ' + lignes[++i].trim();
      }
      enfants.push(new Paragraph({
        numbering: puce ? { reference: 'puces', level: 0 } : { reference: 'etapes', level: 0 },
        spacing: { before: 40, after: 80 },
        children: runs(texte, { size: 21, color: ENCRE }),
      }));
      continue;
    }

    // paragraphe : on recolle les lignes repliées
    let texte = t;
    while (i + 1 < lignes.length) {
      const s = lignes[i + 1].trim();
      if (!s || s.startsWith('#') || s.startsWith('|') || s.startsWith('>') || /^[-*]\s/.test(s) || /^\d+\.\s/.test(s) || /^---+$/.test(s)) break;
      texte += ' ' + s; i++;
    }
    enfants.push(new Paragraph({
      spacing: { before: 0, after: 180, line: 290 },
      alignment: AlignmentType.JUSTIFIED,
      children: runs(texte, { size: 21, color: ENCRE }),
    }));
  }
}

// ------------------------------------------------------------------- document
const doc = new Document({
  creator: banque,
  title: "Guide de l'utilisateur — back-office agence",
  description: "Le back-office agence vu du comptoir : prise en main, opérations, validation, siège.",
  styles: {
    default: { document: { run: { font: 'Calibri', size: 21, color: ENCRE } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { size: 36, bold: true, color: ENCRE }, paragraph: { spacing: { before: 0, after: 260 }, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { size: 27, bold: true, color: ENCRE }, paragraph: { spacing: { before: 420, after: 140 }, outlineLevel: 1, keepNext: true } },
      { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { size: 23, bold: true, color: ENCRE }, paragraph: { spacing: { before: 320, after: 120 }, outlineLevel: 2, keepNext: true } },
    ],
  },
  numbering: {
    config: [
      { reference: 'puces', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 420, hanging: 220 } } } }] },
      { reference: 'etapes', levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 420, hanging: 220 } } } }] },
    ],
  },
  features: { updateFields: true },
  sections: [{
    properties: { page: { margin: { top: MARGE, bottom: MARGE, left: MARGE, right: MARGE } } },
    headers: { default: new Header({ children: [new Paragraph({
      alignment: AlignmentType.RIGHT,
      border: { bottom: { style: BorderStyle.SINGLE, size: 3, color: REGLE, space: 6 } },
      children: [new TextRun({ text: "Guide de l'utilisateur — back-office agence", size: 16, color: ENCRE2 })],
    })] }) },
    footers: { default: new Footer({ children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ children: [PageNumber.CURRENT], size: 17, color: ENCRE2 })],
    })] }) },
    children: enfants,
  }],
});

fs.writeFileSync(SORTIE, await Packer.toBuffer(doc));
console.log('écrit :', SORTIE, '|', compteurFigure, 'figures');
