import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
  CbToolbar, EtatOperation,
} from '../../ui';
import { CbChoixCompteGeneral } from '../composants/choix-compte-general';
import {
  Agence, DemandeAgence, enArbre, LIBELLE_NATURE_AGENCE, NatureAgence, NoeudReseau,
  obstaclesALAgence,
} from '../modele/reseau.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const NATURES: readonly NatureAgence[] = ['BRANCH', 'REGION'];

/**
 * Le réseau : le siège, les régions, les agences.
 *
 * <h2>Ce que cet écran ferme</h2>
 *
 * Une agence se créait par l'API et n'apparaissait ensuite nulle part : ni pour vérifier qu'elle
 * avait bien été créée, ni pour savoir à quoi elle était rattachée. Le socle ne publiait aucune
 * lecture du réseau.
 *
 * <h2>Le compte de liaison</h2>
 *
 * C'est le seul champ qui demande une explication, et c'est celui qu'on oublie. Une écriture entre
 * deux agences ne passe pas d'un compte client à l'autre : elle transite par un **compte de
 * liaison** tenu au siège, dans la devise de l'opération. Sans lui, la première opération déplacée
 * échoue — en agence, devant un client. L'écran en exige donc au moins un.
 *
 * <h2>Ce qui ne se change plus</h2>
 *
 * Le code figure dans les numéros de compte que l'agence ouvrira. Il ne se change pas ensuite, et
 * c'est pourquoi une création se valide comme une opération.
 */
@Component({
  selector: 'cb-agences',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbChoixCompteGeneral, CbDateInput, CbField, CbInput, CbNotice,
            CbSection, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './agences.page.html',
  styleUrl: './agences.page.css',
})
export class Agences {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly NATURES = NATURES;
  protected readonly LIBELLE_NATURE_AGENCE = LIBELLE_NATURE_AGENCE;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly agences = signal<readonly Agence[]>([]);

  protected readonly saisie = signal(false);
  protected readonly code = signal('');
  protected readonly nom = signal('');
  protected readonly nature = signal<NatureAgence>('BRANCH');
  protected readonly parent = signal('');
  protected readonly ouverteLe = signal<string | null>(null);
  protected readonly liaisons = signal<Record<string, string>>({});
  protected readonly deviseNeuve = signal('');

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeCreer = computed(() => this.droits.peut('BRANCH_MANAGE'));
  protected readonly creationADeux = computed(
    () => this.droits.annonceDeuxRegards('BRANCH_MANAGE'));

  protected readonly arbre = computed<readonly NoeudReseau[]>(() => enArbre(this.agences()));

  protected readonly ouvertes = computed(
    () => this.agences().filter((a) => a.status === 'ACTIVE').length);

  /** Les rattachements possibles : le siège et les régions, jamais une agence. */
  protected readonly parents = computed(
    () => this.agences().filter((a) => a.kind !== 'BRANCH'));

  protected readonly lignesLiaison = computed(
    () => Object.entries(this.liaisons()).map(([devise, compte]) => ({ devise, compte })));

  protected readonly demande = computed<DemandeAgence>(() => ({
    code: this.code(),
    name: this.nom(),
    kind: this.nature(),
    parentId: this.parent() || null,
    openedOn: this.ouverteLe(),
    liaisonAccounts: this.liaisons(),
  }));

  protected readonly obstacles = computed(
    () => obstaclesALAgence(this.demande(), this.agences().map((a) => a.code)));

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      this.agences.set(await this.siege.agences(this.config.legalEntityId()));
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected ouvrirLaSaisie(): void {
    this.saisie.set(true);
    this.acquitte.set(null);
    this.refus.set(null);
    this.code.set('');
    this.nom.set('');
    this.nature.set('BRANCH');
    this.parent.set(this.agences().find((a) => a.kind === 'HEAD_OFFICE')?.id ?? '');
    this.ouverteLe.set(null);
    // La devise de tenue d'abord : c'est celle de la quasi-totalité des opérations déplacées.
    this.liaisons.set({ [this.config.valeur().affichage.deviseParDefaut]: '' });
    this.deviseNeuve.set('');
  }

  protected fermerLaSaisie(): void {
    this.saisie.set(false);
  }

  protected majLiaison(devise: string, compte: string): void {
    this.liaisons.update((liaisons) => ({ ...liaisons, [devise]: compte }));
  }

  protected ajouterDevise(): void {
    const devise = this.deviseNeuve().trim().toUpperCase();
    if (devise === '' || this.liaisons()[devise] !== undefined) {
      return;
    }
    this.liaisons.update((liaisons) => ({ ...liaisons, [devise]: '' }));
    this.deviseNeuve.set('');
  }

  protected retirerDevise(devise: string): void {
    this.liaisons.update((liaisons) => {
      const reste = { ...liaisons };
      delete reste[devise];
      return reste;
    });
  }

  protected async creer(): Promise<void> {
    if (this.obstacles().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.creerAgence(this.config.legalEntityId(), this.demande(),
                                   crypto.randomUUID());
      this.saisie.set(false);
      this.acquitte.set(`Création de l'agence ${this.code()} demandée. Elle n'existera qu'après `
                        + "la validation d'un second — son code figure dans les numéros de compte "
                        + "qu'elle ouvrira, et ne se change plus ensuite.");
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected etatDe(agence: Agence): EtatOperation {
    return agence.status === 'ACTIVE' ? 'approuve' : 'contre-passe';
  }

  protected libelleEtat(agence: Agence): string {
    return agence.status === 'ACTIVE' ? 'Ouverte' : 'Fermée';
  }

  protected nomDuParent(agence: Agence): string {
    if (agence.parentId === null) {
      return '—';
    }
    const parent = this.agences().find((a) => a.id === agence.parentId);
    return parent ? `${parent.code} · ${parent.name}` : 'rattachement introuvable';
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
