import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { CbChoixCompte } from '../../clients/composants/choix-compte';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
  CbSection, CbStateBadge, CbTable, CbTabs, CbToolbar, EtatOperation, Onglet,
} from '../../ui';
import {
  actesSurCheque, ATTENTE_CHEQUE, Cheque, Chequier, DemandeMandat, IncidentCheque,
  LIBELLE_MODE_PAIEMENT, LIBELLE_MOTIF_OPPOSITION, LIBELLE_STATUT_CHEQUE,
  LIBELLE_STATUT_CHEQUIER, LIBELLE_STATUT_MANDAT, Mandat, ModePaiement, MotifOpposition,
  obstaclesAuChequier, obstaclesAuMandat, obstaclesAuPaiement, peutRevoquer, StatutCheque,
} from '../modele/compte.modele';
import { PAIEMENTS } from '../paiements.port';

type Phase = 'repos' | 'chargement' | 'prete' | 'envoi' | 'refuse';

const STATUTS_CHEQUE: readonly StatutCheque[] = ['UNUSED', 'PAID', 'STOPPED', 'REJECTED'];
const MOTIFS: readonly MotifOpposition[] = ['LOSS', 'THEFT', 'FRAUDULENT_USE',
                                            'BEARER_INSOLVENCY'];
const MODES: readonly ModePaiement[] = ['CASH', 'CLEARING'];

/**
 * Les moyens de paiement d'un compte : chéquiers, chèques, incidents, mandats.
 *
 * Les trois autres écrans de cet espace sont des **files** — on y regarde ce qui
 * attend, tous comptes confondus. Celui-ci part du compte, parce que les actes
 * qu'il porte n'ont de sens que sur un compte nommé : on ne délivre pas un
 * chéquier à la compensation, on fait opposition sur le carnet d'un client.
 *
 * Quatre règles du socle que l'écran annonce sans les tenir :
 *
 *   **un chéquier se délivre à deux.** Le socle répond une opération en attente,
 *   pas un carnet : l'écran dit « demandé », jamais « délivré » ;
 *
 *   **l'opposition n'est pas libre.** La loi uniforme UEMOA l'enferme dans
 *   quatre motifs, et l'écran n'en propose pas un cinquième ;
 *
 *   **la caisse du paiement au guichet vient du jeton.** L'écran ne la choisit
 *   pas — il ne la montre même pas, parce qu'elle ne se discute pas ;
 *
 *   **un mandat s'enregistre à deux**, et son créancier se désigne une fois :
 *   un compte de la banque, ou une banque et un compte d'ailleurs.
 */
@Component({
  selector: 'cb-compte-paiements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbAmountInput, CbButton, CbChoixCompte, CbDateInput, CbField,
            CbInput, CbNotice, CbSection, CbStateBadge, CbTable, CbTabs, CbToolbar],
  templateUrl: './compte.page.html',
  styleUrl: './compte.page.css',
})
export class MoyensDePaiementDuCompte {
  private readonly paiements = inject(PAIEMENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly route = inject(ActivatedRoute);

  protected readonly STATUTS_CHEQUE = STATUTS_CHEQUE;
  protected readonly MOTIFS = MOTIFS;
  protected readonly MODES = MODES;
  protected readonly LIBELLE_STATUT_CHEQUIER = LIBELLE_STATUT_CHEQUIER;
  protected readonly LIBELLE_STATUT_CHEQUE = LIBELLE_STATUT_CHEQUE;
  protected readonly LIBELLE_STATUT_MANDAT = LIBELLE_STATUT_MANDAT;
  protected readonly LIBELLE_MOTIF_OPPOSITION = LIBELLE_MOTIF_OPPOSITION;
  protected readonly LIBELLE_MODE_PAIEMENT = LIBELLE_MODE_PAIEMENT;
  protected readonly ATTENTE_CHEQUE = ATTENTE_CHEQUE;

  protected readonly compteId = signal('');

  /**
   * Le numéro de compte porté par l'URL, quand on arrive du dossier client.
   *
   * C'est un **numéro**, pas un identifiant : l'adresse se recopie, se met en
   * favori, se dicte. Le composant de choix le résout et rend l'identifiant.
   */
  protected readonly numeroDemande = this.route.snapshot.queryParamMap.get('compte') ?? '';
  protected readonly phase = signal<Phase>('repos');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);

