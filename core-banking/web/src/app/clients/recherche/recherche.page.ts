import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CbActivity, CbButton, CbInput, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar } from '../../ui';
import { CLIENTS } from '../clients.port';
import {
  LIBELLE_KYC, LIBELLE_NATURE, LIBELLE_RISQUE, LIBELLE_STATUT, Tiers,
  etatDuKyc, etatDuTiers,
} from '../modele/clients.modele';

/**
 * Rechercher un client.
 *
 * Trois choix qui tiennent l'écran :
 *
 *   **la recherche ne se déclenche pas à la frappe.** Un guichetier tape un nom
 *   pendant que le client le lui épelle ; interroger à chaque lettre ferait
 *   défiler des résultats faux sous ses yeux et chargerait le socle pour rien ;
 *
 *   **une recherche vide n'est pas une erreur** : elle rend les premiers
 *   clients. C'est ce qu'on veut en ouvrant l'écran ;
 *
 *   **aucun résultat n'est pas un échec** : c'est une réponse, et elle propose
 *   la seule suite utile — créer le client.
 */
@Component({
  selector: 'cb-recherche-client',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbInput, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './recherche.page.html',
  styleUrl: './recherche.page.css',
})
export class RechercheClient {
  private readonly clients = inject(CLIENTS);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly router = inject(Router);

  /**
   * Le terme passé en paramètre d'URL (`?q=`). Il amorce la recherche : un
   * écran qui renvoie ici pour vérifier qu'un client n'existe pas déjà doit
   * poser la question, pas la faire retaper.
   */
  readonly termeInitial = input('', { alias: 'q' });

  protected readonly q = signal('');
  protected readonly resultats = signal<readonly Tiers[]>([]);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  /** Faux tant qu'aucune recherche n'a abouti : « aucun résultat » ne se dit qu'après. */
  protected readonly cherche = signal(false);

  /**
   * Le périmètre de lecture, quand il est plus étroit que la banque.
   *
   * `PARTY_READ` est donné par agence : un chargé de clientèle ne voit pas les
   * clients d'à côté. Une recherche qui ne rend rien laisse alors croire que le
   * client n'existe pas, alors qu'il est simplement ailleurs — et l'agent le
   * recrée, ce qui fait un doublon que personne ne rattrapera.
   *
   * Vide quand la portée est l'établissement entier, ou qu'on ne la connaît
   * pas : on ne dit une restriction que lorsqu'on en est sûr.
   */
  protected readonly perimetre = computed(() =>
    (this.droits.portee('PARTY_READ') === 'OWN_BRANCH' ? 'votre agence' : null));

  /**
   * L'entrée de route n'est pas encore posée à la construction : la lire là
   * rendrait toujours la valeur par défaut. Un `effect` la reçoit — et
   * recharge si l'on revient ici avec un autre terme.
   *
   * `untracked` borne la dépendance au seul paramètre d'URL : sans lui, la
   * lecture de `q` par le chargement ferait de la frappe un déclencheur, ce
   * que cet écran refuse précisément de faire.
   */
  constructor() {
    effect(() => {
      const terme = this.termeInitial();
      untracked(() => {
        this.q.set(terme);
        void this.charger(0);
      });
    });
  }

  protected readonly LIBELLE_NATURE = LIBELLE_NATURE;
  protected readonly LIBELLE_KYC = LIBELLE_KYC;
  protected readonly LIBELLE_RISQUE = LIBELLE_RISQUE;
  protected readonly LIBELLE_STATUT = LIBELLE_STATUT;
  protected readonly etatDuTiers = etatDuTiers;
  protected readonly etatDuKyc = etatDuKyc;

  protected async charger(page: number): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      const resultat = await this.clients.chercher(
        this.config.legalEntityId(), this.q(), page, this.taille());
      this.resultats.set(resultat.tiers);
      this.page.set(resultat.page);
      this.precedent.set(resultat.precedent);
      this.suivant.set(resultat.suivant);
      this.cherche.set(true);
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.resultats.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrir(tiers: Tiers): void {
    void this.router.navigate(['/clients', tiers.id]);
  }

  protected creer(): void {
    void this.router.navigate(['/clients/nouveau']);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }
}
