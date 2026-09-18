import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { autorise } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbTable, CbToolbar,
} from '../../ui';
import { REGLEMENTAIRE } from '../reglementaire.port';
import {
  AssietteTaxe, EFFET_ASSIETTE, LIBELLE_ASSIETTE, RegleFiscale, obstaclesALaRegleFiscale,
} from '../modele/reglementaire.modele';

const ASSIETTES: readonly AssietteTaxe[] = ['INTEREST_PAID', 'FEES_CHARGED', 'TRANSACTION'];

/**
 * Les taxes que la banque retient et collecte pour l'administration.
 *
 * **Une taxe n'est pas un produit de la banque.** Elle est prélevée sur le
 * client et reversée : elle transite par un compte de collecte, et c'est
 * pourquoi ce compte est obligatoire. Une taxe retenue sans compte où la loger
 * serait prise au client sans être due à personne — l'erreur est invisible au
 * guichet et se découvre au contrôle fiscal.
 *
 * **Un taux s'exprime en pour cent.** 15 est quinze pour cent, pas quinze
 * millièmes. La confusion existe et coûte cher dans les deux sens ; l'écran le
 * dit et borne la saisie entre 0 et 100.
 *
 * **Le taux se pose à deux** : il produit des montants sur des comptes clients,
 * comme le reste du paramétrage qui compte.
 */
@Component({
  selector: 'cb-fiscalite',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbButton, CbDateInput, CbField, CbInput, CbNotice, CbSection, CbTable, CbToolbar,
  ],
  templateUrl: './fiscalite.page.html',
  styleUrl: './fiscalite.page.css',
})
export class Fiscalite {
  private readonly reglementaire = inject(REGLEMENTAIRE);
  private readonly config = inject(AppConfig);
  private readonly authentification = inject(AUTHENTIFICATION);

  protected readonly ASSIETTES = ASSIETTES;
  protected readonly LIBELLE_ASSIETTE = LIBELLE_ASSIETTE;
  protected readonly EFFET_ASSIETTE = EFFET_ASSIETTE;

  protected readonly regles = signal<readonly RegleFiscale[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly envoi = signal(false);
  protected readonly volet = signal(false);
  protected readonly enAttente = signal<string | null>(null);
  protected readonly commence = signal(false);

  protected readonly code = signal('');
  protected readonly libelle = signal('');
  protected readonly assiette = signal<AssietteTaxe>('INTEREST_PAID');
  protected readonly taux = signal('');
  protected readonly compteDeCollecte = signal('');
  protected readonly dateDEffet = signal<string | null>(null);
  protected readonly dateDeFin = signal<string | null>(null);

  private cle = crypto.randomUUID();

  protected readonly peutDeclarer = computed(() =>
    autorise(this.authentification.habilitations(), 'TAX_RULE_MANAGE'));

  protected readonly obstacles = computed(() => obstaclesALaRegleFiscale({
    code: this.code(),
    label: this.libelle(),
    basis: this.assiette(),
    ratePercent: this.taux() === '' ? null : this.taux().replace(',', '.'),
    collectionAccountId: this.compteDeCollecte(),
    validFrom: this.dateDEffet(),
    validTo: this.dateDeFin(),
  }));

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.regles.set(await this.reglementaire.reglesFiscales(this.config.legalEntityId()));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.regles.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(): void {
    this.volet.set(true);
    this.refus.set(null);
    this.enAttente.set(null);
    this.commence.set(false);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set(false);
  }

  protected async declarer(): Promise<void> {
    this.commence.set(true);
    if (this.obstacles().length > 0) return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      const attente = await this.reglementaire.declarerRegleFiscale(
        this.config.legalEntityId(), {
          code: this.code().trim(),
          label: this.libelle().trim(),
          basis: this.assiette(),
          ratePercent: this.taux().replace(',', '.'),
          collectionAccountId: this.compteDeCollecte().trim(),
          validFrom: this.dateDEffet(),
          validTo: this.dateDeFin(),
        }, this.cle);
      this.enAttente.set(attente.operationId);
      this.fermerVolet();
      this.cle = crypto.randomUUID();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected pourcent(valeur: string | null): string {
    return formaterTaux(valeur);
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
