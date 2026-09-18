import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbField, CbInput, CbKbd, CbNotice, CbSection, CbStateBadge, CbToolbar,
  EtatOperation,
} from '../../ui';
import { CLIENTS } from '../clients.port';
import { IdentiteTiers } from '../composants/identite-tiers';
import {
  Dossier, IssueOuverture, ProduitOuvrable, Tiers, obstaclesAOuverture,
} from '../modele/clients.modele';

type Phase = 'chargement' | 'saisie' | 'envoi' | 'en-attente' | 'refuse';

/**
 * Ouvrir un compte à un client.
 *
 * **Une ouverture part toujours à la validation d'un second.** Le contrat ne
 * déclare qu'une seule issue favorable — 202 avec l'opération en attente — et
 * le contrôleur du socle est annoté `ACCEPTED` sans condition. L'écran l'annonce
 * donc *avant* l'envoi : promettre un compte immédiat, puis afficher « en
 * attente », est la manière la plus sûre de faire resaisir la demande.
 *
 * **Le socle n'honore pas de clé d'idempotence sur cette route** : elle ne
 * figure ni dans le contrat ni dans la signature du contrôleur (seules les
 * opérations de guichet la déclarent). L'écran se protège donc lui-même — il ne
 * propose aucun rejeu quand l'issue est incertaine (réseau coupé, 5xx) et
 * renvoie vers la file de validation, où la demande se vérifie.
 *
 * Ce que l'écran **ne fait pas** : composer un numéro de compte. Le contrat
 * l'accepte vide et le socle le compose selon le plan de numérotation de la
 * banque ; le laisser saisir au guichet produirait des numéros qui ne suivent
 * aucune règle.
 */
