import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
} from '../../ui';
import { CONFORMITE } from '../conformite.port';
import {
  Alerte, EFFET_ORIGINE, LIBELLE_ORIGINE, LIBELLE_STATUT_ALERTE, aTraiter, etatDeLAlerte,
  obstaclesALaDeclaration,
} from '../modele/conformite.modele';

type Volet = 'aucun' | 'classer' | 'declarer';

/**
 * Le dossier d'une alerte : ce qui l'a levée, ses pièces, et ce qu'on en fait.
 *
 * **Trois issues, et trois seulement.** Une alerte se prend en charge, puis
 * elle se classe avec un motif, ou elle part en déclaration de soupçon. Il n'y
 * a pas de quatrième porte : pas de blocage de compte depuis ici, pas de
 * courrier au client, pas de note au dossier client.
 *
 * **Le motif de classement est la pièce du dossier.** C'est la seule trace que
 * l'inspection viendra lire ; une alerte classée sans raison écrite ne se
 * contrôle pas, et le socle la refuse.
 *
 * **La déclaration se rédige à deux.** Elle met en cause une personne ; ne pas
 * la rédiger engage la banque autant. Aucun des deux sens ne se décide seul.
 */
@Component({
  selector: 'cb-alerte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbButton, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
  ],
  templateUrl: './alerte.page.html',
  styleUrl: './alerte.page.css',
})
export class DossierAlerte {
  readonly id = input.required<string>();

  private readonly conformite = inject(CONFORMITE);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly LIBELLE_STATUT_ALERTE = LIBELLE_STATUT_ALERTE;
  protected readonly LIBELLE_ORIGINE = LIBELLE_ORIGINE;
  protected readonly EFFET_ORIGINE = EFFET_ORIGINE;
  protected readonly etatDeLAlerte = etatDeLAlerte;

  protected readonly alerte = signal<Alerte | null>(null);
  /** Les autres alertes du même tiers : une déclaration les couvre ensemble. */
  protected readonly voisines = signal<readonly Alerte[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly envoi = signal(false);
  protected readonly volet = signal<Volet>('aucun');
  protected readonly enAttente = signal<string | null>(null);

  protected readonly motif = signal('');
  protected readonly reference = signal('');
  protected readonly expose = signal('');
  protected readonly citees = signal<readonly string[]>([]);

  private cle = crypto.randomUUID();

  protected readonly ouverte = computed(() => {
    const a = this.alerte();
    return a !== null && aTraiter(a);
  });

  /** Ce que l'alerte totalise, quand ses pièces portent des montants. */
  protected readonly total = computed(() => {
    const a = this.alerte();
    if (!a || a.pieces.length === 0) return null;
    const somme = a.pieces.reduce((s, p) => s + Number(p.amount?.amount ?? '0'), 0);
    const devise = a.pieces.find((p) => p.amount)?.amount?.currency ?? 'XOF';
    return { amount: String(somme), currency: devise };
  });

  /** Ce qui empêche de soumettre la déclaration, dit avant de la soumettre. */
  protected readonly obstacles = computed(() => {
    const a = this.alerte();
    if (!a) return [];
    return obstaclesALaDeclaration({
      partyId: a.partyId,
      reference: this.reference(),
      narrative: this.expose(),
      alertIds: this.citees(),
    }, [a, ...this.voisines()]);
  });

  constructor() {
    // L'entrée de route n'est pas posée à la construction : lire dans un effet
    // charge aussi quand on passe d'une alerte à l'autre.
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const alerte = await this.conformite.alerte(entite, id);
      this.alerte.set(alerte);
      // Les autres alertes du même tiers : une déclaration les cite ensemble,
      // et deux déclarations pour un même fait seraient deux dossiers.
      const toutes = await this.conformite.alertes(entite, null);
      this.voisines.set(toutes.filter(
        (a) => a.partyId === alerte.partyId && a.id !== alerte.id && a.status !== 'REPORTED'));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(volet: Volet): void {
    this.volet.set(volet);
    this.refus.set(null);
    this.enAttente.set(null);
    this.cle = crypto.randomUUID();
    if (volet === 'declarer') {
      // L'alerte ouverte est citée d'office : c'est elle qu'on déclare.
      this.citees.set([this.id()]);
    }
  }

  protected fermerVolet(): void {
    this.volet.set('aucun');
  }

  protected basculerCitation(alertId: string): void {
    const citees = this.citees();
    this.citees.set(citees.includes(alertId)
      ? citees.filter((id) => id !== alertId)
      : [...citees, alertId]);
  }

  protected estCitee(alertId: string): boolean {
    return this.citees().includes(alertId);
  }

  protected async prendreEnCharge(): Promise<void> {
    await this.agir(async () => {
      await this.conformite.prendreEnCharge(this.config.legalEntityId(), this.id(), this.cle);
    });
  }

  protected async classer(): Promise<void> {
    await this.agir(async () => {
      await this.conformite.classer(this.config.legalEntityId(), this.id(),
                                    this.motif().trim(), this.cle);
      this.motif.set('');
      this.fermerVolet();
    });
  }

  protected async declarer(): Promise<void> {
    const alerte = this.alerte();
    if (!alerte) return;
    await this.agir(async () => {
      const attente = await this.conformite.rediger(this.config.legalEntityId(), {
        partyId: alerte.partyId,
        reference: this.reference().trim(),
        narrative: this.expose().trim(),
        alertIds: this.citees(),
      }, this.cle);
      this.enAttente.set(attente.operationId);
      this.fermerVolet();
    });
  }

  private async agir(acte: () => Promise<void>): Promise<void> {
    this.envoi.set(true);
    this.refus.set(null);
    try {
      await acte();
      await this.charger();
      this.cle = crypto.randomUUID();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected aLaFile(): void {
    void this.router.navigate(['/conformite/alertes']);
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
