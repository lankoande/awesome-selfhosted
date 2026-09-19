import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
  CbTabs, CbToolbar, EtatOperation, Onglet,
} from '../../ui';
import {
  ActeSchema, actesSurSchema, ATTENTE_SCHEMA, EnteteSchema, Essai, etatEffectif, EvenementSocle,
  LIBELLE_ACTE_SCHEMA, LIBELLE_ETAT_SCHEMA, LIBELLE_ORIGINE, LIBELLE_STATUT_SCHEMA, libelleEcart,
  libelleNombreEvenements, libelleRefus, LigneSaisie, ModuleSocle, obstaclesALaFermeture,
  obstaclesAlEntete,
  obstaclesAlEvenement, obstaclesALaLigne, parametrables, parModule, partiesDeLaLigne,
  PhraseLigne, SchemaComplet,
  SchemaComptable, StatutSchema,
} from '../modele/schemas.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS: readonly StatutSchema[] = ['DRAFT', 'ACTIVE', 'WITHDRAWN'];

const ONGLETS: readonly Onglet[] = [
  { id: 'socle', libelle: 'Ce que le socle impute' },
  { id: 'parametres', libelle: 'Schémas paramétrés' },
];

/**
 * Les schémas comptables : la traduction de chaque événement en lignes d'écriture.
 *
 * <h2>Le piège que cet écran ferme</h2>
 *
 * Le socle laissait rédiger et activer un schéma sous n'importe quel code, pour n'importe quel
 * événement. Or un seul est aujourd'hui résolu depuis le paramétrage : celui de la commission.
 * Tout le reste — guichet, virements, moyens de paiement, crédit — est imputé par le socle
 * lui-même. Un schéma rédigé pour un déblocage de crédit passait la validation, s'activait à deux,
 * et **n'était lu par personne** : celui qui l'avait écrit croyait l'imputation changée, et l'écart
 * se serait vu au premier rapprochement, des mois plus tard.
 *
 * L'écran le dit, et le socle le refuse. Le premier onglet montre ce que la banque impute
 * réellement — un comptable a le droit de le savoir sans lire du Java —, et marque ce qui se
 * remplace. Le second ne propose à la rédaction que ce qui sera effectivement résolu.
 *
 * <h2>Le poste ne lit aucune expression</h2>
 *
 * `round(net, 0) + tax` n'est jamais interprété ici. L'essai part au socle et revient avec les
 * variables attendues, les valeurs dérivées et les lignes produites — refus compris. Deux
 * analyseurs à tenir en accord auraient fini par diverger, et l'écran aurait montré une écriture
 * que la production ne produit pas. Sur de la comptabilité, ce serait pire qu'inutile.
 *
 * <h2>Fermer, et non retirer</h2>
 *
 * Un schéma en vigueur ne se retire pas : les imputations se résolvent à la date de valeur
 * traitée, y compris passée, et seul un schéma actif se résout. Sa validité se **ferme** — et
 * c'est aussi la seule façon d'en activer un suivant sous le même code.
 */
@Component({
  selector: 'cb-schemas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection,
            CbStateBadge, CbTable, CbTabs, CbToolbar, NgTemplateOutlet],
  templateUrl: './schemas.page.html',
  styleUrl: './schemas.page.css',
})
export class Schemas {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly ONGLETS = ONGLETS;
  protected readonly STATUTS = STATUTS;
  protected readonly LIBELLE_STATUT_SCHEMA = LIBELLE_STATUT_SCHEMA;
  protected readonly LIBELLE_ETAT_SCHEMA = LIBELLE_ETAT_SCHEMA;
  protected readonly LIBELLE_ACTE_SCHEMA = LIBELLE_ACTE_SCHEMA;
  protected readonly LIBELLE_ORIGINE = LIBELLE_ORIGINE;
  protected readonly ATTENTE_SCHEMA = ATTENTE_SCHEMA;

