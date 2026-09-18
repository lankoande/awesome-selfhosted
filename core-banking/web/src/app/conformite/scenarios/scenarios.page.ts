import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
  CbSection, CbTable, CbToolbar,
} from '../../ui';
import { CONFORMITE } from '../conformite.port';
import {
  EFFET_METHODE, LIBELLE_METHODE, MethodeScenario, PARAMETRES_REQUIS, Scenario,
  obstaclesAuScenario,
} from '../modele/conformite.modele';

const METHODES: readonly MethodeScenario[] = [
  'CASH_THRESHOLD', 'STRUCTURING', 'ATYPICAL_ACTIVITY', 'DORMANT_REACTIVATION',
];

/**
 * Les scénarios de surveillance : ce que la banque regarde.
 *
 * **La méthode est du code, les seuils sont du paramétrage.** C'est la seule
 * façon de tenir un dispositif vivant : les seuils et les fenêtres changent
 * d'une circulaire à l'autre, et une conformité qui attend la prochaine version
 * n'est pas une conformité. Ajouter une méthode, en revanche, est une livraison
 * — elle change ce que la banque *sait* regarder.
 *
 * **Un scénario se déclare à deux**, et pas pour la raison habituelle : il ne
 * produit aucun montant sur aucun compte. Il décide de ce que la banque
 * regarde, et surtout de ce qu'elle ne regardera pas — un seuil posé trop haut
 * par une seule main éteint la surveillance sans que rien ne le signale.
 *
 * **Le formulaire ne demande que ce que la méthode exige.** Poser un seuil sur
 * une méthode qui n'en admet pas ferait un scénario que le socle refuse à
 * l'approbation, donc un valideur dérangé pour rien.
 */
@Component({
  selector: 'cb-scenarios',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
    CbSection, CbTable, CbToolbar,
  ],
  templateUrl: './scenarios.page.html',
  styleUrl: './scenarios.page.css',
})
export class Scenarios {
  private readonly conformite = inject(CONFORMITE);
  private readonly config = inject(AppConfig);

  protected readonly METHODES = METHODES;
  protected readonly LIBELLE_METHODE = LIBELLE_METHODE;
  protected readonly EFFET_METHODE = EFFET_METHODE;

  protected readonly scenarios = signal<readonly Scenario[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly envoi = signal(false);
  protected readonly volet = signal(false);
  protected readonly enAttente = signal<string | null>(null);
  /** Faux tant que rien n'a été saisi : on ne gronde pas un formulaire vierge. */
  protected readonly commence = signal(false);

  protected readonly code = signal('');
  protected readonly libelle = signal('');
  protected readonly methode = signal<MethodeScenario>('CASH_THRESHOLD');
  protected readonly seuil = signal<number | null>(null);
  protected readonly fenetre = signal<number | null>(null);
  protected readonly nombreMinimal = signal<number | null>(null);
  protected readonly facteur = signal('');
  protected readonly dateDEffet = signal<string | null>(null);
  protected readonly dateDeFin = signal<string | null>(null);

  private cle = crypto.randomUUID();

  /** Ce que la méthode choisie exige. Le reste du formulaire ne s'affiche pas. */
  protected readonly requis = computed(() => PARAMETRES_REQUIS[this.methode()]);

  protected readonly obstacles = computed(() => obstaclesAuScenario({
    code: this.code(),
    label: this.libelle(),
    method: this.methode(),
    thresholdAmount: this.seuil() === null ? null : String(this.seuil()),
    windowDays: this.fenetre(),
    minimumCount: this.nombreMinimal(),
    ratio: this.facteur() === '' ? null : this.facteur().replace(',', '.'),
    riskRating: null,
    validFrom: this.dateDEffet(),
    validTo: this.dateDeFin(),
  }));

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.scenarios.set(await this.conformite.scenarios(this.config.legalEntityId()));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.scenarios.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected exige(parametre: string): boolean {
    return this.requis().includes(parametre);
  }

  protected ouvrirVolet(): void {
    this.volet.set(true);
    this.refus.set(null);
    this.enAttente.set(null);
    this.commence.set(false);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set(false);
  }

  /**
   * Changer de méthode efface les paramètres de la précédente.
   *
   * Les garder laisserait un seuil posé pour un cumul d'espèces partir avec un
   * réveil de compte dormant, où il veut dire autre chose.
   */
  protected choisirMethode(methode: MethodeScenario): void {
    this.methode.set(methode);
    this.seuil.set(null);
    this.fenetre.set(null);
    this.nombreMinimal.set(null);
    this.facteur.set('');
  }

  protected async declarer(): Promise<void> {
    this.commence.set(true);
    if (this.obstacles().length > 0) return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      const attente = await this.conformite.declarerScenario(this.config.legalEntityId(), {
        code: this.code().trim(),
        label: this.libelle().trim(),
        method: this.methode(),
        thresholdAmount: this.seuil() === null ? null : String(this.seuil()),
        windowDays: this.fenetre(),
        minimumCount: this.nombreMinimal(),
        ratio: this.facteur() === '' ? null : this.facteur().replace(',', '.'),
        riskRating: null,
        validFrom: this.dateDEffet(),
        validTo: this.dateDeFin(),
      }, this.cle);
      this.enAttente.set(attente.operationId);
      this.fermerVolet();
      this.cle = crypto.randomUUID();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected nombre(valeur: string): number | null {
    const n = Number(valeur);
    return valeur.trim() === '' || !Number.isFinite(n) ? null : Math.trunc(n);
  }

  protected taux(valeur: string | null): string {
    return formaterTaux(valeur);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
