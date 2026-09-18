import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmountInput, CbButton, CbField, CbInput, CbKbd, CbNotice, CbSection,
  CbStateBadge, CbToolbar, EtatOperation,
} from '../../ui';
import { CREDIT } from '../credit.port';

type Phase = 'saisie' | 'envoi' | 'deposee' | 'refuse';

/**
 * Déposer une demande de crédit.
 *
 * L'écran ne recueille que **ce que le client demande** : qui, combien,
 * combien de temps, pour quoi faire. L'analyse de sa capacité, les conditions
 * et la décision viennent après, dans le dossier — un formulaire qui
 * prétendrait tout instruire d'un coup serait rempli au jugé, et le dossier
 * naîtrait déjà faux.
 *
 * **Le socle n'honore pas de clé d'idempotence sur cette route** : elle ne
 * figure ni dans le contrat ni dans la signature du contrôleur. Quand l'issue
 * est incertaine — réseau coupé, 5xx — l'écran ne propose donc pas de rejeu :
 * un second envoi créerait deux dossiers pour le même client, que deux
 * instructeurs traiteraient en parallèle.
 */
@Component({
  selector: 'cb-nouvelle-demande-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmountInput, CbButton, CbField, CbInput, CbKbd, CbNotice, CbSection,
    CbStateBadge, CbToolbar,
  ],
  templateUrl: './nouvelle.page.html',
  styleUrl: './nouvelle.page.css',
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class NouvelleDemandeCredit {
  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  /** Vrai dès la première frappe : un formulaire vierge ne reproche rien. */
  protected readonly commence = signal(false);

  protected readonly client = signal('');
  protected readonly produit = signal('');
  protected readonly montant = signal<number | null>(null);
  protected readonly duree = signal('');
  protected readonly objet = signal('');
  protected readonly reference = signal('');

  protected readonly phase = signal<Phase>('saisie');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly deposee = signal<{ id: string } | null>(null);

  private readonly cle = signal(crypto.randomUUID());
  private readonly empreinteDeLaCle = signal('');

  protected readonly manques = computed(() => {
    const manques: string[] = [];
    if (!this.client().trim()) manques.push('Le client est obligatoire.');
    if (!this.produit().trim()) manques.push('Le code produit est obligatoire.');
    if ((this.montant() ?? 0) <= 0) manques.push('Le montant demandé doit être positif.');
    const mois = Number(this.duree());
    if (!Number.isInteger(mois) || mois <= 0) manques.push('La durée est un nombre de mois.');
    if (!this.objet().trim()) manques.push("L'objet du crédit est obligatoire.");
    return manques;
  });

  protected readonly peutEnvoyer = computed(() =>
    this.manques().length === 0 && (this.phase() === 'saisie' || this.phase() === 'refuse'));

  /**
   * Réseau coupé (0) ou panne du socle (5xx) : la demande a pu être créée.
   * Renvoyer ferait deux dossiers pour le même client.
   */
  protected readonly issueIncertaine = computed(() => {
    const r = this.refus();
    return r !== null && (r.statut === 0 || r.statut >= 500);
  });

  protected readonly etat = computed<EtatOperation>(() => {
    switch (this.phase()) {
      case 'deposee': return 'comptabilise';
      case 'refuse': return 'rejete';
      default: return 'brouillon';
    }
  });

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    const empreinte = `${this.client()}|${this.produit()}|${this.montant()}|${this.duree()}`;
    if (this.empreinteDeLaCle() !== empreinte) {
      this.cle.set(crypto.randomUUID());
      this.empreinteDeLaCle.set(empreinte);
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      this.deposee.set(await this.credit.deposer({
        legalEntityId: this.config.legalEntityId(),
        customerId: this.client().trim(),
        productCode: this.produit().trim(),
        currency: this.config.valeur().affichage.deviseParDefaut,
        requestedAmount: String(this.montant()),
        requestedTermMonths: Number(this.duree()),
        purpose: this.objet().trim(),
        reference: this.reference().trim() || null,
        requestedOn: null,
      }, this.cle()));
      this.phase.set('deposee');
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.phase.set('refuse');
    }
  }

  protected auDossier(): void {
    const faite = this.deposee();
    if (faite) void this.router.navigate(['/credit/demandes', faite.id]);
  }

  protected auxDemandes(): void {
    void this.router.navigate(['/credit/demandes']);
  }

  protected encore(): void {
    this.client.set('');
    this.montant.set(null);
    this.duree.set('');
    this.objet.set('');
    this.reference.set('');
    this.deposee.set(null);
    this.refus.set(null);
    this.phase.set('saisie');
  }
}