  protected readonly onglet = signal('socle');
  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);

  protected readonly catalogue = signal<readonly EvenementSocle[]>([]);
  protected readonly schemas = signal<readonly SchemaComptable[]>([]);
  protected readonly filtre = signal<StatutSchema | null>(null);
  protected readonly choisi = signal<SchemaComplet | null>(null);
  protected readonly deplie = signal<string | null>(null);

  /** La date comptable de la banque : c'est elle, et non le jour civil, qui borne une fermeture. */
  protected readonly journee = signal('');

  /** La rédaction : ouverte, elle prend la place du détail. */
  protected readonly saisie = signal(false);
  protected readonly code = signal('');
  protected readonly intitule = signal('');
  protected readonly devise = signal('');
  protected readonly evenement = signal('');
  protected readonly debutLe = signal<string | null>(null);
  protected readonly finLe = signal<string | null>(null);
  protected readonly derivations = signal<readonly (readonly [string, string])[]>([]);
  protected readonly lignes = signal<readonly LigneSaisie[]>([]);

  /** L'essai : son jeu de valeurs, et ce que le socle en fait. */
  protected readonly valeurs = signal<Record<string, string>>({});
  protected readonly essai = signal<Essai | null>(null);
  protected readonly essaiEnCours = signal(false);
  protected readonly essaiDuSocle = signal<string | null>(null);

  /** La fermeture d'un schéma en vigueur. */
  protected readonly fermeture = signal(false);
  protected readonly fermerAu = signal<string | null>(null);

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeRediger = computed(() => this.droits.peut('ACCOUNTING_SCHEMA_DRAFT'));
  protected readonly droitDActiver = computed(() => this.droits.peut('ACCOUNTING_SCHEMA_ACTIVATE'));
  protected readonly droitDeFermer = computed(() => this.droits.peut('ACCOUNTING_SCHEMA_CLOSE'));

  protected readonly activationADeux = computed(
    () => this.droits.annonceDeuxRegards('ACCOUNTING_SCHEMA_ACTIVATE'));
  protected readonly fermetureADeux = computed(
    () => this.droits.annonceDeuxRegards('ACCOUNTING_SCHEMA_CLOSE'));

  protected readonly modules = computed<readonly ModuleSocle[]>(
    () => parModule(this.catalogue()));

  /** Ce qu'un schéma peut réellement remplacer : la liste vient du socle, jamais d'ici. */
  protected readonly remplacables = computed<readonly EvenementSocle[]>(
    () => parametrables(this.catalogue()));

  protected readonly visibles = computed(() => {
    const statut = this.filtre();
    return statut === null ? this.schemas() : this.schemas().filter((s) => s.status === statut);
  });

  protected readonly brouillons = computed(
    () => this.schemas().filter((s) => s.status === 'DRAFT').length);

  protected readonly entete = computed<EnteteSchema>(() => ({
    code: this.code(),
    label: this.intitule(),
    currency: this.devise(),
    eventType: this.evenement(),
    validFrom: this.debutLe(),
    validTo: this.finLe(),
  }));

  protected readonly obstaclesIdentite = computed(
    () => obstaclesAlEntete(this.entete(), this.catalogue()));

  protected readonly obstaclesLignes = computed(() => {
    const obstacles = [...obstaclesAlEvenement(this.lignes())];
    this.lignes().forEach((ligne, index) => {
      for (const obstacle of obstaclesALaLigne(ligne)) {
        obstacles.push(`Ligne ${index + 1} — ${obstacle}`);
      }
    });
    return obstacles;
  });

  protected readonly peutEnregistrer = computed(
    () => this.obstaclesIdentite().length === 0 && this.obstaclesLignes().length === 0);

  protected readonly actes = computed<readonly ActeSchema[]>(() => {
    const schema = this.choisi()?.header;
    if (schema === undefined) {
      return [];
    }
    return actesSurSchema(schema).filter((acte) => acte === 'FERMER'
      ? this.droitDeFermer()
      : acte === 'ACTIVER' ? this.droitDActiver() : this.droitDeRediger());
  });

  protected readonly obstaclesFermeture = computed(() => {
    const schema = this.choisi()?.header;
    return schema === undefined ? []
      : obstaclesALaFermeture(schema, { validTo: this.fermerAu() }, this.journee());
  });

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [catalogue, schemas, etablissement] = await Promise.all([
        this.siege.evenementsDuSocle(entite, null),
        this.siege.schemas(entite, null, null),
        this.siege.etablissement(entite),
      ]);
      this.catalogue.set(catalogue);
      this.schemas.set(schemas);
      this.journee.set(etablissement.businessDate);
      if (this.devise() === '') {
        this.devise.set(this.config.valeur().affichage.deviseParDefaut);
      }
      const ouvert = this.choisi();
      if (ouvert !== null) {
        const encore = schemas.find((s) => s.id === ouvert.header.id);
        if (encore) {
          await this.relire(encore.id);
        } else {
          this.choisi.set(null);
        }
      }
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- le catalogue du socle

  protected deplier(eventType: string): void {
    this.deplie.update((ouvert) => (ouvert === eventType ? null : eventType));
  }

  protected phrase(ligne: { account: string; direction: string; amount: string;
                           condition: string | null }): PhraseLigne {
    return partiesDeLaLigne({ ...ligne, label: null });
  }

  /**
   * Essaie un schéma du socle sur un cas.
   *
   * C'est la seule façon de répondre à « qu'est-ce que la banque impute quand un client retire
   * 5 000 francs ? » sans ouvrir le code — et c'est une question qu'un auditeur pose.
   */
  protected async essayerLeSocle(evenement: EvenementSocle): Promise<void> {
    this.essaiDuSocle.set(evenement.eventType);
    this.essai.set(null);
    this.valeurs.set(Object.fromEntries(evenement.variables.map((nom) => [nom, '0'])));
    await this.lancerEssai(evenement.eventType, true);
  }

  // ---------------------------------------------------------------- lecture d'un schéma

  protected filtrer(statut: StatutSchema | null): void {
    this.filtre.set(statut);
  }

  protected async ouvrir(schema: SchemaComptable): Promise<void> {
    this.saisie.set(false);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    this.essai.set(null);
    this.essaiDuSocle.set(null);
    await this.relire(schema.id);
  }

  /**
   * Relit le schéma ouvert, sans toucher à ce qui est affiché autour.
   *
   * Un acte relit derrière lui : effacer l'acquittement au passage ferait disparaître, dans la
   * même seconde, le message qui explique ce qu'on vient de faire.
   */
  private async relire(schemaId: string): Promise<void> {
    try {
      this.choisi.set(await this.siege.schema(this.config.legalEntityId(), schemaId));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- rédaction

  protected ouvrirLaSaisie(): void {
    this.saisie.set(true);
    this.choisi.set(null);
    this.fermeture.set(false);
    this.acquitte.set(null);
    this.refus.set(null);
    this.essai.set(null);
    this.essaiDuSocle.set(null);
    this.code.set('');
    this.intitule.set('');
    this.devise.set(this.config.valeur().affichage.deviseParDefaut);
    this.evenement.set(this.remplacables()[0]?.eventType ?? '');
    this.debutLe.set(null);
    this.finLe.set(null);
    this.derivations.set([]);
    this.lignes.set([]);
    this.valeurs.set({});
    this.partirDuSocle();
  }

  /**
   * Repartir du schéma du socle plutôt que d'une page blanche.
   *
   * Un schéma de commission qu'on remplace en change une ligne sur trois : le réécrire entier
   * serait la meilleure façon d'introduire une faute là où il n'y en avait pas. Et il donne, au
   * passage, la forme que le socle attend.
   */
  protected partirDuSocle(): void {
    const modele = this.catalogue().find((e) => e.eventType === this.evenement());
    if (modele === undefined) {
      return;
    }
    this.derivations.set(modele.derivations.map((d) => [d.name, d.expression] as const));
    this.lignes.set(modele.lines.map((ligne) => ({
      account: ligne.account, direction: ligne.direction, amount: ligne.amount,
      condition: ligne.condition ?? '', label: ligne.label ?? '',
    })));
    this.valeurs.set(Object.fromEntries(modele.variables.map((nom) => [nom, '0'])));
  }

  protected repartirDe(schema: SchemaComplet): void {
    const evenement = schema.events[0];
    this.ouvrirLaSaisie();
    this.code.set(schema.header.code);
    this.intitule.set(schema.header.label);
    this.devise.set(schema.header.currency);
    if (evenement) {
      this.evenement.set(evenement.eventType);
      this.derivations.set(evenement.derivations.map((d) => [d.name, d.expression] as const));
      this.lignes.set(evenement.lines.map((ligne) => ({
        account: ligne.account, direction: ligne.direction, amount: ligne.amount,
        condition: ligne.condition ?? '', label: ligne.label ?? '',
      })));
    }
  }

  protected fermerLaSaisie(): void {
    this.saisie.set(false);
  }

  protected changerEvenement(eventType: string): void {
    this.evenement.set(eventType);
    this.essai.set(null);
    this.partirDuSocle();
  }

  protected ajouterDerivation(): void {
    this.derivations.update((derivations) => [...derivations, ['', '']]);
  }

  protected majDerivation(index: number, position: 0 | 1, valeur: string): void {
    this.derivations.update((derivations) => derivations.map((derivation, i) => {
      if (i !== index) {
        return derivation;
      }
      return position === 0 ? [valeur, derivation[1]] as const : [derivation[0], valeur] as const;
    }));
  }

  protected retirerDerivation(index: number): void {
    this.derivations.update((derivations) => derivations.filter((_, i) => i !== index));
  }

  protected ajouterLigne(): void {
    this.lignes.update((lignes) => [...lignes, {
      account: 'CONTRACT', direction: 'DEBIT', amount: '', condition: '', label: '',
    }]);
  }

  protected majLigne(index: number, champ: keyof LigneSaisie, valeur: string): void {
    this.lignes.update((lignes) => lignes.map((ligne, i) =>
      (i === index ? { ...ligne, [champ]: valeur } : ligne)));
  }

  protected retirerLigne(index: number): void {
    this.lignes.update((lignes) => lignes.filter((_, i) => i !== index));
  }

  // ---------------------------------------------------------------- l'essai

  protected saisirValeur(nom: string, valeur: string): void {
    this.valeurs.update((valeurs) => ({ ...valeurs, [nom]: valeur }));
  }

  protected valeurDe(nom: string): string {
    return this.valeurs()[nom] ?? '';
  }

  protected async essayer(): Promise<void> {
    await this.lancerEssai(this.evenement(), false);
  }

  /**
   * Relance l'essai en cours, du bon côté.
   *
   * Le panneau d'essai est le même pour le schéma du socle et pour celui qu'on rédige ; c'est
   * `essaiDuSocle` qui dit lequel est ouvert. Le déduire du catalogue relancerait toujours le
   * premier événement de la liste, quelle que soit la ligne dépliée.
   */
  protected async relancerEssai(): Promise<void> {
    const duSocle = this.essaiDuSocle();
    await (duSocle === null ? this.lancerEssai(this.evenement(), false)
                            : this.lancerEssai(duSocle, true));
  }

  /**
   * Lance l'essai et **aligne le jeu de valeurs sur ce que le socle réclame**.
   *
   * Les grandeurs attendues changent dès qu'on touche une expression : les déduire ici
   * supposerait de lire le langage du socle. L'essai les rend, et c'est de là que viennent les
   * champs à remplir — ni avant, ni autrement.
   */
  private async lancerEssai(evenement: string, duSocle: boolean): Promise<void> {
    if (!evenement) {
      return;
    }
    this.essaiEnCours.set(true);
    this.refus.set(null);
    try {
      const entite = this.config.legalEntityId();
      const rendu = duSocle
        ? await this.siege.essayerLeSocle(entite, evenement, this.devise(), this.valeurs())
        : await this.siege.essayer(entite, evenement, this.devise(), this.lignes(),
                                   this.derivations(), this.valeurs());
      this.essai.set(rendu);
      this.valeurs.update((valeurs) => {
        const alignees: Record<string, string> = {};
        for (const nom of rendu.variables) {
          alignees[nom] = valeurs[nom] ?? '0';
        }
        return alignees;
      });
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.essaiEnCours.set(false);
    }
  }

  // ---------------------------------------------------------------- actes

  protected async enregistrer(): Promise<void> {
    if (!this.peutEnregistrer()) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.redigerSchema(this.config.legalEntityId(), this.entete(), this.lignes(),
                                     this.derivations(), crypto.randomUUID());
      this.saisie.set(false);
      this.onglet.set('parametres');
      this.acquitte.set(`Schéma ${this.code()} enregistré en brouillon. Le socle l'a éprouvé par `
                        + "tirage : un schéma déséquilibré n'entre jamais en base.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected demarrer(acte: ActeSchema): void {
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
    const schema = this.choisi()?.header;
    if (schema === undefined) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.activerSchema(this.config.legalEntityId(), schema.id, crypto.randomUUID());
      this.acquitte.set(`Activation de ${schema.code} demandée. Un second devra la valider — et ce `
                        + 'ne peut pas être son rédacteur.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async retirer(): Promise<void> {
    const schema = this.choisi()?.header;
    if (schema === undefined) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.retirerSchema(this.config.legalEntityId(), schema.id);
      this.acquitte.set(`Brouillon ${schema.code} retiré. Il reste lisible : ce qui a été écrit `
                        + "une fois explique pourquoi un schéma attendu n'existe pas.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async fermer(): Promise<void> {
    const schema = this.choisi()?.header;
    const au = this.fermerAu();
    if (schema === undefined || au === null || this.obstaclesFermeture().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.fermerSchema(this.config.legalEntityId(), schema.id, { validTo: au },
                                    crypto.randomUUID());
      this.fermeture.set(false);
      this.acquitte.set(`Fermeture de ${schema.code} au ${au} demandée. Un second devra la `
                        + 'valider ; le code sera alors libre pour un schéma suivant.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- présentation

  protected etatDe(schema: SchemaComptable): EtatOperation {
    switch (etatEffectif(schema, this.journee())) {
      case 'DRAFT':
        return 'brouillon';
      case 'ACTIVE':
        return 'approuve';
      case 'A_VENIR':
        return 'en-attente';
      case 'ECHU':
        return 'expire';
      case 'SUSPENDED':
        return 'bloque';
      default:
        return 'contre-passe';
    }
  }

  protected libelleEtat(schema: SchemaComptable): string {
    return LIBELLE_ETAT_SCHEMA[etatEffectif(schema, this.journee())];
  }

  protected nombreEvenements(nombre: number): string {
    return libelleNombreEvenements(nombre);
  }

  protected libelleEcart(code: string | null): string {
    return libelleEcart(code);
  }

  protected libelleRefus(): string {
    return libelleRefus(this.essai()?.rejection ?? null);
  }

  /** Un schéma en vigueur sans terme interdit le suivant : c'est ce que l'écran doit dire. */
  protected bloqueLaSuite(schema: SchemaComptable): boolean {
    return schema.status === 'ACTIVE' && schema.validTo === null;
  }

  protected libelleEvenement(eventType: string): string {
    return this.catalogue().find((e) => e.eventType === eventType)?.label ?? eventType;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
