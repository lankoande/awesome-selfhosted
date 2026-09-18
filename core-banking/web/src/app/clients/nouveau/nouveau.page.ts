import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbKbd, CbNotice, CbSection,
  CbStateBadge, CbToolbar, EtatOperation,
} from '../../ui';
import { CLIENTS } from '../clients.port';
import { LIBELLE_NATURE, NatureTiers, Tiers } from '../modele/clients.modele';

type Phase = 'saisie' | 'envoi' | 'cree' | 'refuse';

/**
 * Créer un client.
 *
 * L'écran ne recueille que l'**identité** : nom, nature, pays, date de
 * naissance ou d'immatriculation. Les pièces, la vérification et le niveau de
 * connaissance client viennent après, dans le dossier — un formulaire de
 * création qui prétendrait tout collecter d'un coup serait abandonné en cours
 * de route, et laisserait un dossier à moitié constitué.
 *
 * **Un client naît non vérifié.** L'écran le dit à la création plutôt que de
 * laisser croire qu'un compte pourra s'ouvrir dans la foulée.
 *
 * **Le socle n'honore pas de clé d'idempotence sur cette route** : elle ne
 * figure ni dans le contrat ni dans la signature du contrôleur. Quand l'issue
 * est incertaine — réseau coupé, 5xx — l'écran ne propose donc pas de rejeu :
 * un second envoi créerait un doublon au référentiel, et un doublon de client
 * se paie ensuite en rapprochements manuels. Il renvoie vers la recherche.
 */
@Component({
  selector: 'cb-nouveau-client',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbButton, CbDateInput, CbField, CbInput, CbKbd, CbNotice, CbSection,
    CbStateBadge, CbToolbar,
  ],
  templateUrl: './nouveau.page.html',
  styleUrl: './nouveau.page.css',
  host: { '(keydown.control.enter)': 'envoyer()' },
})
export class NouveauClient {
  private readonly clients = inject(CLIENTS);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  /** Vrai dès la première frappe : un formulaire vierge ne reproche rien. */
  protected readonly commence = signal(false);

  protected readonly nom = signal('');
  protected readonly nature = signal<NatureTiers>('NATURAL_PERSON');
  protected readonly pays = signal('BF');
  protected readonly date = signal<string | null>(null);
  protected readonly reference = signal('');
  protected readonly segment = signal('');

  protected readonly phase = signal<Phase>('saisie');
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly cree = signal<Tiers | null>(null);

  /**
   * Envoyée bien que le socle l'ignore aujourd'hui : le jour où cette route la
   * reconnaîtra, le poste n'aura rien à changer. En attendant, c'est
   * {@link issueIncertaine} qui protège du doublon.
   */
  private readonly cle = signal(crypto.randomUUID());
  private readonly empreinteDeLaCle = signal('');

  protected readonly LIBELLE_NATURE = LIBELLE_NATURE;

  protected readonly manques = computed(() => {
    const manques: string[] = [];
    if (this.nom().trim().length < 2) manques.push('Le nom ou la raison sociale est obligatoire.');
    if (!/^[A-Z]{2}$/.test(this.pays().trim().toUpperCase())) {
      manques.push('Le pays est un code ISO de deux lettres.');
    }
    return manques;
  });

  protected readonly peutEnvoyer = computed(() =>
    this.manques().length === 0 && (this.phase() === 'saisie' || this.phase() === 'refuse'));

  /**
   * L'envoi s'est-il perdu sans qu'on sache s'il a abouti ? Réseau coupé (0) ou
   * panne du socle (5xx) : le tiers a pu être créé. Renvoyer le créerait deux
   * fois. On cherche d'abord.
   */
  protected readonly issueIncertaine = computed(() => {
    const r = this.refus();
    return r !== null && (r.statut === 0 || r.statut >= 500);
  });

  protected readonly etat = computed<EtatOperation>(() => {
    switch (this.phase()) {
      case 'cree': return 'comptabilise';
      case 'refuse': return 'rejete';
      default: return 'brouillon';
    }
  });

  /** Le libellé de la date change avec la nature : une société ne naît pas, elle s'immatricule. */
  protected readonly etiquetteDate = computed(() =>
    this.nature() === 'LEGAL_PERSON' ? "Date d'immatriculation" : 'Date de naissance');

  protected async envoyer(): Promise<void> {
    if (!this.peutEnvoyer()) return;
    const empreinte = `${this.nom()}|${this.nature()}|${this.pays()}|${this.date() ?? ''}`;
    if (this.empreinteDeLaCle() !== empreinte) {
      this.cle.set(crypto.randomUUID());
      this.empreinteDeLaCle.set(empreinte);
    }
    await this.soumettre();
  }

  /** Proposé seulement après un refus **certain** : le socle n'a rien écrit. */
  protected async reessayer(): Promise<void> {
    await this.soumettre();
  }

  /** Va vérifier au référentiel si le client existe déjà, sous le nom saisi. */
  protected chercherLeNom(): void {
    void this.router.navigate(['/clients', 'recherche'], { queryParams: { q: this.nom().trim() } });
  }

  private async soumettre(): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const tiers = await this.clients.creer({
        legalEntityId: this.config.legalEntityId(),
        displayName: this.nom().trim(),
        kind: this.nature(),
        countryCode: this.pays().trim().toUpperCase(),
        birthOrRegistrationDate: this.date(),
        reference: this.reference().trim() || null,
        segment: this.segment().trim() || null,
      }, this.cle());
      this.cree.set(tiers);
      this.phase.set('cree');
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.phase.set('refuse');
    }
  }

  protected auDossier(): void {
    const tiers = this.cree();
    if (tiers) void this.router.navigate(['/clients', tiers.id]);
  }

  protected encore(): void {
    this.nom.set('');
    this.date.set(null);
    this.reference.set('');
    this.segment.set('');
    this.cree.set(null);
    this.refus.set(null);
    this.phase.set('saisie');
  }
}
