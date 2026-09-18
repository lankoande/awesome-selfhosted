import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { formaterMontant } from '../../core/format/montant';
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
import { ContexteCompte, SoldeCompte } from '../modele/guichet.modele';
import { GUICHET } from '../guichet.port';
import { Soumission } from '../soumission';

type Remettant = 'titulaire' | 'tiers';

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
  private readonly droits = inject(Droits);

  protected readonly montant = signal<number | null>(null);
  protected readonly comptage = signal<Comptage>({});
  protected readonly remettant = signal<Remettant>('titulaire');
  protected readonly identite = signal('');
  protected readonly libelle = signal('');

  // -------------------------------------------------- l'issue et la clé
  protected readonly soumission = new Soumission(() => this.empreinte());
  protected readonly phase = this.soumission.phase;
  protected readonly recu = this.soumission.recu;
  protected readonly attente = this.soumission.attente;
  protected readonly refus = this.soumission.refus;
  protected readonly cleAffichee = this.soumission.cleAffichee;
  protected readonly cleEtat = this.soumission.cleEtat;

  protected readonly devise = computed(() => this.solde()?.currency ?? this.config.valeur().affichage.deviseParDefaut);
  protected readonly coupures = computed(() => coupuresDe(this.devise()));
  protected readonly compteActif = computed(() => this.solde()?.status === 'ACTIVE');
  protected readonly enCours = this.soumission.enCours;
  protected readonly termine = this.soumission.termine;

  /** Ce qui identifie la demande : deux saisies différentes, deux clés. */
  private empreinte(): string {
    return JSON.stringify([this.compteId(), this.montant(), this.narratif()]);
  }

  protected readonly ecartBilletage = computed(() => {
    const annonce = this.montant();
    const compte = totalComptage(this.comptage(), this.coupures());
    if (annonce === null || compte === 0) return 0;
    return compte - annonce;
  });

  /**
   * Le plafond du profil sur cette opération, quand la politique en pose un.
   *
   * **Il s'annonce avant la saisie.** Un guichetier qui apprend son plafond
   * dans un refus, après avoir compté les billets, a perdu deux minutes et la
   * face devant le client.
   */
  protected readonly plafond = computed(() => this.droits.plafond('CASH_OPERATION', this.devise()));

  /** Le plafond en opération déplacée, plus bas : le dossier n'est pas là. */
  protected readonly plafondDeplace = computed(
    () => this.droits.plafond('CASH_OPERATION', this.devise(), true));
  /**
   * Ce qu'on dit sous le champ du montant : la devise, et le plafond quand il
   * y en a un. Les deux comptent avant la frappe, pas après.
   */
  protected readonly aideMontant = computed(() => {
    const parties = [`Devise du compte : ${this.devise()}`];
    const limite = this.plafond();
    if (limite) {
      parties.push(`plafond de votre profil : `
        + `${formaterMontant(Number(limite.amount))} ${this.devise()}`);
      const deplace = this.plafondDeplace();
      if (deplace && deplace.amount !== limite.amount) {
        parties.push(`${formaterMontant(Number(deplace.amount))} hors de votre agence`);
      }
    }
    return parties.join(' — ');
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
    // Le plafond en agence est le plus favorable des deux : bloquer dessus ne
    // peut jamais refuser à tort. Le socle tranche toujours — il connaît
    // l'agence du compte, que le poste ignore.
    if (this.droits.depasse('CASH_OPERATION', this.montant(), this.devise())) {
      obstacles.push(`Le montant dépasse votre plafond de `
        + `${formaterMontant(Number(this.plafond()?.amount ?? 0))} ${this.devise()} pour cette opération.`);
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
    this.viderLaSaisie();
    this.soumission.reinitialiser('chargement');
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
      this.soumission.poserRefus(erreur);
    }
  }

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    await this.soumission.envoyer((cle) => this.guichet.verser(this.demande(cle)));
  }

  protected async reessayer(): Promise<void> {
    await this.soumission.reessayer((cle) => this.guichet.verser(this.demande(cle)));
  }

  private demande(cleIdempotence: string) {
    return {
      legalEntityId: this.config.legalEntityId(),
      accountId: this.compteId(),
      amount: String(this.montant() ?? 0),
      currency: this.devise(),
      channel: 'BRANCH',
      narrative: this.narratif(),
      cleIdempotence,
    };
  }

  /** Nouvelle opération : nouvelle clé, saisie vidée, compte conservé. */
  protected nouveau(): void {
    this.viderLaSaisie();
    this.soumission.reinitialiser();
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

  private viderLaSaisie(): void {
    this.montant.set(null);
    this.comptage.set({});
    this.remettant.set('titulaire');
    this.identite.set('');
    this.libelle.set('');
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
