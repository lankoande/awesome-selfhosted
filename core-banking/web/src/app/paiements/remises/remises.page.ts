import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbNotice, CbPagination,
  CbSection, CbStateBadge, CbTable, CbToolbar, EtatOperation,
} from '../../ui';
import {
  ACTES_A_MOTIF, ACTES_A_NOSTRO, Acte, actesSurRemise, ATTENTE_REMISE, DemandeRemise, LIBELLE_ACTE,
  LIBELLE_STATUT_REMISE, obstaclesAUneRemise, Remise, StatutRemise,
} from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS: readonly StatutRemise[] = ['DEPOSITED', 'SETTLED', 'RETURNED'];

/**
 * Les remises de chèques.
 *
 * Une remise est **créditée sauf bonne fin** : le compte du client monte tout
 * de suite, et le montant reste bloqué jusqu'à ce que la banque tirée règle.
 * Le solde monte, le disponible non — c'est la seule façon honnête de présenter
 * un chèque en cours d'encaissement, et c'est ce que l'écran répète partout.
 *
 * Un client qui voit son solde augmenter et son disponible immobile appellera.
 * La phrase d'état est la réponse à cet appel.
 */
@Component({
  selector: 'cb-remises-cheques',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbNotice,
            CbPagination, CbSection, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './remises.page.html',
  styleUrl: './remises.page.css',
})
export class RemisesCheques {
  private readonly paiements = inject(PAIEMENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly STATUTS = STATUTS;
  protected readonly LIBELLE_STATUT_REMISE = LIBELLE_STATUT_REMISE;
  protected readonly LIBELLE_ACTE = LIBELLE_ACTE;
  protected readonly ATTENTE_REMISE = ATTENTE_REMISE;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly lignes = signal<readonly Remise[]>([]);
  protected readonly choisie = signal<Remise | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly filtre = signal<StatutRemise | null>(null);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  protected readonly acteEnCours = signal<Acte | null>(null);
  protected readonly motif = signal('');
  protected readonly nostro = signal('');

  protected readonly saisieOuverte = signal(false);
  protected readonly compte = signal('');
  protected readonly montant = signal<number | null>(null);
  protected readonly banqueTiree = signal('');
  protected readonly numeroCheque = signal('');
  protected readonly tireur = signal('');

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeRemettre = computed(() => this.droits.peut('CHEQUE_DEPOSIT'));
  protected readonly droitDeTraiter = computed(() => this.droits.peut('CHEQUE_PROCESS'));

  protected readonly actes = computed(() => {
    const remise = this.choisie();
    return remise === null || !this.droitDeTraiter() ? [] : actesSurRemise(remise);
  });

  /** Ce qui reste à l'encaissement : l'argent d'un client immobilisé. */
  protected readonly aLEncaissement = computed(
    () => this.lignes().filter((r) => r.status === 'DEPOSITED').length);

  protected readonly demande = computed<DemandeRemise>(() => ({
    accountId: this.compte().trim(),
    amount: this.montant() === null ? '' : String(this.montant()),
    currency: this.config.valeur().affichage.deviseParDefaut,
    draweeBank: this.banqueTiree().trim(),
    chequeNumber: this.numeroCheque().trim(),
    drawerName: this.tireur().trim() || null,
    channel: 'BRANCH',
  }));

  protected readonly obstacles = computed(() => obstaclesAUneRemise(this.demande()));

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
      const rendu = await this.paiements.remises(
        this.config.legalEntityId(), this.filtre(), page, this.taille());
      this.lignes.set(rendu.lignes);
      this.page.set(rendu.numero);
      this.precedent.set(rendu.precedent);
      this.suivant.set(rendu.suivant);
      const ouverte = this.choisie();
      if (ouverte !== null) {
        this.choisie.set(rendu.lignes.find((r) => r.id === ouverte.id) ?? null);
      }
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected filtrer(statut: StatutRemise | null): void {
    this.filtre.set(statut);
    this.acquitte.set(null);
    void this.charger(0);
  }

  protected changerPage(pas: number): void {
    void this.charger(Math.max(0, this.page() + pas));
  }

  protected ouvrir(remise: Remise): void {
    this.choisie.set(remise);
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
    const remise = this.choisie();
    const acte = this.acteEnCours();
    if (remise === null || acte === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const rendu = await this.paiements.deciderRemise(this.config.legalEntityId(), remise.id, {
        acte,
        motif: this.exigeUnMotif() ? this.motif().trim() : undefined,
        nostroAccountId: this.exigeUnNostro() ? this.nostro().trim() : undefined,
      });
      this.choisie.set(rendu);
      this.acteEnCours.set(null);
      this.acquitte.set(acte === 'REGLER'
        ? `La remise ${rendu.id} est réglée : le blocage est levé, le client dispose des fonds.`
        : `La remise ${rendu.id} revient impayée : le crédit est contre-passé et le blocage levé.`);
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

  protected async remettre(): Promise<void> {
    if (this.obstacles().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const creee = await this.paiements.remettre(
        this.config.legalEntityId(), this.demande(), crypto.randomUUID());
      this.saisieOuverte.set(false);
      this.viderLaSaisie();
      this.acquitte.set(`La remise ${creee.id} est enregistrée. Le compte est crédité sauf bonne `
                        + 'fin : le montant est bloqué jusqu’au règlement de la banque tirée.');
      await this.charger(0);
      this.choisie.set(this.lignes().find((r) => r.id === creee.id) ?? creee);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  private viderLaSaisie(): void {
    this.compte.set('');
    this.montant.set(null);
    this.banqueTiree.set('');
    this.numeroCheque.set('');
    this.tireur.set('');
  }

  protected etatDe(remise: Remise): EtatOperation {
    switch (remise.status) {
      case 'DEPOSITED':
        return 'en-attente';
      case 'SETTLED':
        return 'comptabilise';
      default:
        return 'rejete';
    }
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
