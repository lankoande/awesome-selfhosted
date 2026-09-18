import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import { CbActivity, CbButton, CbNotice, CbSection, CbTable, CbToolbar } from '../../ui';
import { REGLEMENTAIRE } from '../reglementaire.port';
import {
  Declaration, Echeance, Etat, LIBELLE_DESTINATAIRE, libellePeriode,
} from '../modele/reglementaire.modele';

/** Une échéance, rapprochée de ce que le catalogue en dit et de l'état s'il existe. */
interface LigneEcheance {
  readonly echeance: Echeance;
  readonly declaration: Declaration | null;
  readonly etat: Etat | null;
  readonly retard: number;
}

/**
 * Les échéances déclaratives : ce que la banque doit, et n'a pas encore rendu.
 *
 * **C'est l'écran d'accueil du réglementaire**, parce que c'est la seule
 * question qu'un exploitant se pose en arrivant : qu'est-ce qui est en retard ?
 *
 * **Deux retards, pas un.** Le socle rend toutes les échéances dépassées, y
 * compris celles dont l'état existe déjà — parce qu'une production n'est pas un
 * dépôt. L'écran sépare les deux, et l'ordre n'est pas neutre :
 *
 *   « rien n'est produit » demande un geste de production, qu'un comptable fait
 *   seul et qui se refait ;
 *
 *   « produit, pas déposé » demande une transmission, qui engage la banque et
 *   passe par un second regard. C'est le retard le plus discret — l'état est
 *   là, tout paraît fait — et c'est celui que le superviseur constate.
 */
@Component({
  selector: 'cb-echeances',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbNotice, CbSection, CbTable, CbToolbar],
  templateUrl: './echeances.page.html',
  styleUrl: './echeances.page.css',
})
export class Echeances {
  private readonly reglementaire = inject(REGLEMENTAIRE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly router = inject(Router);

  protected readonly LIBELLE_DESTINATAIRE = LIBELLE_DESTINATAIRE;
  protected readonly libellePeriode = libellePeriode;

  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly enCours = signal<string | null>(null);

  private readonly echeances = signal<readonly Echeance[]>([]);
  private readonly declarations = signal<readonly Declaration[]>([]);
  private readonly etats = signal<readonly Etat[]>([]);

  private readonly lignes = computed<readonly LigneEcheance[]>(() => {
    const parCode = new Map(this.declarations().map((d) => [d.code, d]));
    const aujourdHui = this.aujourdHui();
    return this.echeances().map((echeance) => ({
      echeance,
      declaration: parCode.get(echeance.declarationCode) ?? null,
      etat: this.etats().find(
        (e) => e.declarationCode === echeance.declarationCode
               && e.periodEnd === echeance.periodEnd && e.status !== 'CANCELLED') ?? null,
      retard: jours(echeance.dueOn, aujourdHui),
    }));
  });

  /** Ce qui n'existe pas encore : un geste de production. */
  protected readonly aProduire = computed(() => this.lignes().filter((l) => !l.echeance.produced));

  /** Ce qui existe mais n'est pas parti : le retard le plus discret. */
  protected readonly aDeposer = computed(() => this.lignes().filter((l) => l.echeance.produced));

  /** Produire est un travail comptable ; l'écran ne propose pas la porte fermée. */
  protected readonly peutProduire = computed(
    () => this.droits.peut('REGULATORY_REPORT_PRODUCE'));

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [echeances, declarations, etats] = await Promise.all([
        this.reglementaire.echeances(entite),
        this.reglementaire.declarations(entite),
        this.reglementaire.etats(entite, null),
      ]);
      this.echeances.set(echeances);
      this.declarations.set(declarations);
      this.etats.set(etats);
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Produire depuis l'échéance : le geste le plus fréquent de l'écran.
   *
   * La période n'est pas demandée — elle est celle de l'échéance. La faire
   * saisir ici ferait ressaisir une date déjà à l'écran, donc s'y tromper.
   */
  protected async produire(ligne: LigneEcheance): Promise<void> {
    if (!ligne.declaration || !ligne.echeance.periodEnd) return;
    this.enCours.set(ligne.echeance.declarationCode);
    this.refus.set(null);
    try {
      const etat = await this.reglementaire.produire(
        this.config.legalEntityId(), ligne.declaration.id, ligne.echeance.periodEnd,
        crypto.randomUUID());
      void this.router.navigate(['/reglementaire/etats', etat.id]);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.enCours.set(null);
    }
  }

  protected ouvrir(ligne: LigneEcheance): void {
    if (ligne.etat) void this.router.navigate(['/reglementaire/etats', ligne.etat.id]);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }

  /** La date comptable du poste. Le socle garde la sienne ; celle-ci sert à compter. */
  private aujourdHui(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}

/** Jours écoulés depuis l'échéance. Négatif : elle n'est pas encore passée. */
function jours(dueOn: string | null, aujourdHui: string): number {
  if (!dueOn) return 0;
  const ecart = Date.parse(aujourdHui + 'T00:00:00Z') - Date.parse(dueOn + 'T00:00:00Z');
  return Math.round(ecart / 86400000);
}
