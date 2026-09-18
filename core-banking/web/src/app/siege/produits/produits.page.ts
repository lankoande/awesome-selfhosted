import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
  CbToolbar, EtatOperation,
} from '../../ui';
import { CbChoixCompteGeneral } from '../composants/choix-compte-general';
import {
  actesSurVersion, ActeVersion, ATTENTE_VERSION, BAREME_INTERETS, Champ, champsDe, enSections,
  EnteteVersion, etatEffectif, FamilleProduit, LIBELLE_ACTE_VERSION, LIBELLE_ETAT_VERSION,
  LIBELLE_STATUT_VERSION, libelleSection, manquesDuParametrage, obstaclesALaFermeture,
  obstaclesAlEntete, Section, StatutVersion, Tranche, VersionComplete, VersionProduit,
} from '../modele/produits.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS: readonly StatutVersion[] = ['DRAFT', 'ACTIVE', 'WITHDRAWN'];

/**
 * Le paramétrage produit : ce qu'une banque vend, et à quelles conditions.
 *
 * <h2>Ce que cet écran ferme</h2>
 *
 * Le socle sait tout faire depuis longtemps ; c'était le seul domaine où ouvrir une banque
 * demandait d'écrire des produits en JSON et d'appeler l'API à la main. Trois manques du contrat
 * l'interdisaient : on ne pouvait ni lister les versions, ni relire l'une d'elles, ni connaître ce
 * qu'une famille exige. Ils sont comblés ; l'écran s'en sert.
 *
 * <h2>Le formulaire est construit par le contrat, pas écrit ici</h2>
 *
 * Ce qu'un compte courant exige, ce qu'un taux d'agios rend obligatoire, ce qu'une commission
 * ouvre : rien n'est écrit dans cet écran. Le socle sert sa propre déclaration
 * (`GET /products/families`), et la saisie s'y conforme — champ par champ, condition par
 * condition. Une règle ajoutée au socle apparaît ici sans qu'on touche à ce fichier ; deux copies
 * d'un même contrat auraient divergé.
 *
 * <h2>Trois règles que l'écran annonce sans les tenir</h2>
 *
 * **Un brouillon a le droit d'être incomplet** — c'est ce qui en fait un brouillon. L'écran
 * n'empêche pas de l'enregistrer : il annonce ce qui manquera à l'activation.
 *
 * **Une version en vigueur ne se retire pas.** Les comptes rattachés la résolvent à chaque date de
 * valeur traitée, y compris passée : la sortir de l'état actif ferait échouer leur arrêté. Sa
 * validité se **ferme**, et pas avant la date comptable de la banque.
 *
 * **Une version active sans terme interdit la suivante.** C'est la contrainte d'exclusion du
 * socle, et c'est pourquoi la fermeture est un acte à part entière plutôt qu'un détail.
 */
