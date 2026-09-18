import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbNotice, CbPagination,
  CbSection, CbStateBadge, CbTable, CbToolbar, EtatOperation,
} from '../../ui';
import {
  ACTES_A_MOTIF, ACTES_A_NOSTRO, Acte, actesSurOrdre, ATTENTE_ORDRE, DemandeOrdre, LIBELLE_ACTE,
  LIBELLE_STATUT_ORDRE, obstaclesAUnOrdre, OrdrePaiement, StatutOrdre,
} from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS: readonly StatutOrdre[] = ['ORDERED', 'SENT', 'SETTLED', 'RETURNED', 'CANCELLED'];

/**
 * Les virements émis.
 *
 * Un ordre sortant naît **débité** : le compte du client est mouvementé tout de
 * suite, frais compris, et le montant attend sur un compte de règlement. Rien
 * n'est parti. C'est ce que l'écran doit faire comprendre, parce que c'est ce
 * qui rend l'annulation possible — et seulement à ce moment-là.
 *
 * Deux partis pris :
 *
 *   **la file s'ouvre sur ce qui attend.** Un service de compensation vient
 *   voir ce qui n'est pas parti et ce qui n'est pas réglé, pas l'historique ;
 *
 *   **chaque état dit ce qu'il attend, au présent.** « Parti au système de
 *   paiement. Il ne s'annule plus. » vaut mieux qu'un badge muet : c'est la
 *   phrase que l'agent répétera au client qui appelle.
 */
@Component({
  selector: 'cb-virements-emis',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbNotice,
            CbPagination, CbSection, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './virements.page.html',
  styleUrl: './virements.page.css',
})
export class VirementsEmis {
  private readonly paiements = inject(PAIEMENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly STATUTS = STATUTS;
  protected readonly LIBELLE_STATUT_ORDRE = LIBELLE_STATUT_ORDRE;
  protected readonly LIBELLE_ACTE = LIBELLE_ACTE;
  protected readonly ATTENTE_ORDRE = ATTENTE_ORDRE;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly lignes = signal<readonly OrdrePaiement[]>([]);
  protected readonly choisi = signal<OrdrePaiement | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly filtre = signal<StatutOrdre | null>(null);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  /** L'acte en cours de confirmation : il attend son motif ou son nostro. */
  protected readonly acteEnCours = signal<Acte | null>(null);
  protected readonly motif = signal('');
  protected readonly nostro = signal('');

  protected readonly saisieOuverte = signal(false);
  protected readonly compte = signal('');
  protected readonly montant = signal<number | null>(null);
  protected readonly beneficiaire = signal('');
  protected readonly banque = signal('');
  protected readonly compteBeneficiaire = signal('');
  protected readonly reference = signal('');

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDOrdonner = computed(() => this.droits.peut('PAYMENT_ORDER'));
  protected readonly droitDeTraiter = computed(() => this.droits.peut('PAYMENT_PROCESS'));

  protected readonly actes = computed(() => {
    const ordre = this.choisi();
    return ordre === null || !this.droitDeTraiter() ? [] : actesSurOrdre(ordre);
  });

  /** Ce qui attend une décision : c'est le chiffre qu'un service de compensation vient chercher. */
  protected readonly aTraiter = computed(
    () => this.lignes().filter((o) => actesSurOrdre(o).length > 0).length);

  protected readonly demande = computed<DemandeOrdre>(() => ({
    accountId: this.compte().trim(),
    amount: this.montant() === null ? '' : String(this.montant()),
    currency: this.config.valeur().affichage.deviseParDefaut,
    beneficiaryName: this.beneficiaire().trim(),
    beneficiaryBank: this.banque().trim(),
    beneficiaryAccount: this.compteBeneficiaire().trim(),
    reference: this.reference().trim() || null,
    channel: 'BRANCH',
  }));

  protected readonly obstacles = computed(() => obstaclesAUnOrdre(this.demande()));

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
      const rendu = await this.paiements.ordres(
        this.config.legalEntityId(), this.filtre(), page, this.taille());
      this.lignes.set(rendu.lignes);
      this.page.set(rendu.numero);
      this.precedent.set(rendu.precedent);
      this.suivant.set(rendu.suivant);
      this.rafraichirChoisi(rendu.lignes);
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /** Après un rechargement, la ligne ouverte suit la file plutôt que de rester figée. */
  private rafraichirChoisi(lignes: readonly OrdrePaiement[]): void {
    const ouvert = this.choisi();
    if (ouvert !== null) {
      this.choisi.set(lignes.find((o) => o.id === ouvert.id) ?? null);
    }
  }

  protected filtrer(statut: StatutOrdre | null): void {
    this.filtre.set(statut);
    this.acquitte.set(null);
    void this.charger(0);
  }

  protected changerPage(pas: number): void {
    void this.charger(Math.max(0, this.page() + pas));
  }

  protected ouvrir(ordre: OrdrePaiement): void {
    this.choisi.set(ordre);
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
    const ordre = this.choisi();
    const acte = this.acteEnCours();
    if (ordre === null || acte === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const rendu = await this.paiements.deciderOrdre(this.config.legalEntityId(), ordre.id, {
        acte,
        motif: this.exigeUnMotif() ? this.motif().trim() : undefined,
        nostroAccountId: this.exigeUnNostro() ? this.nostro().trim() : undefined,
      });
      this.choisi.set(rendu);
      this.acteEnCours.set(null);
      this.acquitte.set(`${LIBELLE_ACTE[acte]} : l'ordre ${rendu.id} est `
                        + `${LIBELLE_STATUT_ORDRE[rendu.status].toLowerCase()}.`);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected ouvrirLaSaisie(): void {
    this.saisieOuverte.set(true);
    this.acquitte.set(null);
  }

  protected fermerLaSaisie(): void {
    this.saisieOuverte.set(false);
  }

  protected async ordonner(): Promise<void> {
    if (this.obstacles().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const cree = await this.paiements.ordonner(
        this.config.legalEntityId(), this.demande(), crypto.randomUUID());
      this.saisieOuverte.set(false);
      this.viderLaSaisie();
      this.acquitte.set(`L'ordre ${cree.id} est enregistré. Le compte est déjà débité, frais `
                        + "compris ; rien n'est parti tant qu'il n'est pas envoyé.");
      await this.charger(0);
      this.choisi.set(this.lignes().find((o) => o.id === cree.id) ?? cree);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  private viderLaSaisie(): void {
    this.compte.set('');
    this.montant.set(null);
    this.beneficiaire.set('');
    this.banque.set('');
    this.compteBeneficiaire.set('');
    this.reference.set('');
  }

  /**
   * L'état de l'ordre, dit avec le vocabulaire fermé du badge.
   *
   * Le mot affiché est celui du métier ; la couleur vient du vocabulaire commun
   * du poste — en attente tant que rien n'est dénoué, comptabilisé quand le
   * correspondant a payé, rejeté quand l'ordre revient.
   */
  protected etatDe(ordre: OrdrePaiement): EtatOperation {
    switch (ordre.status) {
      case 'ORDERED':
        return 'en-attente';
      case 'SENT':
        return 'approuve';
      case 'SETTLED':
        return 'comptabilise';
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
