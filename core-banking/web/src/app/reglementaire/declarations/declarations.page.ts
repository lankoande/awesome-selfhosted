import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { autorise } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
  CbSection, CbTable, CbToolbar,
} from '../../ui';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { REGLEMENTAIRE } from '../reglementaire.port';
import {
  Declaration, Destinataire, EFFET_METHODE_ETAT, Frequence, LIBELLE_DESTINATAIRE,
  LIBELLE_FREQUENCE, LIBELLE_METHODE_ETAT, METHODES_A_SEUIL, MethodeEtat, libellePeriode,
  obstaclesAuCatalogue, periodesCloses,
} from '../modele/reglementaire.modele';

const METHODES: readonly MethodeEtat[] = [
  'ACCOUNTING_SITUATION', 'CREDIT_REGISTRY', 'PAYMENT_INCIDENTS', 'CREDIT_BUREAU',
  'TAX_COLLECTION', 'STATEMENT_PACK', 'CONSOLIDATED_STATEMENTS',
];
const DESTINATAIRES: readonly Destinataire[] = [
  'CENTRAL_BANK', 'BANKING_COMMISSION', 'CREDIT_BUREAU', 'TAX_AUTHORITY',
];
const FREQUENCES: readonly Frequence[] = ['MONTHLY', 'QUARTERLY', 'YEARLY'];

/**
 * Le catalogue : ce que la banque doit, à qui, et sous quel délai.
 *
 * **Le délai de dépôt n'est pas cosmétique.** C'est lui qui fait exister
 * l'échéance : une déclaration sans délai ne produit aucun retard, donc aucune
 * alerte, et personne ne voit rien manquer. L'écran l'exige.
 *
 * **Le seuil suit la méthode.** Seule la centrale des risques recense au-delà
 * d'un seuil ; le poser ailleurs écrirait un paramètre que rien ne lit. Le
 * formulaire ne le propose que là, et l'exige là.
 *
 * On produit aussi depuis cet écran, pour une période choisie — l'écran des
 * échéances ne propose que les périodes en retard, et il arrive qu'on reprenne
 * un mois plus ancien.
 */
@Component({
  selector: 'cb-catalogue-reglementaire',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
    CbSection, CbTable, CbToolbar,
  ],
  templateUrl: './declarations.page.html',
  styleUrl: './declarations.page.css',
})
export class CatalogueReglementaire {
  private readonly reglementaire = inject(REGLEMENTAIRE);
  private readonly config = inject(AppConfig);
  private readonly authentification = inject(AUTHENTIFICATION);
  private readonly router = inject(Router);

  protected readonly METHODES = METHODES;
  protected readonly DESTINATAIRES = DESTINATAIRES;
  protected readonly FREQUENCES = FREQUENCES;
  protected readonly LIBELLE_METHODE_ETAT = LIBELLE_METHODE_ETAT;
  protected readonly EFFET_METHODE_ETAT = EFFET_METHODE_ETAT;
  protected readonly LIBELLE_DESTINATAIRE = LIBELLE_DESTINATAIRE;
  protected readonly LIBELLE_FREQUENCE = LIBELLE_FREQUENCE;
  protected readonly libellePeriode = libellePeriode;

  protected readonly declarations = signal<readonly Declaration[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly envoi = signal(false);
  protected readonly enAttente = signal<string | null>(null);
  protected readonly commence = signal(false);
  protected readonly volet = signal(false);

  /** La déclaration dont on produit un état. Nulle : aucun volet de production. */
  protected readonly enProduction = signal<Declaration | null>(null);
  protected readonly periode = signal('');

  protected readonly code = signal('');
  protected readonly libelle = signal('');
  protected readonly methode = signal<MethodeEtat>('ACCOUNTING_SITUATION');
  protected readonly destinataire = signal<Destinataire>('CENTRAL_BANK');
  protected readonly frequence = signal<Frequence>('MONTHLY');
  protected readonly delai = signal<number | null>(null);
  protected readonly seuil = signal<number | null>(null);
  protected readonly dateDEffet = signal<string | null>(null);
  protected readonly dateDeFin = signal<string | null>(null);

  private cle = crypto.randomUUID();

  protected readonly admetUnSeuil = computed(() => METHODES_A_SEUIL.includes(this.methode()));

  protected readonly peutProduire = computed(() =>
    autorise(this.authentification.habilitations(), 'REGULATORY_REPORT_PRODUCE'));

  protected readonly peutDeclarer = computed(() =>
    autorise(this.authentification.habilitations(), 'REGULATORY_DECLARATION_MANAGE'));

  /** Les périodes closes de la déclaration en production, la plus récente d'abord. */
  protected readonly periodesProposees = computed(() => {
    const declaration = this.enProduction();
    if (!declaration) return [];
    return periodesCloses(declaration.frequency, new Date().toISOString().slice(0, 10));
  });

  protected readonly obstacles = computed(() => obstaclesAuCatalogue({
    code: this.code(),
    label: this.libelle(),
    recipient: this.destinataire(),
    method: this.methode(),
    frequency: this.frequence(),
    deadlineDays: this.delai(),
    thresholdAmount: this.seuil() === null ? null : String(this.seuil()),
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
      this.declarations.set(await this.reglementaire.declarations(this.config.legalEntityId()));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.declarations.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(): void {
    this.volet.set(true);
    this.enProduction.set(null);
    this.refus.set(null);
    this.enAttente.set(null);
    this.commence.set(false);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set(false);
  }

  /** Changer de méthode efface le seuil : il ne veut pas dire la même chose ailleurs. */
  protected choisirMethode(methode: MethodeEtat): void {
    this.methode.set(methode);
    if (!METHODES_A_SEUIL.includes(methode)) this.seuil.set(null);
  }

  protected ouvrirProduction(declaration: Declaration): void {
    this.enProduction.set(declaration);
    this.volet.set(false);
    this.refus.set(null);
    this.periode.set('');
    this.cle = crypto.randomUUID();
  }

  protected fermerProduction(): void {
    this.enProduction.set(null);
  }

  protected async produire(): Promise<void> {
    const declaration = this.enProduction();
    if (!declaration || this.periode() === '') return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      const etat = await this.reglementaire.produire(
        this.config.legalEntityId(), declaration.id, this.periode(), this.cle);
      void this.router.navigate(['/reglementaire/etats', etat.id]);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected async declarer(): Promise<void> {
    this.commence.set(true);
    if (this.obstacles().length > 0) return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      const attente = await this.reglementaire.declarer(this.config.legalEntityId(), {
        code: this.code().trim(),
        label: this.libelle().trim(),
        recipient: this.destinataire(),
        method: this.methode(),
        frequency: this.frequence(),
        deadlineDays: this.delai(),
        thresholdAmount: this.seuil() === null ? null : String(this.seuil()),
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
