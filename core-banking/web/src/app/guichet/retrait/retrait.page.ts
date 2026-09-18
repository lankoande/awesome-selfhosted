import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { formaterMontant } from '../../core/format/montant';
import { AppConfig } from '../../core/config/runtime-config';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDrawer, CbField, CbInput,
  CbKbd, CbNotice, CbSection, CbStateBadge, CbToolbar, EtatOperation,
} from '../../ui';
import { BandeauClient } from '../composants/bandeau-client';
import { Billetage } from '../composants/billetage';
import { ContexteCompteTiroir } from '../composants/contexte-compte';
import { Imputation } from '../composants/imputation';
import { Comptage, coupuresDe, totalComptage } from '../modele/coupures';
import { ContexteCompte, SoldeCompte } from '../modele/guichet.modele';
import { GUICHET } from '../guichet.port';
import { Soumission } from '../soumission';

type Porteur = 'titulaire' | 'mandataire';

const LIBELLE_MAX = 140;

/**
 * Guichet — retrait d'espèces.
 *
 * Le versement et le retrait se ressemblent, et se jouent sur une différence
 * qui coûte cher quand on la rate : **c'est le disponible qui commande, pas le
 * solde comptable**. Un blocage retient une part du solde ; un guichetier qui
 * refuse un retrait sans pouvoir dire pourquoi, c'est un incident client.
 *
 * Le poste vérifie ce qu'il sait — le montant demandé dépasse-t-il le
 * disponible qu'on lui a donné — et laisse le socle décider du reste : les
 * frais s'ajoutent au débit, et seul le socle connaît le barème.
 */
