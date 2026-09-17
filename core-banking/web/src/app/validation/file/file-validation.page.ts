import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbConfirm, CbInput, CbNotice, CbPagination,
  CbSection, CbStateBadge, CbTable, CbToolbar, EtatOperation,
} from '../../ui';
import { Identite, OperationEnAttente, decidable, etatAffiche } from '../modele/validation.modele';
import { VALIDATION } from '../validation.port';

type Phase = 'chargement' | 'prete' | 'decision' | 'erreur';

/**
 * La file de validation — l'écran du second regard.
 *
 * Il éprouve le vocabulaire d'états, et il dit deux choses que l'interface est
 * seule à pouvoir dire au bon moment :
 *   **la requête est rejouée à l'approbation**, donc l'exécution peut refuser
 *   ce que la saisie acceptait — c'est écrit sur la confirmation, pas dans une
 *   note de bas de page ;
 *   **personne n'approuve sa propre demande** — l'interface le signale avant le
 *   clic, l'API le refuse de toute façon.
 */
@Component({
  selector: 'cb-file-validation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbButton, CbInput, CbNotice, CbPagination,
    CbSection, CbStateBadge, CbTable, CbToolbar,
  ],
  templateUrl: './file-validation.page.html',
  styleUrl: './file-validation.page.css',
})
export class FileValidation {
  private readonly validation = inject(VALIDATION);
  private readonly confirmation = inject(CbConfirm);
  private readonly config = inject(AppConfig);

