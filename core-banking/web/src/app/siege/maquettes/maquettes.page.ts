import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge,
  CbTable, CbToolbar, EtatOperation,
} from '../../ui';
import {
  ActeMaquette, actesSurMaquette, ATTENTE_MAQUETTE, calculDuTotal, CompteNonAffecte,
  EnteteMaquette, EtatProduit, etatEffectif, LIBELLE_ACTE_MAQUETTE, LIBELLE_ETAT_MAQUETTE,
  LIBELLE_NATURE_COMPTE, LIBELLE_NATURE_ETAT, LIBELLE_NATURE_RUBRIQUE, LIBELLE_SENS,
  libelleNombreOrphelins, libelleNombreRubriques, Maquette, MaquetteComplete, NATURES_COMPTE,
  NATURES_ETAT, NatureEtat, NatureRubrique, obstaclesALaFermeture, obstaclesAlEntete,
  obstaclesAuxRegles, obstaclesAuxRubriques, OBJET_NATURE_ETAT, phraseDeLaRegle, RegleSaisie,
  regleProposee, reglesCouvertes, RubriqueSaisie, rubriquesDeDetail, Sens, STATUTS_MAQUETTE,
  StatutMaquette,
} from '../modele/maquettes.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const NATURES_RUBRIQUE: readonly NatureRubrique[] = ['DETAIL', 'TOTAL', 'PROFIT_OR_LOSS'];
const SENS: readonly Sens[] = ['DEBIT', 'CREDIT'];

/**
 * Les maquettes d'états financiers : bilan, compte de résultat, hors bilan.
 *
 * <h2>Ce que cet écran ferme</h2>
 *
 * Une maquette se rédigeait et s'activait ; elle ne se retrouvait pas, ne se fermait pas, et
 * surtout **ne s'éprouvait pas**. On activait à deux une maquette qui laisse quarante comptes
 * sans rubrique, et on l'apprenait en lisant un bilan faux — après l'avoir transmis au
 * superviseur.
 *
 * <h2>L'essai est le cœur de l'écran</h2>
 *
 * Il applique la maquette au journal réel et rend l'état qu'elle produirait, avec ses contrôles :
 * les comptes qu'aucune règle n'affecte, l'équilibre, le résultat antérieur non clos. Aucune
 * indulgence — un essai indulgent ne vaudrait rien, puisque c'est précisément ce qu'on cherche à
 * savoir avant d'activer.
 *
 * Et un compte resté sans rubrique n'est pas qu'un message : c'est un bouton. Il ouvre la règle
 * manquante, nature et sens du solde déjà repris. Une anomalie qu'on corrige d'un geste vaut
 * mieux qu'une anomalie qu'on recopie.
 *
 * <h2>L'ordre des règles est la règle</h2>
 *
 * La première règle qui reconnaît un compte l'emporte. L'écran le montre — les règles se
 * déplacent, elles ne se numérotent pas — et il signale celles qu'une précédente recouvre
 * entièrement : elles ne s'appliqueront jamais, et le socle ne peut pas le dire.
 */
