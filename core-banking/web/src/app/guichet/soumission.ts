import { computed, signal } from '@angular/core';
import { IssueVersement, Recu, RefusMetier } from './modele/guichet.modele';

export type PhaseSoumission = 'chargement' | 'saisie' | 'envoi' | 'comptabilise' | 'en-attente' | 'refuse';

/**
 * La soumission d'une opération de guichet — ce qui est identique du versement
 * au retrait, et qu'il ne faut écrire qu'une fois parce que c'est là qu'on se
 * trompe : la clé d'idempotence, son renouvellement, le rejeu, et la
 * distinction des trois issues du socle.
 *
 * La clé couvre **une demande**. Elle est conservée tant que l'empreinte de la
 * saisie ne bouge pas — « Réessayer » rejoue la même clé, un réseau qui tombe
 * ne peut pas produire une double écriture. Dès que la saisie change, la clé
 * est renouvelée : sinon le socle rendrait le premier reçu pour une opération
 * différente, et le guichetier croirait avoir passé la seconde.
 */
export class Soumission {
  readonly phase = signal<PhaseSoumission>('chargement');
  readonly recu = signal<Recu | null>(null);
  readonly attente = signal<{ operationId: string; attenduDe: string } | null>(null);
  readonly refus = signal<RefusMetier | null>(null);

  private readonly cle = signal<string>(crypto.randomUUID());
  private readonly empreinteDeLaCle = signal<string | null>(null);

  readonly enCours = computed(() => this.phase() === 'envoi' || this.phase() === 'chargement');
  readonly termine = computed(() => this.phase() === 'comptabilise' || this.phase() === 'en-attente');
  readonly cleAffichee = computed(() => (this.empreinteDeLaCle() === null ? null : this.cle()));
  readonly cleEtat = computed(() => {
    if (this.empreinteDeLaCle() === null) return "générée à l'envoi";
    return this.empreinteDeLaCle() === this.empreinte() ? 'conservée' : 'renouvelée — la saisie a changé';
  });

  /** `empreinte` résume la demande : deux saisies différentes, deux empreintes. */
  constructor(private readonly empreinte: () => string) {}

  /** Envoi normal : la clé est renouvelée si la saisie a changé depuis le dernier envoi. */
  async envoyer(action: (cle: string) => Promise<IssueVersement>): Promise<void> {
    if (this.empreinteDeLaCle() !== this.empreinte()) {
      this.cle.set(crypto.randomUUID());
      this.empreinteDeLaCle.set(this.empreinte());
    }
    await this.soumettre(action, this.cle());
  }

  /** « Réessayer » : la même clé, toujours. C'est tout l'intérêt de l'avoir gardée. */
  async reessayer(action: (cle: string) => Promise<IssueVersement>): Promise<void> {
    await this.soumettre(action, this.cle());
  }

  private async soumettre(action: (cle: string) => Promise<IssueVersement>, cle: string): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const issue = await action(cle);
      if (issue.genre === 'comptabilise') {
        this.recu.set(issue.recu);
        this.phase.set('comptabilise');
      } else {
        this.attente.set({ operationId: issue.operationId, attenduDe: issue.attenduDe });
        this.phase.set('en-attente');
      }
    } catch (erreur) {
      this.refus.set(
        erreur instanceof RefusMetier
          ? erreur
          : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur)),
      );
      this.phase.set('refuse');
    }
  }

  /** Nouvelle opération : nouvelle clé, aucune trace de la précédente. */
  reinitialiser(phase: PhaseSoumission = 'saisie'): void {
    this.recu.set(null);
    this.attente.set(null);
    this.refus.set(null);
    this.cle.set(crypto.randomUUID());
    this.empreinteDeLaCle.set(null);
    this.phase.set(phase);
  }

  poserRefus(erreur: unknown): void {
    this.refus.set(
      erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur)),
    );
    this.phase.set('refuse');
  }
}
