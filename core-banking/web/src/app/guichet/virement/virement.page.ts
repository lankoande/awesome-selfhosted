import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterMontant } from '../../core/format/montant';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDrawer, CbField, CbInput,
  CbKbd, CbNotice, CbSection, CbStateBadge, CbToolbar, EtatOperation,
} from '../../ui';
import { BandeauClient } from '../composants/bandeau-client';
import { ContexteCompteTiroir } from '../composants/contexte-compte';
import { Imputation } from '../composants/imputation';
import { ContexteCompte, SoldeCompte } from '../modele/guichet.modele';
import { GUICHET } from '../guichet.port';
import { Soumission } from '../soumission';

const LIBELLE_MAX = 140;

/**
 * Guichet — virement interne.
 *
 * Une seule écriture, deux comptes : le socle débite l'un et crédite l'autre
 * dans la même transaction, il n'y a jamais un instant où l'argent n'est nulle
 * part. L'écran ne fait donc jamais deux appels.
 *
 * Comme au retrait, c'est le **disponible du débiteur** qui commande. Et comme
 * partout, le poste n'empêche que ce qui est certain : deux fois le même
 * compte, deux devises différentes — le reste appartient au socle.
 */
@Component({
  selector: 'cb-virement',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BandeauClient, Imputation,
    CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbInput, CbKbd,
    CbNotice, CbSection, CbStateBadge, CbToolbar,
  ],
  templateUrl: './virement.page.html',
  styleUrl: '../versement/versement.page.css',
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class Virement {
  private readonly guichet = inject(GUICHET);
  private readonly tiroir = inject(CbDrawer);
  protected readonly config = inject(AppConfig);

  protected readonly catalogue = signal<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>([]);
  protected readonly sourceId = signal<string>('');
  protected readonly destinationId = signal<string>('');
  protected readonly source = signal<SoldeCompte | null>(null);
  protected readonly destination = signal<SoldeCompte | null>(null);
  protected readonly contexte = signal<ContexteCompte | null>(null);

  private readonly droits = inject(Droits);

  protected readonly montant = signal<number | null>(null);
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

  protected readonly devise = computed(() => this.source()?.currency ?? this.config.valeur().affichage.deviseParDefaut);
  protected readonly disponible = computed(() => Number(this.source()?.available.amount ?? 0));
  protected readonly sourceActive = computed(() => this.source()?.status === 'ACTIVE');
  protected readonly intituleDestination = computed(() => {
    const choisi = this.catalogue().find((c) => c.accountId === this.destinationId());
    return choisi?.intitule ?? '—';
  });
  protected readonly codeDestination = computed(() => this.destination()?.code ?? '—');
  /** Un montant affiché passe toujours par le formateur : jamais de chiffres collés. */
  protected readonly aideDisponible = computed(
    () => `Disponible : ${formaterMontant(this.disponible())} ${this.devise()}`,
  );

  /**
   * Le plafond du profil sur cette opération, quand la politique en pose un.
   *
   * **Il s'annonce avant la saisie.** Apprendre son plafond dans un refus,
   * après avoir engagé le client, fait perdre deux minutes et la face.
   */
  protected readonly plafond = computed(() => this.droits.plafond('TRANSFER', this.devise()));

  /** Le plafond en opération déplacée, plus bas : le dossier n'est pas sous les yeux. */
  protected readonly plafondDeplace = computed(
    () => this.droits.plafond('TRANSFER', this.devise(), true));
  /**
   * Ce qu'on dit sous le champ du montant : la devise, et le plafond quand il
   * y en a un. Les deux comptent avant la frappe, pas après.
   */
  protected readonly aideMontant = computed(() => {
    const parties = ["Les frais et la taxe s'ajoutent au débit du donneur d'ordre."];
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
    if (!this.sourceActive()) obstacles.push("Le compte à débiter n'est pas actif.");
    if (this.destination() && this.destination()!.status !== 'ACTIVE') {
      obstacles.push("Le compte à créditer n'est pas actif.");
    }
    if (this.sourceId() && this.sourceId() === this.destinationId()) {
      obstacles.push('Le débiteur et le bénéficiaire sont le même compte.');
    }
    const devises = this.source() && this.destination()
      && this.source()!.currency !== this.destination()!.currency;
    if (devises) {
      obstacles.push('Les deux comptes ne sont pas dans la même devise : un virement ne fait pas le change.');
    }
    if ((this.montant() ?? 0) <= 0) obstacles.push('Le montant est obligatoire.');
    else if ((this.montant() ?? 0) > this.disponible()) {
      obstacles.push('Le montant dépasse le disponible du débiteur : le socle refusera.');
    }
    // Le plafond en agence est le plus favorable des deux : bloquer dessus ne
    // peut jamais refuser à tort. Le socle tranche toujours — il connaît
    // l'agence du compte, que le poste ignore.
    if (this.droits.depasse('TRANSFER', this.montant(), this.devise())) {
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
      default: return this.sourceActive() ? 'brouillon' : 'bloque';
    }
  });

  constructor() {
    void this.demarrer();
  }

  private async demarrer(): Promise<void> {
    const liste = (await this.guichet.catalogue?.()) ?? [];
    this.catalogue.set(liste);
    if (liste.length >= 2) {
      this.destinationId.set(liste[1]!.accountId);
      await this.choisirSource(liste[0]!.accountId);
      await this.chargerDestination();
    } else {
      this.soumission.reinitialiser();
    }
  }

  protected async choisirSource(accountId: string): Promise<void> {
    this.sourceId.set(accountId);
    this.viderLaSaisie();
    this.soumission.reinitialiser('chargement');
    const entite = this.config.legalEntityId();
    try {
      const [solde, contexte] = await Promise.all([
        this.guichet.soldes(entite, accountId),
        this.guichet.contexte(entite, accountId),
      ]);
      this.source.set(solde);
      this.contexte.set(contexte);
      this.phase.set('saisie');
    } catch (erreur) {
      this.source.set(null);
      this.contexte.set(null);
      this.soumission.poserRefus(erreur);
    }
  }

  protected async choisirDestination(accountId: string): Promise<void> {
    this.destinationId.set(accountId);
    await this.chargerDestination();
  }

  private async chargerDestination(): Promise<void> {
    const accountId = this.destinationId();
    if (!accountId) return;
    try {
      this.destination.set(await this.guichet.soldes(this.config.legalEntityId(), accountId));
    } catch {
      // Le bénéficiaire ne se lit pas : on n'invente rien, l'obstacle se verra
      // à l'envoi, dans le refus du socle.
      this.destination.set(null);
    }
  }

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    await this.soumission.envoyer((cle) => this.guichet.virer(this.demande(cle)));
  }

  protected async reessayer(): Promise<void> {
    await this.soumission.reessayer((cle) => this.guichet.virer(this.demande(cle)));
  }

  private demande(cleIdempotence: string) {
    return {
      legalEntityId: this.config.legalEntityId(),
      sourceAccountId: this.sourceId(),
      destinationAccountId: this.destinationId(),
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
    const solde = this.source();
    if (!contexte || !solde) return;
    this.tiroir.ouvrir(ContexteCompteTiroir, { donnees: { contexte, solde }, etiquette: 'Contexte du compte' });
  }

  protected imprimerRecu(): void {
    window.print();
  }

  private viderLaSaisie(): void {
    this.montant.set(null);
    this.libelle.set('');
  }

  private empreinte(): string {
    return JSON.stringify([this.sourceId(), this.destinationId(), this.montant(), this.narratif()]);
  }

  protected narratif(): string {
    const morceaux = ['Virement interne'];
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

  protected majLibelle(valeur: string): void {
    this.libelle.set(valeur);
  }
}