@Component({
  selector: 'cb-retrait',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BandeauClient, Billetage, Imputation,
    CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbKbd,
    CbNotice, CbSection, CbStateBadge, CbToolbar,
  ],
  templateUrl: './retrait.page.html',
  styleUrl: '../versement/versement.page.css',
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class Retrait {
  private readonly guichet = inject(GUICHET);
  private readonly tiroir = inject(CbDrawer);
  protected readonly config = inject(AppConfig);

  protected readonly catalogue = signal<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>([]);
  protected readonly compteId = signal<string>('');
  protected readonly solde = signal<SoldeCompte | null>(null);
  protected readonly contexte = signal<ContexteCompte | null>(null);

  private readonly droits = inject(Droits);

  protected readonly montant = signal<number | null>(null);
  protected readonly comptage = signal<Comptage>({});
  protected readonly porteur = signal<Porteur>('titulaire');
  protected readonly identite = signal('');
  protected readonly libelle = signal('');

  protected readonly soumission = new Soumission(() => this.empreinte());
  protected readonly phase = this.soumission.phase;
  protected readonly recu = this.soumission.recu;
  protected readonly attente = this.soumission.attente;
  protected readonly refus = this.soumission.refus;
  protected readonly cleAffichee = this.soumission.cleAffichee;
  protected readonly cleEtat = this.soumission.cleEtat;
  protected readonly enCours = this.soumission.enCours;
  protected readonly termine = this.soumission.termine;

  protected readonly devise = computed(() => this.solde()?.currency ?? this.config.valeur().affichage.deviseParDefaut);
  protected readonly coupures = computed(() => coupuresDe(this.devise()));
  protected readonly compteActif = computed(() => this.solde()?.status === 'ACTIVE');
  protected readonly disponible = computed(() => Number(this.solde()?.available.amount ?? 0));
  protected readonly retenu = computed(() => {
    const solde = this.solde();
    return solde ? Number(solde.current.amount) - Number(solde.available.amount) : 0;
  });

  protected readonly ecartBilletage = computed(() => {
    const annonce = this.montant();
    const compte = totalComptage(this.comptage(), this.coupures());
    if (annonce === null || compte === 0) return 0;
    return compte - annonce;
  });

  /**
   * Le poste ne bloque que sur ce qu'il sait avec certitude. Un montant
   * supérieur au disponible sera refusé quoi qu'il arrive : inutile de faire
   * l'aller-retour. En dessous, les frais peuvent encore faire basculer — et
   * c'est le socle qui tranche, pas le navigateur.
   */
  /**
   * Le plafond du profil sur cette opération, quand la politique en pose un.
   *
   * **Il s'annonce avant la saisie.** Apprendre son plafond dans un refus,
   * après avoir engagé le client, fait perdre deux minutes et la face.
   */
  protected readonly plafond = computed(() => this.droits.plafond('CASH_OPERATION', this.devise()));

  /** Le plafond en opération déplacée, plus bas : le dossier n'est pas sous les yeux. */
  protected readonly plafondDeplace = computed(
    () => this.droits.plafond('CASH_OPERATION', this.devise(), true));
  /**
   * Ce qu'on dit sous le champ du montant : la devise, et le plafond quand il
   * y en a un. Les deux comptent avant la frappe, pas après.
   */
  protected readonly aideMontant = computed(() => {
    const parties = ["Les frais et la taxe s'ajoutent au débit : le socle vérifie le disponible, commission comprise."];
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

  protected readonly obstacles = computed<readonly string[]>(() => {
    const obstacles: string[] = [];
    if (!this.compteActif()) obstacles.push("Le compte n'est pas actif : aucune opération n'est acceptée.");
    if ((this.montant() ?? 0) <= 0) obstacles.push('Le montant à retirer est obligatoire.');
    else if ((this.montant() ?? 0) > this.disponible()) {
      obstacles.push('Le montant dépasse le disponible : le socle refusera.');
    }
    if (this.ecartBilletage() !== 0) obstacles.push('Le comptage ne retrouve pas le montant annoncé.');
    if (this.porteur() === 'mandataire' && this.identite().trim().length < 3) {
      obstacles.push("Un retrait par un mandataire exige son identité et sa pièce.");
    }
    // Le plafond en agence est le plus favorable des deux : bloquer dessus ne
    // peut jamais refuser à tort. Le socle tranche toujours — il connaît
    // l'agence du compte, que le poste ignore.
    if (this.droits.depasse('CASH_OPERATION', this.montant(), this.devise())) {
      obstacles.push('Le montant dépasse votre plafond de '
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

  private async demarrer(): Promise<void> {
    const liste = (await this.guichet.catalogue?.()) ?? [];
    this.catalogue.set(liste);
    const premier = liste[0]?.accountId ?? '';
    if (premier) await this.choisirCompte(premier);
    else this.soumission.reinitialiser();
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
    await this.soumission.envoyer((cle) => this.guichet.retirer(this.demande(cle)));
  }

  protected async reessayer(): Promise<void> {
    await this.soumission.reessayer((cle) => this.guichet.retirer(this.demande(cle)));
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

  protected nouveau(): void {
    this.viderLaSaisie();
    this.soumission.reinitialiser();
  }

  protected ouvrirContexte(): void {
    const contexte = this.contexte();
    const solde = this.solde();
    if (!contexte || !solde) return;
    this.tiroir.ouvrir(ContexteCompteTiroir, { donnees: { contexte, solde }, etiquette: 'Contexte du compte' });
  }

  protected imprimerRecu(): void {
    window.print();
  }

  private viderLaSaisie(): void {
    this.montant.set(null);
    this.comptage.set({});
    this.porteur.set('titulaire');
    this.identite.set('');
    this.libelle.set('');
  }

  private empreinte(): string {
    return JSON.stringify([this.compteId(), this.montant(), this.narratif()]);
  }

  /** Le mandataire figure au libellé : c'est ce qu'une écriture de retrait porte. */
  protected narratif(): string {
    const morceaux = ["Retrait d'espèces"];
    if (this.porteur() === 'mandataire' && this.identite().trim()) {
      morceaux.push(`retiré par ${this.identite().trim()}`);
    }
    if (this.libelle().trim()) morceaux.push(this.libelle().trim());
    return morceaux.join(' — ').slice(0, LIBELLE_MAX);
  }

  protected jour(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }

  protected majMontant(valeur: number | null): void {
    this.montant.set(valeur);
  }

  protected majPorteur(valeur: string): void {
    this.porteur.set(valeur === 'mandataire' ? 'mandataire' : 'titulaire');
  }

  protected majIdentite(valeur: string): void {
    this.identite.set(valeur);
  }

  protected majLibelle(valeur: string): void {
    this.libelle.set(valeur);
  }
}
