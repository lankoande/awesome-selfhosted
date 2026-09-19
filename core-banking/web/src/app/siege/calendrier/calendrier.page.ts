import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbTable, CbTabs,
  CbToolbar, Onglet,
} from '../../ui';
import {
  ConditionsDeBanque, Convention, DemandeFerie, DemandeHeureLimite, DemandeRegleDateValeur,
  libelleJour, LIBELLE_CONVENTION, LIBELLE_SENS_OPERATION, LIBELLE_UNITE, obstaclesALaRegle,
  obstaclesALHeureLimite, obstaclesAuFerie, phraseDeLaRegle, RegleDateValeur, SensOperation,
  UniteDecalage,
} from '../modele/reseau.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const SENS: readonly SensOperation[] = ['DEBIT', 'CREDIT'];
const UNITES: readonly UniteDecalage[] = ['BUSINESS_DAYS', 'CALENDAR_DAYS'];
const CONVENTIONS: readonly Convention[] = ['UNADJUSTED', 'FOLLOWING', 'MODIFIED_FOLLOWING',
                                            'PRECEDING', 'MODIFIED_PRECEDING'];

const VIDE: ConditionsDeBanque = {
  calendarCode: null, calendarLabel: null, coversFrom: null, coversTo: null, weekend: [],
  holidays: [], rules: [], cutoffs: [],
};

/**
 * Les conditions de banque : ce qui décide d'une date de valeur.
 *
 * <h2>Pourquoi les trois sont sur le même écran</h2>
 *
 * Une date de valeur est le produit d'une **règle**, d'une **heure limite** et d'un **calendrier**.
 * Un virement reçu à 15 h par la compensation, un jeudi, veille de férié : la règle dit +2 jours
 * ouvrés, l'heure limite de 14 h 30 le repousse au lendemain, le calendrier saute le férié et le
 * week-end. Les paramétrer sur trois écrans séparés, c'est garantir qu'on ne saura jamais
 * expliquer la date qu'un client conteste.
 *
 * <h2>Ce que l'écran ferme</h2>
 *
 * Aucune de ces trois choses ne se relisait. Celui qui paramétrait ajoutait une règle sans voir
 * celles qui existaient déjà — dont celle qu'il allait contredire.
 */
@Component({
  selector: 'cb-calendrier',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbTable,
            CbTabs, CbToolbar],
  templateUrl: './calendrier.page.html',
  styleUrl: './calendrier.page.css',
})
export class Calendrier {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly SENS = SENS;
  protected readonly UNITES = UNITES;
  protected readonly CONVENTIONS = CONVENTIONS;
  protected readonly LIBELLE_SENS_OPERATION = LIBELLE_SENS_OPERATION;
  protected readonly LIBELLE_UNITE = LIBELLE_UNITE;
  protected readonly LIBELLE_CONVENTION = LIBELLE_CONVENTION;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly conditions = signal<ConditionsDeBanque>(VIDE);
  protected readonly onglet = signal('feries');

  /** Ce qui est ouvert à la saisie : un férié, une règle, une heure limite — jamais deux. */
  protected readonly saisie = signal<'ferie' | 'regle' | 'heure' | null>(null);

  protected readonly dateFerie = signal<string | null>(null);
  protected readonly libelleFerie = signal('');

  protected readonly typeOperation = signal('');
  protected readonly canalRegle = signal('');
  protected readonly sens = signal<SensOperation>('CREDIT');
  protected readonly decalage = signal<number | null>(0);
  protected readonly unite = signal<UniteDecalage>('BUSINESS_DAYS');
  protected readonly convention = signal<Convention>('FOLLOWING');
  protected readonly regleDu = signal<string | null>(null);
  protected readonly regleAu = signal<string | null>(null);

  protected readonly canalHeure = signal('');
  protected readonly heure = signal('');
  protected readonly fermeLeCanal = signal(false);
  protected readonly heureDu = signal<string | null>(null);
  protected readonly heureAu = signal<string | null>(null);

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeParametrer = computed(() => this.droits.peut('CALENDAR_MANAGE'));
  protected readonly aDeux = computed(() => this.droits.annonceDeuxRegards('CALENDAR_MANAGE'));

  protected readonly onglets = computed<readonly Onglet[]>(() => [
    { id: 'feries', libelle: 'Jours fériés', compte: this.conditions().holidays.length },
    { id: 'regles', libelle: 'Dates de valeur', compte: this.conditions().rules.length },
    { id: 'heures', libelle: 'Heures limites', compte: this.conditions().cutoffs.length },
  ]);

