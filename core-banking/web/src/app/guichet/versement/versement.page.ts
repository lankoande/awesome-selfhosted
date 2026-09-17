import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import {
  CbActivity,
  CbAmountInput,
  CbButton,
  CbDrawer,
  CbField,
  CbInput,
  CbKbd,
  CbNotice,
  CbSection,
  CbStateBadge,
  CbToolbar,
  EtatOperation,
} from '../../ui';
import { BandeauClient } from '../composants/bandeau-client';
import { Billetage } from '../composants/billetage';
import { ContexteCompteTiroir } from '../composants/contexte-compte';
import { Imputation } from '../composants/imputation';
import { Comptage, coupuresDe, totalComptage } from '../modele/coupures';
import { ContexteCompte, IssueVersement, Recu, RefusMetier, SoldeCompte } from '../modele/guichet.modele';
import { GUICHET } from '../guichet.port';

type Remettant = 'titulaire' | 'tiers';
type Phase = 'chargement' | 'saisie' | 'envoi' | 'comptabilise' | 'en-attente' | 'refuse';

const LIBELLE_MAX = 140;

/**
 * Guichet — versement d'espèces.
 *
 * L'écran tient trois promesses du document de décisions :
 *   le récapitulatif d'imputation montré avant de valider, sans jamais
 *   recalculer un barème qui appartient au socle ;
 *   la clé d'idempotence conservée, pour qu'un réseau qui tombe ne se solde
 *   jamais par un double versement ni par une re-saisie à l'aveugle ;
 *   un refus lisible, à une place fixe, avec sa raison et la suite à donner.
 */