@Component({
  selector: 'cb-produits',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbChoixCompteGeneral, CbDateInput, CbField, CbInput, CbNotice,
            CbSection, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './produits.page.html',
  styleUrl: './produits.page.css',
})
export class Produits {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly STATUTS = STATUTS;
  protected readonly LIBELLE_STATUT_VERSION = LIBELLE_STATUT_VERSION;
  protected readonly LIBELLE_ETAT_VERSION = LIBELLE_ETAT_VERSION;
  protected readonly LIBELLE_ACTE_VERSION = LIBELLE_ACTE_VERSION;
  protected readonly ATTENTE_VERSION = ATTENTE_VERSION;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);

  protected readonly familles = signal<readonly FamilleProduit[]>([]);
  protected readonly versions = signal<readonly VersionProduit[]>([]);
  protected readonly filtre = signal<StatutVersion | null>(null);
  protected readonly choisie = signal<VersionComplete | null>(null);

  /** La rédaction : ouverte, elle prend la place du détail. */
  protected readonly saisie = signal(false);
  protected readonly code = signal('');
  protected readonly famille = signal('');
  protected readonly intitule = signal('');
  protected readonly devise = signal('');
  protected readonly debutLe = signal<string | null>(null);
  protected readonly finLe = signal<string | null>(null);
  protected readonly valeurs = signal<Record<string, string>>({});
  protected readonly tranches = signal<readonly Tranche[]>([]);

  /** La fermeture d'une version en vigueur. */
  protected readonly fermeture = signal(false);
  protected readonly fermerAu = signal<string | null>(null);

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeRediger = computed(() => this.droits.peut('PRODUCT_DRAFT'));
  protected readonly droitDActiver = computed(() => this.droits.peut('PRODUCT_ACTIVATE'));
  protected readonly droitDeFermer = computed(() => this.droits.peut('PRODUCT_CLOSE'));

  protected readonly activationADeux = computed(
    () => this.droits.annonceDeuxRegards('PRODUCT_ACTIVATE'));
  protected readonly fermetureADeux = computed(
    () => this.droits.annonceDeuxRegards('PRODUCT_CLOSE'));

  protected readonly dateComptable = computed(() => this.config.valeur().affichage.deviseParDefaut);

  /** La date comptable de la banque : c'est elle, et non le jour civil, qui borne une fermeture. */
  protected readonly journee = signal('');

  protected readonly visibles = computed(() => {
    const statut = this.filtre();
    return statut === null ? this.versions() : this.versions().filter((v) => v.status === statut);
  });

  protected readonly brouillons = computed(
    () => this.versions().filter((v) => v.status === 'DRAFT').length);

  protected readonly familleChoisie = computed(
    () => this.familles().find((f) => f.code === this.famille()) ?? null);

  /** Les champs que la famille demande, recalculés à chaque frappe. */
  protected readonly champs = computed<readonly Champ[]>(() => {
    const famille = this.familleChoisie();
    return famille === null ? [] : champsDe(famille, this.valeurs());
  });

  protected readonly sections = computed<readonly Section[]>(() => enSections(this.champs()));

  protected readonly baremes = computed(
    () => (this.tranches().length > 0 ? [BAREME_INTERETS] : []));

  protected readonly entete = computed<EnteteVersion>(() => ({
    code: this.code(),
    productType: this.famille(),
    label: this.intitule(),
    currency: this.devise(),
    validFrom: this.debutLe(),
    validTo: this.finLe(),
  }));

  protected readonly obstaclesIdentite = computed(() => obstaclesAlEntete(this.entete()));

  /** Ce qui manquera à l'activation. Annoncé, jamais bloquant : un brouillon a le droit d'attendre. */
  protected readonly manques = computed(() => {
    const famille = this.familleChoisie();
    return famille === null ? []
                            : manquesDuParametrage(famille, this.valeurs(), this.baremes());
  });

  protected readonly actes = computed<readonly ActeVersion[]>(() => {
    const version = this.choisie()?.header;
    if (version === undefined) {
      return [];
    }
    return actesSurVersion(version).filter((acte) => acte === 'FERMER'
      ? this.droitDeFermer()
      : acte === 'ACTIVER' ? this.droitDActiver() : this.droitDeRediger());
  });

  protected readonly obstaclesFermeture = computed(() => {
    const version = this.choisie()?.header;
    return version === undefined ? []
      : obstaclesALaFermeture(version, { validTo: this.fermerAu() }, this.journee());
  });

  /** Les barèmes d'une version relue, en liste : un gabarit ne trie pas un objet. */
  protected readonly baremesLus = computed(() => {
    const version = this.choisie();
    return version === null ? []
      : Object.entries(version.tiers).map(([cle, tranches]) => ({ cle, tranches }));
  });

  /** Les paramètres d'une version relue, rangés par section comme à la saisie. */
  protected readonly sectionsLues = computed<readonly Section[]>(() => {
    const version = this.choisie();
    if (version === null) {
      return [];
    }
    return enSections(Object.keys(version.parameters).map((nom) => ({
      nom, obligatoire: false, compte: false, raison: null,
    })));
  });

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [familles, versions, etablissement] = await Promise.all([
        this.siege.familles(entite),
        this.siege.versions(entite, null, null),
        this.siege.etablissement(entite),
      ]);
      this.familles.set(familles);
      this.versions.set(versions);
      this.journee.set(etablissement.businessDate);
      const ouverte = this.choisie();
      if (ouverte !== null) {
        const encore = versions.find((v) => v.id === ouverte.header.id);
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

  protected filtrer(statut: StatutVersion | null): void {
    this.filtre.set(statut);
  }

  protected async ouvrir(version: VersionProduit): Promise<void> {
    this.saisie.set(false);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    await this.relire(version.id);
  }

  /**
   * Relit la version ouverte, sans toucher à ce qui est affiché autour.
   *
   * Un acte relit derrière lui : effacer l'acquittement au passage ferait disparaître, dans la
   * même seconde, le message qui explique ce qu'on vient de faire.
   */
  private async relire(versionId: string): Promise<void> {
    try {
      this.choisie.set(await this.siege.version(this.config.legalEntityId(), versionId));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- rédaction

  protected ouvrirLaSaisie(): void {
    this.saisie.set(true);
    this.choisie.set(null);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    this.code.set('');
    this.famille.set('');
    this.intitule.set('');
    this.devise.set(this.config.valeur().affichage.deviseParDefaut);
    this.debutLe.set(null);
    this.finLe.set(null);
    this.valeurs.set({});
    this.tranches.set([]);
  }

  /**
   * Repartir d'une version existante.
   *
   * C'est le geste courant du paramétrage : une nouvelle version d'un produit change deux lignes
   * sur quarante. Tout ressaisir serait la meilleure façon d'introduire une faute là où il n'y en
   * avait pas — et la date d'entrée en vigueur, elle, ne se copie jamais.
   */
  protected repartirDe(version: VersionComplete): void {
    this.ouvrirLaSaisie();
    this.code.set(version.header.code);
    this.famille.set(version.header.productType);
    this.intitule.set(version.header.label);
    this.devise.set(version.header.currency);
    this.valeurs.set({ ...version.parameters });
    this.tranches.set([...(version.tiers[BAREME_INTERETS] ?? [])]);
  }

  protected fermerLaSaisie(): void {
    this.saisie.set(false);
  }

  protected changerFamille(code: string): void {
    this.famille.set(code);
    // Les paramètres d'une famille ne veulent rien dire dans une autre : le socle refuserait un
    // paramètre inconnu de la famille, et c'est le seul moyen de distinguer une valeur inutile
    // d'une valeur mal nommée.
    this.valeurs.set({});
    this.tranches.set([]);
  }

  protected saisir(nom: string, valeur: string): void {
    this.valeurs.update((valeurs) => ({ ...valeurs, [nom]: valeur }));
  }

  protected valeurDe(nom: string): string {
    return this.valeurs()[nom] ?? '';
  }

  protected ajouterTranche(): void {
    const dernieres = this.tranches();
    const depuis = dernieres.length === 0 ? '0' : (dernieres[dernieres.length - 1].to ?? '0');
    this.tranches.set([...dernieres, { from: depuis, to: null, annualRatePercent: '0' }]);
  }

  protected majTranche(index: number, champ: keyof Tranche, valeur: string): void {
    this.tranches.update((tranches) => tranches.map((tranche, i) =>
      (i === index ? { ...tranche, [champ]: champ === 'to' && valeur === '' ? null : valeur }
                   : tranche)));
  }

  protected retirerTranche(index: number): void {
    this.tranches.update((tranches) => tranches.filter((_, i) => i !== index));
  }

  protected async enregistrer(): Promise<void> {
    if (this.obstaclesIdentite().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const baremes: Record<string, readonly Tranche[]> = {};
      if (this.tranches().length > 0) {
        baremes[BAREME_INTERETS] = this.tranches();
      }
      await this.siege.redigerVersion(this.config.legalEntityId(), this.entete(), this.valeurs(),
                                      baremes, crypto.randomUUID());
      this.saisie.set(false);
      this.acquitte.set(`Version de ${this.code()} enregistrée en brouillon. Elle ne résout rien `
                        + "tant qu'un second ne l'a pas activée.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- actes

  protected demarrer(acte: ActeVersion): void {
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
    const version = this.choisie()?.header;
    if (version === undefined) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.activerVersion(this.config.legalEntityId(), version.id,
                                      crypto.randomUUID());
      this.acquitte.set(`Activation de ${version.code} demandée. Un second devra la valider — et `
                        + "le socle a d'abord confronté le paramétrage à sa famille.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async retirer(): Promise<void> {
    const version = this.choisie()?.header;
    if (version === undefined) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.retirerVersion(this.config.legalEntityId(), version.id);
      this.acquitte.set(`Brouillon ${version.code} retiré. Il reste lisible : ce qui a été saisi `
                        + "une fois explique pourquoi une version attendue n'existe pas.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async fermer(): Promise<void> {
    const version = this.choisie()?.header;
    const au = this.fermerAu();
    if (version === undefined || au === null || this.obstaclesFermeture().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.fermerVersion(this.config.legalEntityId(), version.id, au,
                                     crypto.randomUUID());
      this.fermeture.set(false);
      this.acquitte.set(`Fermeture de ${version.code} au ${au} demandée. Un second devra la `
                        + 'valider ; le code sera alors libre pour une version suivante.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- présentation

  /**
   * L'état affiché suit la validité, pas le seul statut.
   *
   * Le socle ne connaît que `ACTIVE` pour une version qui s'applique, une qui s'appliquera et une
   * qui s'est appliquée. Les confondre à l'écran ferait chercher longtemps pourquoi un produit
   * « en vigueur » ne s'ouvre plus.
   */
  protected etatDe(version: VersionProduit): EtatOperation {
    switch (etatEffectif(version, this.journee())) {
      case 'DRAFT':
        return 'brouillon';
      case 'ACTIVE':
        return 'approuve';
      case 'A_VENIR':
        return 'en-attente';
      case 'ECHUE':
        return 'expire';
      case 'SUSPENDED':
        return 'bloque';
      default:
        return 'contre-passe';
    }
  }

  protected libelleEtat(version: VersionProduit): string {
    return LIBELLE_ETAT_VERSION[etatEffectif(version, this.journee())];
  }

  protected libelleFamille(code: string): string {
    return this.familles().find((f) => f.code === code)?.label ?? code;
  }

  protected libelleSection(section: string): string {
    return libelleSection(section);
  }

  /** Une version en vigueur sans terme interdit la suivante : c'est ce que l'écran doit dire. */
  protected bloqueLaSuite(version: VersionProduit): boolean {
    return version.status === 'ACTIVE' && version.validTo === null;
  }

  /** Une version échue attend qu'on le lui dise : elle ne s'applique plus, elle reste au dossier. */
  protected estEchue(version: VersionProduit): boolean {
    return etatEffectif(version, this.journee()) === 'ECHUE';
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