@Component({
  selector: 'cb-maquettes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection,
            CbStateBadge, CbTable, CbToolbar],
  templateUrl: './maquettes.page.html',
  styleUrl: './maquettes.page.css',
})
export class Maquettes {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly NATURES_ETAT = NATURES_ETAT;
  protected readonly NATURES_RUBRIQUE = NATURES_RUBRIQUE;
  protected readonly NATURES_COMPTE = NATURES_COMPTE;
  protected readonly SENS = SENS;
  protected readonly STATUTS_MAQUETTE = STATUTS_MAQUETTE;
  protected readonly LIBELLE_NATURE_ETAT = LIBELLE_NATURE_ETAT;
  protected readonly LIBELLE_NATURE_RUBRIQUE = LIBELLE_NATURE_RUBRIQUE;
  protected readonly LIBELLE_NATURE_COMPTE = LIBELLE_NATURE_COMPTE;
  protected readonly LIBELLE_SENS = LIBELLE_SENS;
  protected readonly LIBELLE_ETAT_MAQUETTE = LIBELLE_ETAT_MAQUETTE;
  protected readonly LIBELLE_ACTE_MAQUETTE = LIBELLE_ACTE_MAQUETTE;
  protected readonly ATTENTE_MAQUETTE = ATTENTE_MAQUETTE;
  protected readonly OBJET_NATURE_ETAT = OBJET_NATURE_ETAT;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);

  protected readonly maquettes = signal<readonly Maquette[]>([]);
  protected readonly filtre = signal<StatutMaquette | null>(null);
  protected readonly filtreNature = signal<NatureEtat | null>(null);
  protected readonly choisie = signal<MaquetteComplete | null>(null);

  /** La date comptable de la banque : c'est elle, et non le jour civil, qui borne une fermeture. */
  protected readonly journee = signal('');

  /** L'essai : sa période, et l'état que le socle en tire. */
  protected readonly essai = signal<EtatProduit | null>(null);
  protected readonly essaiEnCours = signal(false);
  protected readonly essaiDu = signal<string | null>(null);
  protected readonly essaiAu = signal<string | null>(null);

  /** La rédaction. */
  protected readonly saisie = signal(false);
  protected readonly nature = signal<NatureEtat>('BALANCE_SHEET');
  protected readonly code = signal('');
  protected readonly intitule = signal('');
  protected readonly debutLe = signal<string | null>(null);
  protected readonly finLe = signal<string | null>(null);
  protected readonly rubriques = signal<readonly RubriqueSaisie[]>([]);
  protected readonly regles = signal<readonly RegleSaisie[]>([]);

  /** La fermeture d'une maquette en vigueur. */
  protected readonly fermeture = signal(false);
  protected readonly fermerAu = signal<string | null>(null);

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeRediger = computed(() => this.droits.peut('STATEMENT_LAYOUT_DRAFT'));
  protected readonly droitDActiver = computed(() => this.droits.peut('STATEMENT_LAYOUT_ACTIVATE'));
  protected readonly droitDeFermer = computed(() => this.droits.peut('STATEMENT_LAYOUT_CLOSE'));

  protected readonly activationADeux = computed(
    () => this.droits.annonceDeuxRegards('STATEMENT_LAYOUT_ACTIVATE'));
  protected readonly fermetureADeux = computed(
    () => this.droits.annonceDeuxRegards('STATEMENT_LAYOUT_CLOSE'));

  protected readonly visibles = computed(() => this.maquettes().filter((maquette) =>
    (this.filtre() === null || maquette.status === this.filtre())
    && (this.filtreNature() === null || maquette.kind === this.filtreNature())));

  protected readonly entete = computed<EnteteMaquette>(() => ({
    kind: this.nature(),
    code: this.code(),
    label: this.intitule(),
    validFrom: this.debutLe(),
    validTo: this.finLe(),
  }));

  protected readonly obstaclesIdentite = computed(
    () => obstaclesAlEntete(this.entete(), this.maquettes()));

  protected readonly obstaclesRubriques = computed(
    () => obstaclesAuxRubriques(this.rubriques(), this.nature()));

  protected readonly obstaclesRegles = computed(
    () => obstaclesAuxRegles(this.regles(), this.rubriques()));

  /** Les règles mortes : ni obstacle ni faute, mais un défaut qu'on ne verrait qu'au bilan. */
  protected readonly reglesMortes = computed(() => reglesCouvertes(this.regles()));

  protected readonly cibles = computed(() => rubriquesDeDetail(this.rubriques()));

  protected readonly peutEnregistrer = computed(
    () => this.obstaclesIdentite().length === 0 && this.obstaclesRubriques().length === 0
          && this.obstaclesRegles().length === 0);

  protected readonly actes = computed<readonly ActeMaquette[]>(() => {
    const maquette = this.enteteChoisie();
    if (maquette === null) {
      return [];
    }
    return actesSurMaquette(maquette).filter((acte) => acte === 'FERMER'
      ? this.droitDeFermer()
      : acte === 'ACTIVER' ? this.droitDActiver() : this.droitDeRediger());
  });

  protected readonly obstaclesFermeture = computed(() => {
    const maquette = this.enteteChoisie();
    return maquette === null ? []
      : obstaclesALaFermeture(maquette, { validTo: this.fermerAu() }, this.journee());
  });

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [maquettes, etablissement] = await Promise.all([
        this.siege.maquettes(entite, null, null),
        this.siege.etablissement(entite),
      ]);
      this.maquettes.set(maquettes);
      this.journee.set(etablissement.businessDate);
      if (this.essaiAu() === null) {
        this.essaiAu.set(etablissement.businessDate);
      }
      const ouverte = this.choisie();
      if (ouverte !== null) {
        const encore = maquettes.find((maquette) => maquette.id === ouverte.id);
        if (encore) {
          await this.relire(encore.id);
        } else {
          this.choisie.set(null);
        }
      }
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- lecture

  protected filtrer(statut: StatutMaquette | null): void {
    this.filtre.set(statut);
  }

  protected filtrerNature(nature: NatureEtat | null): void {
    this.filtreNature.set(nature);
  }

  protected async ouvrir(maquette: Maquette): Promise<void> {
    this.saisie.set(false);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    this.essai.set(null);
    await this.relire(maquette.id);
  }

  private async relire(layoutId: string): Promise<void> {
    try {
      this.choisie.set(await this.siege.maquette(this.config.legalEntityId(), layoutId));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /**
   * L'en-tête de la maquette ouverte, tel que la liste le porte.
   *
   * Le détail servi ne rend pas le statut courant après un acte : le prendre dans la liste, qui
   * vient d'être relue, évite d'afficher « brouillon » sur une maquette qu'on vient d'activer.
   */
  protected enteteChoisie(): Maquette | null {
    const ouverte = this.choisie();
    return ouverte === null
      ? null
      : this.maquettes().find((maquette) => maquette.id === ouverte.id) ?? null;
  }

  protected libelleRegle(index: number): string {
    const maquette = this.choisie();
    if (maquette === null) {
      return '';
    }
    const regle = maquette.rules[index];
    return regle === undefined ? '' : phraseDeLaRegle(regle, maquette.lines);
  }

  protected calculDuTotal(code: string): string {
    const maquette = this.choisie();
    if (maquette === null) {
      return '';
    }
    const rubrique = maquette.lines.find((candidate) => candidate.code === code);
    return rubrique === undefined ? '' : calculDuTotal(rubrique, maquette.lines);
  }

  // ---------------------------------------------------------------- l'essai

  protected async essayer(): Promise<void> {
    const maquette = this.choisie();
    if (maquette === null) {
      return;
    }
    this.essaiEnCours.set(true);
    this.refus.set(null);
    try {
      this.essai.set(await this.siege.essayerMaquette(
        this.config.legalEntityId(), maquette.id, this.essaiDu(), this.essaiAu()));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.essaiEnCours.set(false);
    }
  }

  /**
   * Un compte resté sans rubrique ouvre la règle qui lui manque.
   *
   * La rédaction s'ouvre sur la maquette essayée, et la règle proposée reprend la nature du
   * compte et le sens de son solde : un compte de client débiteur est une créance, créditeur un
   * dépôt — ce n'est jamais la même rubrique.
   */
  protected corriger(compte: CompteNonAffecte): void {
    const maquette = this.choisie();
    if (maquette === null) {
      return;
    }
    if (!this.saisie()) {
      this.repartirDe(maquette);
    }
    const premiere = this.cibles()[0];
    this.regles.update((regles) => [...regles, regleProposee(compte, premiere?.code ?? '')]);
  }

  // ---------------------------------------------------------------- rédaction

  protected ouvrirLaSaisie(): void {
    this.saisie.set(true);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    this.code.set('');
    this.intitule.set('');
    this.debutLe.set(null);
    this.finLe.set(null);
    this.rubriques.set([]);
    this.regles.set([]);
  }

  /**
   * Repartir d'une maquette existante.
   *
   * Une refonte de bilan change trois rubriques sur quarante. Tout ressaisir serait la meilleure
   * façon d'introduire une faute là où il n'y en avait pas — et la date d'entrée en vigueur, elle,
   * ne se copie jamais.
   */
  protected repartirDe(maquette: MaquetteComplete): void {
    this.ouvrirLaSaisie();
    this.nature.set(maquette.kind);
    this.code.set(maquette.code);
    this.intitule.set(maquette.label);
    this.rubriques.set(maquette.lines.map((rubrique) => ({
      code: rubrique.code, label: rubrique.label, level: rubrique.level, kind: rubrique.kind,
      side: rubrique.side, plus: [...rubrique.plus], minus: [...rubrique.minus],
    })));
    this.regles.set(maquette.rules.map((regle) => ({
      lineCode: regle.lineCode,
      accountKind: regle.accountKind ?? '',
      codePrefix: regle.codePrefix ?? '',
      balanceSide: regle.balanceSide ?? '',
    })));
  }

  protected fermerLaSaisie(): void {
    this.saisie.set(false);
  }

  protected changerNature(nature: string): void {
    this.nature.set(nature as NatureEtat);
  }

  protected ajouterRubrique(): void {
    this.rubriques.update((rubriques) => [...rubriques, {
      code: '', label: '', level: 1, kind: 'DETAIL', side: 'DEBIT', plus: [], minus: [],
    }]);
  }

  protected majRubrique(index: number, champ: 'code' | 'label' | 'kind' | 'side' | 'level',
                        valeur: string): void {
    this.rubriques.update((rubriques) => rubriques.map((rubrique, i) => {
      if (i !== index) {
        return rubrique;
      }
      if (champ === 'level') {
        return { ...rubrique, level: Math.max(0, Number(valeur) || 0) };
      }
      return { ...rubrique, [champ]: valeur } as RubriqueSaisie;
    }));
  }

  protected majTotal(index: number, cote: 'plus' | 'minus', valeur: string): void {
    const codes = valeur.split(',').map((code) => code.trim()).filter((code) => code.length > 0);
    this.rubriques.update((rubriques) => rubriques.map((rubrique, i) =>
      (i === index ? { ...rubrique, [cote]: codes } : rubrique)));
  }

  protected retirerRubrique(index: number): void {
    this.rubriques.update((rubriques) => rubriques.filter((_, i) => i !== index));
  }

  protected ajouterRegle(): void {
    this.regles.update((regles) => [...regles, {
      lineCode: this.cibles()[0]?.code ?? '', accountKind: '', codePrefix: '', balanceSide: '',
    }]);
  }

  protected majRegle(index: number, champ: keyof RegleSaisie, valeur: string): void {
    this.regles.update((regles) => regles.map((regle, i) =>
      (i === index ? { ...regle, [champ]: valeur } as RegleSaisie : regle)));
  }

  protected retirerRegle(index: number): void {
    this.regles.update((regles) => regles.filter((_, i) => i !== index));
  }

  /**
   * Déplacer une règle, c'est changer sa précédence.
   *
   * Le rang ne se saisit pas : il est l'ordre de la liste. Laisser saisir un numéro inviterait à
   * deux règles de même rang, ou à un trou — et c'est précisément l'ordre qui décide ici.
   */
  protected deplacerRegle(index: number, sens: -1 | 1): void {
    this.regles.update((regles) => {
      const cible = index + sens;
      if (cible < 0 || cible >= regles.length) {
        return regles;
      }
      const copie = [...regles];
      const [retiree] = copie.splice(index, 1);
      copie.splice(cible, 0, retiree!);
      return copie;
    });
  }

  protected phraseSaisie(regle: RegleSaisie): string {
    return phraseDeLaRegle({
      ordinal: 0,
      lineCode: regle.lineCode,
      accountKind: regle.accountKind || null,
      codePrefix: regle.codePrefix.trim() || null,
      balanceSide: regle.balanceSide || null,
    }, this.rubriques().map((rubrique, index) => ({ ...rubrique, ordinal: index + 1 })));
  }

  protected estMorte(index: number): boolean {
    return this.reglesMortes().includes(index);
  }

  // ---------------------------------------------------------------- actes

  protected async enregistrer(): Promise<void> {
    if (!this.peutEnregistrer()) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.redigerMaquette(this.config.legalEntityId(), this.entete(),
                                       this.rubriques(), this.regles(), crypto.randomUUID());
      this.saisie.set(false);
      this.acquitte.set(`Maquette ${this.code()} enregistrée en brouillon. Essayez-la sur le `
                        + "journal avant de l'activer : c'est là que se voient les comptes "
                        + 'laissés sans rubrique.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected demarrer(acte: ActeMaquette): void {
    if (acte === 'FERMER') {
      this.fermeture.set(true);
      this.fermerAu.set(this.journee());
      return;
    }
    void (acte === 'ACTIVER' ? this.activer() : this.retirer());
  }

  protected abandonnerLaFermeture(): void {
    this.fermeture.set(false);
  }

  protected async activer(): Promise<void> {
    const maquette = this.enteteChoisie();
    if (maquette === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.activerMaquette(this.config.legalEntityId(), maquette.id,
                                       crypto.randomUUID());
      this.acquitte.set(`Activation de ${maquette.code} demandée. Un second devra la valider — et `
                        + 'ce ne peut pas être son rédacteur.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async retirer(): Promise<void> {
    const maquette = this.enteteChoisie();
    if (maquette === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.retirerMaquette(this.config.legalEntityId(), maquette.id);
      this.acquitte.set(`Brouillon ${maquette.code} retiré. Il reste lisible : ce qui a été écrit `
                        + "une fois explique pourquoi un état ne se présente pas comme on l'avait "
                        + 'prévu.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async fermer(): Promise<void> {
    const maquette = this.enteteChoisie();
    const au = this.fermerAu();
    if (maquette === null || au === null || this.obstaclesFermeture().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.fermerMaquette(this.config.legalEntityId(), maquette.id, { validTo: au },
                                      crypto.randomUUID());
      this.fermeture.set(false);
      this.acquitte.set(`Fermeture de ${maquette.code} au ${au} demandée. Un second devra la `
                        + 'valider ; la place sera alors libre pour la maquette suivante.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- présentation

  protected etatDe(maquette: Maquette): EtatOperation {
    switch (etatEffectif(maquette, this.journee())) {
      case 'DRAFT':
        return 'brouillon';
      case 'ACTIVE':
        return 'approuve';
      case 'A_VENIR':
        return 'en-attente';
      case 'ECHUE':
        return 'expire';
      default:
        return 'contre-passe';
    }
  }

  protected libelleEtat(maquette: Maquette): string {
    return LIBELLE_ETAT_MAQUETTE[etatEffectif(maquette, this.journee())];
  }

  protected nombreRubriques(nombre: number): string {
    return libelleNombreRubriques(nombre);
  }

  protected nombreOrphelins(nombre: number): string {
    return libelleNombreOrphelins(nombre);
  }

  /** Une maquette en vigueur sans terme interdit la suivante : c'est ce que l'écran doit dire. */
  protected bloqueLaSuite(maquette: Maquette): boolean {
    return maquette.status === 'ACTIVE' && maquette.validTo === null;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