  protected readonly chequiers = signal<readonly Chequier[]>([]);
  protected readonly cheques = signal<readonly Cheque[]>([]);
  protected readonly incidents = signal<readonly IncidentCheque[]>([]);
  protected readonly mandats = signal<readonly Mandat[]>([]);

  protected readonly onglet = signal('chequiers');
  protected readonly filtreCheque = signal<StatutCheque | null>(null);

  /** Ce qui est ouvert : une demande de chéquier, un acte sur un chèque, un mandat. */
  protected readonly saisieChequier = signal(false);
  protected readonly nombreDeCheques = signal(25);
  protected readonly chequeChoisi = signal<Cheque | null>(null);
  protected readonly acteCheque = signal<'PAYER' | 'OPPOSER' | null>(null);
  protected readonly montant = signal<number | null>(null);
  protected readonly mode = signal<ModePaiement>('CASH');
  protected readonly nostro = signal('');
  protected readonly porteur = signal('');
  protected readonly motifOpposition = signal<MotifOpposition>('LOSS');
  protected readonly saisieMandat = signal(false);
  protected readonly mandatChoisi = signal<Mandat | null>(null);
  protected readonly motifRevocation = signal('');

  protected readonly reference = signal('');
  protected readonly identifiantCreancier = signal('');
  protected readonly nomCreancier = signal('');
  protected readonly creancierInterne = signal(false);
  protected readonly compteCreancier = signal('');
  protected readonly banqueCreancier = signal('');
  protected readonly ibanCreancier = signal('');
  protected readonly signeLe = signal<string | null>(null);
  protected readonly effetLe = signal<string | null>(null);
  protected readonly finLe = signal<string | null>(null);
  protected readonly plafond = signal<number | null>(null);

  protected readonly devise = computed(() => this.config.valeur().affichage.deviseParDefaut);

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly onglets = computed<readonly Onglet[]>(() => [
    { id: 'chequiers', libelle: 'Chéquiers', compte: this.chequiers().length },
    { id: 'cheques', libelle: 'Chèques', compte: this.cheques().length },
    { id: 'incidents', libelle: 'Incidents', compte: this.incidents().length },
    { id: 'mandats', libelle: 'Mandats', compte: this.mandats().length },
  ]);

  protected readonly droitDeDelivrer = computed(() => this.droits.peut('CHEQUE_BOOK_ISSUE'));
  protected readonly droitDePayer = computed(() => this.droits.peut('CHEQUE_PAY'));
  protected readonly droitDOpposer = computed(() => this.droits.peut('CHEQUE_STOP'));
  protected readonly droitDEnregistrer = computed(() => this.droits.peut('MANDATE_REGISTER'));
  protected readonly droitDeRevoquer = computed(() => this.droits.peut('MANDATE_REVOKE'));

  protected readonly chequierADeux = computed(
    () => this.droits.annonceDeuxRegards('CHEQUE_BOOK_ISSUE'));
  protected readonly mandatADeux = computed(
    () => this.droits.annonceDeuxRegards('MANDATE_REGISTER'));

  /** Le plafond du profil sur le paiement d'un chèque, annoncé avant la saisie. */
  protected readonly plafondPaiement = computed(
    () => this.droits.plafond('CHEQUE_PAY', this.devise()));

  protected readonly depasseLePlafond = computed(
    () => this.droits.depasse('CHEQUE_PAY', this.montant(), this.devise()));

  protected readonly chequesVisibles = computed(() => {
    const filtre = this.filtreCheque();
    return filtre === null ? this.cheques() : this.cheques().filter((c) => c.status === filtre);
  });

  /** Ce qui engage encore le compte : le chiffre qu'un client demande au téléphone. */
  protected readonly enCirculation = computed(
    () => this.cheques().filter((c) => c.status === 'UNUSED').length);

  protected readonly actesDuCheque = computed(() => {
    const cheque = this.chequeChoisi();
    if (cheque === null) {
      return [];
    }
    return actesSurCheque(cheque).filter(
      (acte) => (acte === 'PAYER' ? this.droitDePayer() : this.droitDOpposer()));
  });

  protected readonly obstaclesChequier = computed(
    () => obstaclesAuChequier({ count: this.nombreDeCheques() }));