  protected readonly weekend = computed(
    () => this.conditions().weekend.map((jour) => libelleJour(jour)).join(' et '));

  protected readonly demandeFerie = computed<DemandeFerie>(
    () => ({ date: this.dateFerie(), label: this.libelleFerie() }));

  protected readonly obstaclesFerie = computed(
    () => obstaclesAuFerie(this.demandeFerie(), this.conditions()));

  protected readonly demandeRegle = computed<DemandeRegleDateValeur>(() => ({
    operationType: this.typeOperation().trim().toUpperCase(),
    channel: this.canalRegle().trim().toUpperCase() || null,
    direction: this.sens(),
    offset: this.decalage(),
    unit: this.unite(),
    convention: this.convention(),
    validFrom: this.regleDu(),
    validTo: this.regleAu(),
  }));

  protected readonly obstaclesRegle = computed(() => obstaclesALaRegle(this.demandeRegle()));

  protected readonly demandeHeure = computed<DemandeHeureLimite>(() => ({
    channel: this.canalHeure().trim().toUpperCase() || null,
    cutoffTime: this.heure().trim(),
    closesChannel: this.fermeLeCanal(),
    validFrom: this.heureDu(),
    validTo: this.heureAu(),
  }));

  protected readonly obstaclesHeure = computed(
    () => obstaclesALHeureLimite(this.demandeHeure(), this.conditions().cutoffs));

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      this.conditions.set(await this.siege.conditions(this.config.legalEntityId()));
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected ouvrirLaSaisie(quoi: 'ferie' | 'regle' | 'heure'): void {
    this.saisie.set(quoi);
    this.acquitte.set(null);
    this.refus.set(null);
    if (quoi === 'ferie') {
      this.dateFerie.set(null);
      this.libelleFerie.set('');
    } else if (quoi === 'regle') {
      this.typeOperation.set('');
      this.canalRegle.set('');
      this.sens.set('CREDIT');
      this.decalage.set(0);
      this.unite.set('BUSINESS_DAYS');
      this.convention.set('FOLLOWING');
      this.regleDu.set(null);
      this.regleAu.set(null);
    } else {
      this.canalHeure.set('');
      this.heure.set('');
      this.fermeLeCanal.set(false);
      this.heureDu.set(null);
      this.heureAu.set(null);
    }
  }

  protected fermerLaSaisie(): void {
    this.saisie.set(null);
  }

  protected majDecalage(valeur: string): void {
    const lu = Number(valeur);
    this.decalage.set(valeur.trim() === '' || !Number.isFinite(lu) ? null : Math.trunc(lu));
  }

  protected async ajouterFerie(): Promise<void> {
    if (this.obstaclesFerie().length > 0) {
      return;
    }
    await this.envoyer(
      () => this.siege.ajouterFerie(this.config.legalEntityId(), this.demandeFerie(),
                                    crypto.randomUUID()),
      `Jour férié du ${this.dateFerie()} demandé. Il déplacera les échéances et les dates de `
      + "valeur qui tombent dessus, dès qu'un second l'aura validé.");
  }

  protected async ajouterRegle(): Promise<void> {
    if (this.obstaclesRegle().length > 0) {
      return;
    }
    await this.envoyer(
      () => this.siege.ajouterRegle(this.config.legalEntityId(), this.demandeRegle(),
                                    crypto.randomUUID()),
      `Règle de date de valeur sur ${this.demandeRegle().operationType} demandée. Elle déplace `
      + "des intérêts : un second doit la valider.");
  }

  protected async ajouterHeureLimite(): Promise<void> {
    if (this.obstaclesHeure().length > 0) {
      return;
    }
    await this.envoyer(
      () => this.siege.ajouterHeureLimite(this.config.legalEntityId(), this.demandeHeure(),
                                          crypto.randomUUID()),
      `Heure limite de ${this.heure()} demandée. Au-delà, l'opération prendra la valeur du jour `
      + "ouvré suivant.");
  }

  private async envoyer(acte: () => Promise<unknown>, message: string): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await acte();
      this.saisie.set(null);
      this.acquitte.set(message);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected phraseDeLaRegle(regle: RegleDateValeur): string {
    return phraseDeLaRegle(regle);
  }

  /** Une règle ou une heure limite dont la validité est close ne s'applique plus. */
  protected estClose(validTo: string | null): boolean {
    const jour = this.conditions().coversTo;
    return validTo !== null && jour !== null && validTo < jour;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