@Component({
  selector: 'cb-ouverture-compte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbButton, CbField, CbInput, CbKbd, CbNotice, CbSection, CbStateBadge,
    CbToolbar, IdentiteTiers,
  ],
  templateUrl: './ouverture.page.html',
  styleUrl: './ouverture.page.css',
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class OuvertureCompte {
  readonly id = input.required<string>();

  private readonly clients = inject(CLIENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly router = inject(Router);

  protected readonly tiers = signal<Tiers | null>(null);
  protected readonly dossier = signal<Dossier | null>(null);
  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly issue = signal<IssueOuverture | null>(null);

  /**
   * Vrai dès la première frappe. Un formulaire vierge ne reproche rien : lister
   * les manques avant que l'opérateur ait touché un champ, c'est le gronder
   * pour ce qu'il n'a pas encore eu l'occasion de faire.
   */
  protected readonly commence = signal(false);

  protected readonly produit = signal('');
  protected readonly devise = signal('');
  /** Les produits ouvrables, tels que le socle les rend. */
  protected readonly produits = signal<readonly ProduitOuvrable[]>([]);

  /**
   * Une clé par demande, envoyée bien que le socle l'ignore aujourd'hui : le
   * jour où cette route la reconnaîtra, le poste n'aura rien à changer. Tant
   * qu'elle n'est pas honorée, c'est {@link issueIncertaine} qui protège.
   */
  private readonly cle = signal(crypto.randomUUID());
  private readonly empreinteDeLaCle = signal('');

  /**
   * Le second regard n'est pas écrit en dur : il est lu de la politique.
   *
   * Le socle peut cesser de l'exiger. Une phrase codée en dur deviendrait alors
   * fausse sans que rien ne le signale, et c'est une phrase qui engage : elle
   * dit à l'opérateur que rien n'est fait tant qu'un second n'a pas approuvé.
   *
   * On *annonce* plutôt qu'on ne *sait* : tant que le socle n'a rien dit, on
   * prévient. Se taire à tort ferait annoncer au client un compte qui n'existe
   * pas encore.
   */
  protected readonly ouvertureADeuxRegards = computed(
    () => this.droits.annonceDeuxRegards('ACCOUNT_OPEN'));

  protected readonly obstacles = computed(() => {
    const t = this.tiers();
    return t ? obstaclesAOuverture(t, this.dossier()) : [];
  });

  /** Ce que l'écran empêche est ergonomique ; ce qui est interdit, le socle le refuse. */
  /**
   * Choisir un produit fixe la devise : elle appartient au produit, pas à la
   * saisie. Laisser les deux libres produirait des couples impossibles, refusés
   * par le socle après que le client a signé.
   */
  protected choisirProduit(code: string): void {
    this.commence.set(true);
    this.produit.set(code);
    const choisi = this.produits().find((p) => p.code === code);
    if (choisi?.devise) this.devise.set(choisi.devise);
  }

  /** La devise se saisit tant que le catalogue ne la donne pas. */
  protected readonly deviseImposee = computed(() => {
    const choisi = this.produits().find((p) => p.code === this.produit());
    return choisi?.devise != null && choisi.devise !== '';
  });

  protected readonly manques = computed(() => {
    const manques: string[] = [];
    if (!this.produit().trim()) manques.push('Le code produit est obligatoire.');
    if (!/^[A-Z]{3}$/.test(this.devise().trim().toUpperCase())) {
      manques.push('La devise est un code ISO de trois lettres.');
    }
    return manques;
  });

  protected readonly peutEnvoyer = computed(() =>
    this.manques().length === 0
    && this.obstacles().length === 0
    && (this.phase() === 'saisie' || this.phase() === 'refuse'));

  /**
   * L'envoi s'est-il perdu sans qu'on sache s'il a été enregistré ? Réseau
   * coupé (0) ou panne du socle (5xx) : la demande a pu être écrite. Sans clé
   * d'idempotence honorée, rejouer ouvrirait un second compte au même client.
   */
  protected readonly issueIncertaine = computed(() => {
    const r = this.refus();
    return r !== null && (r.statut === 0 || r.statut >= 500);
  });

  protected readonly etat = computed<EtatOperation>(() => {
    switch (this.phase()) {
      case 'en-attente': return 'en-attente';
      case 'refuse': return 'rejete';
      default: return 'brouillon';
    }
  });

  constructor() {
    this.devise.set(this.config.valeur().affichage.deviseParDefaut);
  /**
   * Le client à charger suit l'URL. Un `effect` plutôt qu'un appel au
   * constructeur : l'entrée de route n'est pas encore posée à la construction
   * (NG0950), et passer d'un client à l'autre sans quitter l'écran doit
   * recharger — le routeur réutilise le composant quand seul `:id` change.
   */
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    const entite = this.config.legalEntityId();
    try {
      const tiers = await this.clients.lire(entite, id);
      this.tiers.set(tiers);
      const [dossier, produits] = await Promise.all([
        this.clients.dossier(entite, id),
        this.clients.produits(entite),
      ]);
      this.dossier.set(dossier);
      this.produits.set(produits);
      this.phase.set('saisie');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    const empreinte = `${this.produit()}|${this.devise()}|${this.id()}`;
    if (this.empreinteDeLaCle() !== empreinte) {
      this.cle.set(crypto.randomUUID());
      this.empreinteDeLaCle.set(empreinte);
    }
    await this.soumettre();
  }

  /**
   * Rejoue la même demande. N'est proposé qu'après un refus **certain** — le
   * socle a répondu qu'il n'ouvrirait pas — jamais après une issue incertaine.
   */
  protected async reessayer(): Promise<void> {
    await this.soumettre();
  }

  protected aLaValidation(): void {
    void this.router.navigate(['/validation']);
  }

  private async soumettre(): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const issue = await this.clients.ouvrirCompte({
        legalEntityId: this.config.legalEntityId(),
        holderPartyId: this.id(),
        productCode: this.produit().trim(),
        currency: this.devise().trim().toUpperCase(),
        code: null,
      }, this.cle());
      this.issue.set(issue);
      this.phase.set('en-attente');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected nouveau(): void {
    this.produit.set('');
    this.issue.set(null);
    this.refus.set(null);
    this.phase.set('saisie');
  }

  protected auDossier(): void {
    void this.router.navigate(['/clients', this.id()]);
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