  protected readonly obstaclesPaiement = computed(() => {
    const cheque = this.chequeChoisi();
    if (cheque === null) {
      return ['Aucun chèque choisi.'];
    }
    return obstaclesAuPaiement({
      number: cheque.number,
      amount: this.montant() === null ? '' : String(this.montant()),
      currency: this.devise(),
      mode: this.mode(),
      nostroAccountId: this.nostro().trim() || null,
      beneficiary: this.porteur().trim() || null,
    });
  });

  protected readonly demandeMandat = computed<DemandeMandat>(() => ({
    reference: this.reference(),
    creditorId: this.identifiantCreancier(),
    creditorName: this.nomCreancier(),
    creditorAccountId: this.creancierInterne() ? this.compteCreancier().trim() || null : null,
    creditorBank: this.creancierInterne() ? null : this.banqueCreancier().trim() || null,
    creditorAccount: this.creancierInterne() ? null : this.ibanCreancier().trim() || null,
    signedOn: this.signeLe(),
    validFrom: this.effetLe(),
    validTo: this.finLe(),
    maxAmount: this.plafond() === null ? null : String(this.plafond()),
    currency: this.devise(),
  }));

  protected readonly obstaclesMandat = computed(() => obstaclesAuMandat(this.demandeMandat()));

  constructor() {
    effect(() => {
      const compte = this.compteId();
      if (compte !== '') {
        void this.charger(compte);
      }
    });
  }

