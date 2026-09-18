import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbField, CbInput, CbNotice, CbPagination, CbSection,
  CbStateBadge, CbTable, CbToolbar, EtatOperation,
} from '../../ui';
import {
  ACTES_A_MOTIF, ACTES_A_NOSTRO, Acte, actesSurPrelevement, ATTENTE_PRELEVEMENT, LIBELLE_ACTE,
  LIBELLE_SENS, LIBELLE_STATUT_PRELEVEMENT, Prelevement, SensPrelevement, StatutPrelevement,
} from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS: readonly StatutPrelevement[] = [
  'PENDING', 'COLLECTED', 'REJECTED', 'SETTLED', 'CANCELLED', 'RETURNED', 'REFUNDED',
];

/**
 * Les prélèvements, reçus et émis.
 *
 * Le sens commande autant que l'état. Un prélèvement **reçu** est un créancier
 * qui prélève sur un compte de la banque, sur un mandat signé par le débiteur :
 * il se rembourse quand le débiteur conteste. Un prélèvement **émis** est un
 * client de la banque qui prélève ailleurs : il revient impayé quand le
 * débiteur d'ailleurs ne paie pas. Les deux ne se confondent pas, et l'écran ne
 * les mélange pas.
 *
 * **Le poste n'exécute pas un prélèvement.** Le passage de « en attente » à
 * « exécuté » est le travail du traitement de fin de journée, à l'échéance. Un
 * bouton « Exécuter » laisserait croire qu'un prélèvement se force à la main,
 * hors de l'arrêté — et c'est faux.
 */
@Component({
  selector: 'cb-prelevements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbButton, CbField, CbInput, CbNotice, CbPagination, CbSection,
            CbStateBadge, CbTable, CbToolbar],
  templateUrl: './prelevements.page.html',
  styleUrl: './prelevements.page.css',
})
export class Prelevements {
  private readonly paiements = inject(PAIEMENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly STATUTS = STATUTS;
  protected readonly LIBELLE_STATUT_PRELEVEMENT = LIBELLE_STATUT_PRELEVEMENT;
  protected readonly LIBELLE_SENS = LIBELLE_SENS;
  protected readonly LIBELLE_ACTE = LIBELLE_ACTE;
  protected readonly ATTENTE_PRELEVEMENT = ATTENTE_PRELEVEMENT;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly lignes = signal<readonly Prelevement[]>([]);
  protected readonly choisi = signal<Prelevement | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly sens = signal<SensPrelevement | null>(null);
  protected readonly filtre = signal<StatutPrelevement | null>(null);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  protected readonly acteEnCours = signal<Acte | null>(null);
  protected readonly motif = signal('');
  protected readonly nostro = signal('');

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeTraiter = computed(() => this.droits.peut('DIRECT_DEBIT_PROCESS'));

  protected readonly actes = computed(() => {
    const prelevement = this.choisi();
    return prelevement === null || !this.droitDeTraiter() ? []
                                                          : actesSurPrelevement(prelevement);
  });

  /** Ce qui attend l'échéance : le traitement de fin de journée s'en chargera. */
  protected readonly enAttente = computed(
    () => this.lignes().filter((p) => p.status === 'PENDING').length);

  protected readonly exigeUnMotif = computed(() => {
    const acte = this.acteEnCours();
    return acte !== null && ACTES_A_MOTIF.includes(acte);
  });

  protected readonly exigeUnNostro = computed(() => {
    const acte = this.acteEnCours();
    return acte !== null && ACTES_A_NOSTRO.includes(acte);
  });

  protected readonly peutConfirmer = computed(() => {
    if (this.travaille() || this.acteEnCours() === null) {
      return false;
    }
    if (this.exigeUnMotif() && !this.motif().trim()) {
      return false;
    }
    return !this.exigeUnNostro() || this.nostro().trim().length > 0;
  });

  constructor() {
    void this.charger();
  }

  protected async charger(page = this.page()): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      const rendu = await this.paiements.prelevements(
        this.config.legalEntityId(), this.sens(), this.filtre(), page, this.taille());
      this.lignes.set(rendu.lignes);
      this.page.set(rendu.numero);
      this.precedent.set(rendu.precedent);
      this.suivant.set(rendu.suivant);
      const ouvert = this.choisi();
      if (ouvert !== null) {
        this.choisi.set(rendu.lignes.find((p) => p.id === ouvert.id) ?? null);
      }
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected filtrerSens(sens: SensPrelevement | null): void {
    this.sens.set(sens);
    this.acquitte.set(null);
    void this.charger(0);
  }

  protected filtrer(statut: StatutPrelevement | null): void {
    this.filtre.set(statut);
    this.acquitte.set(null);
    void this.charger(0);
  }

  protected changerPage(pas: number): void {
    void this.charger(Math.max(0, this.page() + pas));
  }

  protected ouvrir(prelevement: Prelevement): void {
    this.choisi.set(prelevement);
    this.acteEnCours.set(null);
    this.acquitte.set(null);
    this.refus.set(null);
  }

  protected demarrer(acte: Acte): void {
    this.acteEnCours.set(acte);
    this.motif.set('');
    this.nostro.set('');
  }

  protected abandonner(): void {
    this.acteEnCours.set(null);
  }

  protected async confirmer(): Promise<void> {
    const prelevement = this.choisi();
    const acte = this.acteEnCours();
    if (prelevement === null || acte === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const rendu = await this.paiements.deciderPrelevement(
        this.config.legalEntityId(), prelevement.id, {
          acte,
          motif: this.exigeUnMotif() ? this.motif().trim() : undefined,
          nostroAccountId: this.exigeUnNostro() ? this.nostro().trim() : undefined,
        });
      this.choisi.set(rendu);
      this.acteEnCours.set(null);
      this.acquitte.set(`${LIBELLE_ACTE[acte]} : le prélèvement ${rendu.id} est `
                        + `${LIBELLE_STATUT_PRELEVEMENT[rendu.status].toLowerCase()}.`);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /** Le libellé de l'acte dit le sens : rembourser un reçu, retourner un émis. */
  protected libelleActe(acte: Acte, prelevement: Prelevement): string {
    if (acte === 'REMBOURSER') {
      return 'Rembourser le débiteur';
    }
    if (acte === 'RETOURNER') {
      return 'Retourner impayé';
    }
    if (acte === 'ANNULER' && prelevement.status === 'PENDING') {
      return 'Retirer avant échéance';
    }
    return LIBELLE_ACTE[acte];
  }

  protected etatDe(prelevement: Prelevement): EtatOperation {
    switch (prelevement.status) {
      case 'PENDING':
        return 'en-attente';
      case 'COLLECTED':
        return 'approuve';
      case 'SETTLED':
        return 'comptabilise';
      case 'REJECTED':
      case 'RETURNED':
        return 'rejete';
      default:
        return 'contre-passe';
    }
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
