import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput, CbInterdit, CbNotice,
  CbSection, CbStateBadge, CbTable,
} from '../../ui';
import { REGLEMENTAIRE } from '../reglementaire.port';
import {
  DossierEtat, LIBELLE_METHODE_ETAT, LIBELLE_NATURE_SUJET, LIBELLE_STATUT_ETAT, etatDeLEtat,
  libellePeriode, obstaclesALAnnulation, obstaclesALaTransmission,
} from '../modele/reglementaire.modele';

type Volet = 'aucun' | 'transmettre' | 'annuler';

/**
 * Le détail d'un état réglementaire.
 *
 * **Trois choses que cet écran doit faire comprendre**, et qui sont toutes
 * contre-intuitives :
 *
 *   **un état est figé avec son paramétrage.** Le seuil qu'il porte est celui
 *   du jour de sa production, pas celui d'aujourd'hui. C'est ce qui permet de
 *   répondre à l'inspection quand un état régénéré sort différent : ce sont les
 *   données qui ont bougé, ou le paramétrage, et on sait lequel ;
 *
 *   **un état en anomalie se produit mais ne se transmet pas.** Produire sert
 *   justement à voir ce qui ne va pas. L'écran montre les anomalies en premier
 *   et explique pourquoi la transmission est fermée — plutôt qu'un bouton grisé
 *   sans raison ;
 *
 *   **un état transmis ne s'annule pas.** Ce qui est parti est parti ; on
 *   dépose un rectificatif. L'écart de recalcul, lui, n'est pas une curiosité :
 *   un état transmis qu'on ne sait plus reproduire est le signe que quelque
 *   chose a bougé derrière lui.
 */
@Component({
  selector: 'cb-etat',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput, CbInterdit, CbNotice,
    CbSection, CbStateBadge, CbTable,
  ],
  templateUrl: './etat.page.html',
  styleUrl: './etat.page.css',
})
export class DetailEtat {
  readonly id = input.required<string>();

  private readonly reglementaire = inject(REGLEMENTAIRE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly router = inject(Router);

  protected readonly LIBELLE_STATUT_ETAT = LIBELLE_STATUT_ETAT;
  protected readonly LIBELLE_METHODE_ETAT = LIBELLE_METHODE_ETAT;
  protected readonly LIBELLE_NATURE_SUJET = LIBELLE_NATURE_SUJET;
  protected readonly etatDeLEtat = etatDeLEtat;
  protected readonly libellePeriode = libellePeriode;

  protected readonly dossier = signal<DossierEtat | null>(null);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly envoi = signal(false);
  protected readonly volet = signal<Volet>('aucun');
  protected readonly enAttente = signal<string | null>(null);

  protected readonly reference = signal('');
  protected readonly dateDeDepot = signal<string | null>(null);
  protected readonly motif = signal('');

  private cle = crypto.randomUUID();

  protected readonly etat = computed(() => this.dossier()?.etat ?? null);

  protected readonly obstaclesTransmission = computed(() => {
    const etat = this.etat();
    return etat ? obstaclesALaTransmission(etat) : [];
  });

  protected readonly obstaclesAnnulation = computed(() => {
    const etat = this.etat();
    return etat ? obstaclesALAnnulation(etat) : [];
  });

  /**
   * Annuler exige le droit de **transmettre**, pas celui de produire.
   *
   * Ce n'est pas une bizarrerie : reprendre un état est une décision sur ce que
   * la banque déclarera, pas un travail de production. Un comptable qui produit
   * ne défait pas seul ce qu'il a produit.
   */
  protected readonly peutTransmettre = computed(
    () => this.droits.peut('REGULATORY_REPORT_TRANSMIT'));

  constructor() {
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.dossier.set(await this.reglementaire.etat(this.config.legalEntityId(), id));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(volet: Volet): void {
    this.volet.set(volet);
    this.refus.set(null);
    this.enAttente.set(null);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set('aucun');
  }

  protected async transmettre(): Promise<void> {
    await this.agir(async () => {
      const attente = await this.reglementaire.transmettre(
        this.config.legalEntityId(), this.id(),
        { reference: this.reference().trim(), transmittedOn: this.dateDeDepot() }, this.cle);
      this.enAttente.set(attente.operationId);
      this.fermerVolet();
    });
  }

  protected async annuler(): Promise<void> {
    await this.agir(async () => {
      await this.reglementaire.annuler(this.config.legalEntityId(), this.id(),
                                       this.motif().trim(), this.cle);
      this.motif.set('');
      this.fermerVolet();
    });
  }

  private async agir(acte: () => Promise<void>): Promise<void> {
    this.envoi.set(true);
    this.refus.set(null);
    try {
      await acte();
      await this.charger();
      this.cle = crypto.randomUUID();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected auxEtats(): void {
    void this.router.navigate(['/reglementaire/etats']);
  }

  protected seuil(valeur: string | null): string {
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