  protected async charger(compte = this.compteId()): Promise<void> {
    if (compte === '') {
      return;
    }
    this.phase.set('chargement');
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [chequiers, cheques, incidents, mandats] = await Promise.all([
        this.paiements.chequiers(entite, compte),
        this.paiements.cheques(entite, compte, null),
        this.paiements.incidents(entite, compte),
        this.paiements.mandats(entite, compte),
      ]);
      this.chequiers.set(chequiers);
      this.cheques.set(cheques);
      this.incidents.set(incidents);
      this.mandats.set(mandats);
      const ouvert = this.chequeChoisi();
      if (ouvert !== null) {
        this.chequeChoisi.set(cheques.find((c) => c.number === ouvert.number) ?? null);
      }
      const mandat = this.mandatChoisi();
      if (mandat !== null) {
        this.mandatChoisi.set(mandats.find((m) => m.id === mandat.id) ?? null);
      }
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- chéquiers

  protected ouvrirLaDemande(): void {
    this.saisieChequier.set(true);
    this.nombreDeCheques.set(25);
    this.acquitte.set(null);
    this.refus.set(null);
  }

  protected fermerLaDemande(): void {
    this.saisieChequier.set(false);
  }

  protected async delivrer(): Promise<void> {
    if (this.obstaclesChequier().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.paiements.delivrerChequier(this.config.legalEntityId(), this.compteId(),
                                            { count: this.nombreDeCheques() });
      this.saisieChequier.set(false);
      // « Demandé », pas « délivré » : le carnet n'existera qu'après le second
      // regard, et le client qui l'attend au guichet doit l'apprendre ici.
      this.acquitte.set(`Chéquier de ${this.nombreDeCheques()} chèques demandé. Il sera délivré `
                        + "quand un second l'aura validé ; les numéros sont attribués à ce "
                        + 'moment-là.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- chèques

  protected filtrerCheques(statut: StatutCheque | null): void {
    this.filtreCheque.set(statut);
  }

  protected ouvrirCheque(cheque: Cheque): void {
    this.chequeChoisi.set(cheque);
    this.acteCheque.set(null);
    this.acquitte.set(null);
    this.refus.set(null);
  }

  protected demarrerSurCheque(acte: 'PAYER' | 'OPPOSER'): void {
    this.acteCheque.set(acte);
    this.montant.set(null);
    this.mode.set('CASH');
    this.nostro.set('');
    this.porteur.set('');
    this.motifOpposition.set('LOSS');
  }

  protected abandonnerLActe(): void {
    this.acteCheque.set(null);
  }

  protected async payer(): Promise<void> {
    const cheque = this.chequeChoisi();
    if (cheque === null || this.obstaclesPaiement().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const paye = await this.paiements.payerCheque(this.config.legalEntityId(), this.compteId(), {
        number: cheque.number,
        amount: String(this.montant()),
        currency: this.devise(),
        mode: this.mode(),
        nostroAccountId: this.nostro().trim() || null,
        beneficiary: this.porteur().trim() || null,
      }, crypto.randomUUID());
      this.chequeChoisi.set(paye);
      this.acteCheque.set(null);
      this.acquitte.set(`Chèque n° ${paye.number} payé et comptabilisé.`);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async opposer(): Promise<void> {
    const cheque = this.chequeChoisi();
    if (cheque === null) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const oppose = await this.paiements.opposer(this.config.legalEntityId(), this.compteId(),
                                                  { number: cheque.number,
                                                    reason: this.motifOpposition() });
      this.chequeChoisi.set(oppose);
      this.acteCheque.set(null);
      this.acquitte.set(`Opposition enregistrée sur le chèque n° ${oppose.number} : `
                        + `${LIBELLE_MOTIF_OPPOSITION[this.motifOpposition()].toLowerCase()}. `
                        + 'Aucun paiement ne passera plus sur ce numéro.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- mandats

  protected ouvrirLeMandat(): void {
    this.saisieMandat.set(true);
    this.mandatChoisi.set(null);
    this.acquitte.set(null);
    this.refus.set(null);
    this.reference.set('');
    this.identifiantCreancier.set('');
    this.nomCreancier.set('');
    this.creancierInterne.set(false);
    this.compteCreancier.set('');
    this.banqueCreancier.set('');
    this.ibanCreancier.set('');
    this.signeLe.set(null);
    this.effetLe.set(null);
    this.finLe.set(null);
    this.plafond.set(null);
  }

  protected fermerLeMandat(): void {
    this.saisieMandat.set(false);
  }

  protected async enregistrerLeMandat(): Promise<void> {
    if (this.obstaclesMandat().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.paiements.enregistrerMandat(this.config.legalEntityId(), this.compteId(),
                                             this.demandeMandat());
      this.saisieMandat.set(false);
      this.acquitte.set(`Mandat ${this.reference()} demandé. Il n'autorisera aucun prélèvement `
                        + "tant qu'un second ne l'aura pas validé.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected choisirMandat(mandat: Mandat): void {
    this.mandatChoisi.set(mandat);
    this.saisieMandat.set(false);
    this.motifRevocation.set('');
    this.acquitte.set(null);
    this.refus.set(null);
  }

  protected peutRevoquer(mandat: Mandat): boolean {
    return peutRevoquer(mandat) && this.droitDeRevoquer();
  }

  protected async revoquer(): Promise<void> {
    const mandat = this.mandatChoisi();
    if (mandat === null || !this.motifRevocation().trim()) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const revoque = await this.paiements.revoquerMandat(
        this.config.legalEntityId(), mandat.id, this.motifRevocation().trim());
      this.mandatChoisi.set(revoque);
      this.acquitte.set(`Mandat ${revoque.reference} révoqué. Une présentation du créancier sera `
                        + 'désormais rejetée.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  // ---------------------------------------------------------------- présentation

  protected etatDuChequier(chequier: Chequier): EtatOperation {
    return chequier.status === 'ACTIVE' ? 'approuve' : 'contre-passe';
  }

  protected etatDuCheque(cheque: Cheque): EtatOperation {
    switch (cheque.status) {
      case 'UNUSED':
        return 'en-attente';
      case 'PAID':
        return 'comptabilise';
      case 'STOPPED':
        return 'bloque';
      default:
        return 'rejete';
    }
  }

  protected etatDuMandat(mandat: Mandat): EtatOperation {
    return mandat.status === 'ACTIVE' ? 'approuve' : 'contre-passe';
  }

  /**
   * Le motif d'opposition en clair.
   *
   * Le socle rend une chaîne, pas un des quatre motifs : une opposition posée
   * par un autre canal peut en porter un que cette version ne connaît pas. On
   * traduit ce qu'on sait traduire et on montre le reste tel quel, plutôt que
   * d'afficher un vide là où il y a une raison.
   */
  protected libelleOpposition(cheque: Cheque): string {
    const motif = cheque.stopReason;
    if (motif === null) {
      return '—';
    }
    return LIBELLE_MOTIF_OPPOSITION[motif as MotifOpposition] ?? motif;
  }

  /** Le créancier, tel qu'on le désigne à l'écran : un compte chez vous, ou ailleurs. */
  protected creancierDe(mandat: Mandat): string {
    return mandat.creditorAccountId !== null
      ? 'compte de la banque'
      : `${mandat.creditorBank ?? '—'} · ${mandat.creditorAccount ?? '—'}`;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