  protected readonly phase = signal<Phase>('chargement');
  protected readonly lignes = signal<readonly OperationEnAttente[]>([]);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  protected readonly choisie = signal<OperationEnAttente | null>(null);
  protected readonly moi = signal<Identite | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);

  protected readonly motifOuvert = signal(false);
  protected readonly motif = signal('');

  /** Ce qui attend vraiment : une ligne dont l'échéance est passée n'attend plus. */
  protected readonly enAttente = computed(
    () => this.lignes().filter((l) => etatAffiche(l) === 'en-attente').length,
  );
  protected readonly maSoumission = computed(() => {
    const operation = this.choisie();
    const moi = this.moi();
    return operation !== null && moi !== null && operation.makerId === moi.id;
  });
  protected readonly decidable = computed(() => {
    const operation = this.choisie();
    return operation !== null && decidable(operation) && !this.maSoumission();
  });
  protected readonly travaille = computed(() => this.phase() === 'chargement' || this.phase() === 'decision');

  constructor() {
    void this.charger();
  }

  protected etat(operation: OperationEnAttente): EtatOperation {
    return etatAffiche(operation);
  }

  protected async charger(page = this.page()): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      const [file, moi] = await Promise.all([
        this.validation.file(this.config.legalEntityId(), page, this.taille()),
        this.validation.identite(),
      ]);
      this.lignes.set(file.elements);
      this.page.set(file.numero);
      this.precedent.set(file.precedent);
      this.suivant.set(file.suivant);
      this.moi.set(moi);
      this.reprendreLaSelection(file.elements);
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('erreur');
    }
  }

  /** Après un rechargement, on garde la ligne ouverte si elle est encore là. */
  private reprendreLaSelection(elements: readonly OperationEnAttente[]): void {
    const courante = this.choisie();
    const suivante = courante ? elements.find((e) => e.id === courante.id) : undefined;
    this.choisie.set(suivante ?? elements[0] ?? null);
  }

  protected ouvrir(operation: OperationEnAttente): void {
    this.choisie.set(operation);
    this.motifOuvert.set(false);
    this.motif.set('');
    this.refus.set(null);
    this.acquitte.set(null);
  }

  protected async approuver(): Promise<void> {
    const operation = this.choisie();
    if (!operation || !this.decidable()) return;

    const accepte = await this.confirmation.demander({
      titre: `Approuver ${operation.handler} ?`,
      message: `Demandée par ${operation.makerUsername} le ${this.instant(operation.madeAt)}.`,
      consequence:
        "L'approbation exécute la requête telle qu'elle a été soumise. Le socle la rejoue : "
        + "si l'état du compte a changé depuis, l'exécution peut refuser ce que la saisie acceptait.",
      confirmer: 'Approuver et exécuter',
    });
    if (!accepte) return;

    await this.decider(() => this.validation.approuver(this.config.legalEntityId(), operation.id), operation.id,
                       'Approuvée et exécutée.');
  }

  protected async rejeter(): Promise<void> {
    const operation = this.choisie();
    if (!operation || !this.decidable() || !this.motif().trim()) return;
    await this.decider(
      () => this.validation.rejeter(this.config.legalEntityId(), operation.id, this.motif().trim()),
      operation.id,
      'Rejetée. Le demandeur voit le motif.',
    );
    this.motifOuvert.set(false);
    this.motif.set('');
  }

  private async decider(action: () => Promise<OperationEnAttente>, id: string, acquitte: string): Promise<void> {
    this.phase.set('decision');
    this.refus.set(null);
    this.acquitte.set(null);
    try {
      const apres = await action();
      this.remplacer(apres);
      this.acquitte.set(acquitte);
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      // Une exécution refusée après approbation laisse l'opération en ÉCHOUÉE :
      // il faut relire pour montrer l'état réel, pas celui d'avant le clic.
      try {
        this.remplacer(await this.validation.lire(this.config.legalEntityId(), id));
      } catch {
        /* la lecture a échoué aussi : le refus affiché reste le premier, qui explique tout */
      }
      this.phase.set('prete');
    }
  }

  private remplacer(operation: OperationEnAttente): void {
    this.lignes.update((lignes) => lignes.map((l) => (l.id === operation.id ? operation : l)));
    this.choisie.set(operation);
  }

  protected async changerPage(delta: number): Promise<void> {
    await this.charger(Math.max(0, this.page() + delta));
  }

  protected majMotif(valeur: string): void {
    this.motif.set(valeur);
  }

  protected ouvrirMotif(): void {
    this.motifOuvert.set(true);
    this.acquitte.set(null);
  }

  // ------------------------------------------------------------- affichage

  protected instant(iso: string): string {
    const date = new Date(iso);
    const jour = String(date.getDate()).padStart(2, '0');
    const mois = String(date.getMonth() + 1).padStart(2, '0');
    const heure = String(date.getHours()).padStart(2, '0');
    const minute = String(date.getMinutes()).padStart(2, '0');
    return `${jour}/${mois}/${date.getFullYear()} ${heure}:${minute}`;
  }

  /**
   * Le délai restant, en clair : « expire dans 4 h » se lit mieux qu'une date.
   * Une opération décidée n'a plus de compte à rebours — son échéance n'a plus
   * de sens, et l'afficher laisserait croire qu'on peut encore agir.
   */
  protected echeance(operation: OperationEnAttente): string {
    if (operation.status !== 'PENDING') return '—';
    const reste = new Date(operation.expiresAt).getTime() - Date.now();
    if (reste <= 0) return 'échéance dépassée';
    const heures = Math.floor(reste / 3_600_000);
    if (heures < 1) return `expire dans ${Math.max(1, Math.floor(reste / 60_000))} min`;
    if (heures < 48) return `expire dans ${heures} h`;
    return `expire dans ${Math.floor(heures / 24)} j`;
  }

  protected entrees(payload: Readonly<Record<string, unknown>>): readonly { cle: string; valeur: string }[] {
    return Object.entries(payload).map(([cle, valeur]) => ({
      cle: this.humaniser(cle),
      valeur: typeof valeur === 'object' && valeur !== null ? JSON.stringify(valeur) : String(valeur),
    }));
  }

  private humaniser(cle: string): string {
    const espace = cle.replace(/([A-Z])/g, ' $1').trim();
    return espace.charAt(0).toUpperCase() + espace.slice(1);
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