@Component({
  selector: 'cb-versement',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BandeauClient, Billetage, Imputation,
    CbActivity, CbAmountInput, CbButton, CbField, CbInput, CbKbd,
    CbNotice, CbSection, CbStateBadge, CbToolbar,
  ],
  templateUrl: './versement.page.html',
  styleUrl: './versement.page.css',
  // Un raccourci annoncé à l'écran doit exister. Il est posé sur l'écran, pas
  // sur la fenêtre : un raccourci global déclencherait aussi depuis un tiroir.
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class Versement {
  private readonly guichet = inject(GUICHET);
  private readonly tiroir = inject(CbDrawer);
  protected readonly config = inject(AppConfig);

  // ------------------------------------------------------------- le compte
  protected readonly catalogue = signal<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>([]);
  protected readonly compteId = signal<string>('');
  protected readonly solde = signal<SoldeCompte | null>(null);
  protected readonly contexte = signal<ContexteCompte | null>(null);

  // -------------------------------------------------------------- la saisie
  protected readonly montant = signal<number | null>(null);
  protected readonly comptage = signal<Comptage>({});
  protected readonly remettant = signal<Remettant>('titulaire');
  protected readonly identite = signal('');
  protected readonly libelle = signal('');

  // -------------------------------------------------------------- l'issue
  protected readonly phase = signal<Phase>('chargement');
  protected readonly recu = signal<Recu | null>(null);
  protected readonly attente = signal<{ operationId: string; attenduDe: string } | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);

  // -------------------------------------------------------- l'idempotence
  private readonly cle = signal<string>(crypto.randomUUID());
  private readonly empreinteDeLaCle = signal<string | null>(null);

  protected readonly devise = computed(() => this.solde()?.currency ?? this.config.valeur().affichage.deviseParDefaut);
  protected readonly coupures = computed(() => coupuresDe(this.devise()));
  protected readonly compteActif = computed(() => this.solde()?.status === 'ACTIVE');
  protected readonly enCours = computed(() => this.phase() === 'envoi' || this.phase() === 'chargement');
  protected readonly termine = computed(() => this.phase() === 'comptabilise' || this.phase() === 'en-attente');

  /**
   * L'empreinte de la demande. La clé d'idempotence couvre *une* demande :
   * si la saisie change, la clé doit changer, sinon le socle rejouerait le
   * premier reçu et le guichetier croirait avoir passé la nouvelle opération.
   */
  private readonly empreinte = computed(() =>
    JSON.stringify([this.compteId(), this.montant(), this.narratif()]),
  );

  protected readonly cleAffichee = computed(() => (this.empreinteDeLaCle() === null ? null : this.cle()));
  protected readonly cleEtat = computed(() => {
    if (this.empreinteDeLaCle() === null) return "générée à l'envoi";
    return this.empreinteDeLaCle() === this.empreinte() ? 'conservée' : 'renouvelée — la saisie a changé';
  });

  protected readonly ecartBilletage = computed(() => {
    const annonce = this.montant();
    const compte = totalComptage(this.comptage(), this.coupures());
    if (annonce === null || compte === 0) return 0;
    return compte - annonce;
  });

  /** Ce que l'écran empêche est ergonomique ; ce qui est interdit, le socle le refuse. */
  protected readonly obstacles = computed<readonly string[]>(() => {
    const obstacles: string[] = [];
    if (!this.compteActif()) obstacles.push("Le compte n'est pas actif : aucune opération n'est acceptée.");
    if ((this.montant() ?? 0) <= 0) obstacles.push('Le montant remis est obligatoire.');
    if (this.ecartBilletage() !== 0) obstacles.push('Le comptage ne retrouve pas le montant annoncé.');
    if (this.remettant() === 'tiers' && this.identite().trim().length < 3) {
      obstacles.push("Un versement par un tiers exige l'identité du remettant.");
    }
    return obstacles;
  });

  protected readonly peutEnvoyer = computed(
    () => this.obstacles().length === 0 && (this.phase() === 'saisie' || this.phase() === 'refuse'),
  );

  protected readonly etatOperation = computed<EtatOperation>(() => {
    switch (this.phase()) {
      case 'comptabilise': return 'comptabilise';
      case 'en-attente': return 'en-attente';
      case 'refuse': return 'rejete';
      default: return this.compteActif() ? 'brouillon' : 'bloque';
    }
  });

  constructor() {
    void this.demarrer();
  }

  // ------------------------------------------------------------------ flux

  private async demarrer(): Promise<void> {
    const liste = (await this.guichet.catalogue?.()) ?? [];
    this.catalogue.set(liste);
    const premier = liste[0]?.accountId ?? '';
    if (premier) await this.choisirCompte(premier);
    else this.phase.set('saisie');
  }

  protected async choisirCompte(accountId: string): Promise<void> {
    this.compteId.set(accountId);
    this.phase.set('chargement');
    this.reinitialiser();
    const entite = this.config.legalEntityId();
    try {
      const [solde, contexte] = await Promise.all([
        this.guichet.soldes(entite, accountId),
        this.guichet.contexte(entite, accountId),
      ]);
      this.solde.set(solde);
      this.contexte.set(contexte);
      this.phase.set('saisie');
    } catch (erreur) {
      this.solde.set(null);
      this.contexte.set(null);
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    await this.soumettre(this.cleDeLaDemande());
  }

  /** « Réessayer » rejoue la même clé : c'est tout l'intérêt de l'avoir gardée. */
  protected async reessayer(): Promise<void> {
    await this.soumettre(this.cle());
  }

  private async soumettre(cle: string): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const issue = await this.guichet.verser({
        legalEntityId: this.config.legalEntityId(),
        accountId: this.compteId(),
        amount: String(this.montant() ?? 0),
        currency: this.devise(),
        channel: 'BRANCH',
        narrative: this.narratif(),
        cleIdempotence: cle,
      });
      this.accuser(issue);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  private accuser(issue: IssueVersement): void {
    if (issue.genre === 'comptabilise') {
      this.recu.set(issue.recu);
      this.phase.set('comptabilise');
      return;
    }
    this.attente.set({ operationId: issue.operationId, attenduDe: issue.attenduDe });
    this.phase.set('en-attente');
  }

  /** Nouvelle opération : nouvelle clé, saisie vidée, compte conservé. */
  protected nouveau(): void {
    this.reinitialiser();
    this.phase.set('saisie');
  }

  protected ouvrirContexte(): void {
    const contexte = this.contexte();
    const solde = this.solde();
    if (!contexte || !solde) return;
    this.tiroir.ouvrir(ContexteCompteTiroir, {
      donnees: { contexte, solde },
      etiquette: 'Contexte du compte',
    });
  }

  protected imprimerRecu(): void {
    window.print();
  }

  // --------------------------------------------------------------- détails

  private reinitialiser(): void {
    this.montant.set(null);
    this.comptage.set({});
    this.remettant.set('titulaire');
    this.identite.set('');
    this.libelle.set('');
    this.recu.set(null);
    this.attente.set(null);
    this.refus.set(null);
    this.cle.set(crypto.randomUUID());
    this.empreinteDeLaCle.set(null);
  }

  private cleDeLaDemande(): string {
    if (this.empreinteDeLaCle() !== this.empreinte()) {
      this.cle.set(crypto.randomUUID());
      this.empreinteDeLaCle.set(this.empreinte());
    }
    return this.cle();
  }

  /**
   * Le libellé d'écriture. Faute de champ dédié dans le contrat, l'identité du
   * remettant y figure : c'est une exigence LCB-FT, et c'est de toute façon ce
   * qu'un libellé d'écriture porte dans une agence.
   */
  protected narratif(): string {
    const morceaux = ["Versement d'espèces"];
    if (this.remettant() === 'tiers' && this.identite().trim()) {
      morceaux.push(`remis par ${this.identite().trim()}`);
    }
    if (this.libelle().trim()) morceaux.push(this.libelle().trim());
    return morceaux.join(' — ').slice(0, LIBELLE_MAX);
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }

  protected majMontant(valeur: number | null): void {
    this.montant.set(valeur);
  }

  protected jour(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }

  protected majRemettant(valeur: string): void {
    this.remettant.set(valeur === 'tiers' ? 'tiers' : 'titulaire');
  }

  protected majIdentite(valeur: string): void {
    this.identite.set(valeur);
  }

  protected majLibelle(valeur: string): void {
    this.libelle.set(valeur);
  }
}
