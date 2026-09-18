import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
  CbToolbar,
} from '../../ui';
import { CONFORMITE } from '../conformite.port';
import { Declaration } from '../modele/conformite.modele';

/**
 * Les déclarations de soupçon, et leur dépôt.
 *
 * **Une déclaration ne se rédige pas ici.** Elle se rédige depuis l'alerte,
 * parce qu'elle cite les alertes qu'elle couvre et qu'un exposé des faits
 * écrit loin des pièces ne vaut rien. Cet écran sert à ce qui vient après :
 * savoir ce qui est parti, et ce qui ne l'est pas encore.
 *
 * **La transmission enregistre la référence rendue par la cellule.** Ce n'est
 * pas une formalité : c'est la preuve du dépôt, et la seule chose que la banque
 * pourra produire si on lui demande un jour quand elle a déclaré.
 */
@Component({
  selector: 'cb-declarations',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge,
    CbTable, CbToolbar,
  ],
  templateUrl: './declarations.page.html',
  styleUrl: './declarations.page.css',
})
export class Declarations {
  private readonly conformite = inject(CONFORMITE);
  private readonly config = inject(AppConfig);

  protected readonly declarations = signal<readonly Declaration[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly envoi = signal(false);

  /** La déclaration dont on saisit la transmission. Nulle : aucun volet ouvert. */
  protected readonly enTransmission = signal<Declaration | null>(null);
  protected readonly reference = signal('');
  protected readonly dateDeDepot = signal<string | null>(null);

  private cle = crypto.randomUUID();

  /** Ce qui est rédigé et pas encore déposé : le retard qui se voit. */
  protected readonly nonTransmises = computed(() =>
    this.declarations().filter((d) => d.transmittedOn === null).length);

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.declarations.set(await this.conformite.declarations(this.config.legalEntityId()));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.declarations.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirTransmission(declaration: Declaration): void {
    this.enTransmission.set(declaration);
    this.reference.set('');
    this.dateDeDepot.set(null);
    this.refus.set(null);
    this.cle = crypto.randomUUID();
  }

  protected fermerTransmission(): void {
    this.enTransmission.set(null);
  }

  protected async transmettre(): Promise<void> {
    const declaration = this.enTransmission();
    if (!declaration) return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      await this.conformite.transmettre(this.config.legalEntityId(), declaration.id, {
        reference: this.reference().trim(),
        transmittedOn: this.dateDeDepot(),
      }, this.cle);
      this.fermerTransmission();
      await this.charger();
      this.cle = crypto.randomUUID();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
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
