import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbField, CbInput, CbNotice, CbSection, CbToolbar,
} from '../../ui';
import { DemandeEtablissement, Etablissement } from '../modele/etablissement.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'lecture' | 'saisie' | 'envoi' | 'soumis' | 'refuse';

/** Un champ modifiable de la fiche, tel que le formulaire le tient. */
interface Champ {
  readonly cle: keyof DemandeEtablissement;
  readonly etiquette: string;
  readonly aide: string;
}

/**
 * L'identité de l'établissement.
 *
 * C'est ce qui figure en en-tête de chaque relevé et de chaque état transmis au
 * superviseur — et, pour le code banque, en tête de chaque RIB. Rien de tout
 * cela n'avait d'écran : une banque s'installait par un script.
 *
 * Trois règles que l'écran annonce sans les tenir, parce que c'est le socle qui
 * les tient :
 *
 *   **le code, le pays et la devise de tenue ne se corrigent pas.** Ils sont
 *   posés dans chaque écriture depuis le premier jour ; les changer serait
 *   réécrire l'histoire comptable, pas corriger une fiche. L'écran les montre
 *   en lecture, sans champ ;
 *
 *   **le code banque se fige au premier compte numéroté avec lui.** Au-delà,
 *   le changer donnerait deux RIB de banques différentes à des comptes de la
 *   même banque ;
 *
 *   **corriger passe par un second regard.** Une faute de frappe sur le code
 *   banque ne se voit pas à l'écran : elle se voit six mois plus tard, sur un
 *   virement reçu qui n'arrive jamais.
 */
@Component({
  selector: 'cb-etablissement',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbField, CbInput, CbNotice, CbSection, CbToolbar],
  templateUrl: './etablissement.page.html',
  styleUrl: './etablissement.page.css',
})
export class EtablissementPage {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly phase = signal<Phase>('chargement');
  protected readonly fiche = signal<Etablissement | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly enAttente = signal<string | null>(null);
  protected readonly saisie = signal<Record<string, string>>({});

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeCorriger = computed(() => this.droits.peut('ESTABLISHMENT_MANAGE'));
  protected readonly secondRegard = computed(
    () => this.droits.annonceDeuxRegards('ESTABLISHMENT_MANAGE'));

  /**
   * Ce qui bouge entre la fiche et la saisie.
   *
   * Un formulaire qui renverrait tous ses champs effacerait ce que l'opérateur
   * n'a pas touché : le socle distingue l'absent du vide, et l'écran doit lui
   * donner cette distinction plutôt que de tout envoyer.
   */
  protected readonly modifications = computed(() => {
    const fiche = this.fiche();
    if (fiche === null) {
      return {} as DemandeEtablissement;
    }
    const demande: Record<string, string> = {};
    for (const champ of this.champs) {
      const saisi = this.saisie()[champ.cle];
      if (saisi === undefined) {
        continue;
      }
      const courant = (fiche[champ.cle as keyof Etablissement] as string | null) ?? '';
      if (saisi.trim() !== courant.trim()) {
        demande[champ.cle] = saisi.trim();
      }
    }
    return demande as DemandeEtablissement;
  });

  protected readonly aDesModifications = computed(
    () => Object.keys(this.modifications()).length > 0);

  protected readonly champs: readonly Champ[] = [
    { cle: 'name', etiquette: 'Nom commercial', aide: "Ce que la barre du poste affiche." },
    { cle: 'legalName', etiquette: 'Dénomination sociale',
      aide: 'Si elle diffère du nom commercial.' },
    { cle: 'bankCode', etiquette: 'Code banque',
      aide: "Attribué par la banque centrale. En-tête du RIB." },
    { cle: 'approvalNumber', etiquette: 'Numéro d’agrément', aide: "L'agrément bancaire." },
    { cle: 'taxId', etiquette: 'Identifiant fiscal', aide: "L'IFU, repris sur les déclarations." },
    { cle: 'registryNumber', etiquette: 'Registre du commerce', aide: 'Le RCCM.' },
    { cle: 'address', etiquette: 'Adresse', aide: "Le siège social, tel qu'il est déclaré." },
    { cle: 'phone', etiquette: 'Téléphone', aide: '' },
    { cle: 'email', etiquette: 'Courriel', aide: '' },
  ];

  constructor() {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      this.fiche.set(await this.siege.etablissement(this.config.legalEntityId()));
      this.saisie.set({});
      this.phase.set('lecture');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected corriger(): void {
    const fiche = this.fiche();
    if (fiche === null) {
      return;
    }
    const depart: Record<string, string> = {};
    for (const champ of this.champs) {
      depart[champ.cle] = (fiche[champ.cle as keyof Etablissement] as string | null) ?? '';
    }
    this.saisie.set(depart);
    this.enAttente.set(null);
    this.phase.set('saisie');
  }

  protected annuler(): void {
    this.saisie.set({});
    this.phase.set('lecture');
  }

  protected majChamp(cle: string, valeur: string): void {
    this.saisie.update((actuel) => ({ ...actuel, [cle]: valeur }));
  }

  protected valeurAffichee(cle: string): string {
    return this.saisie()[cle] ?? '';
  }

  protected valeurFiche(cle: string): string {
    const fiche = this.fiche();
    return fiche === null ? '' : ((fiche[cle as keyof Etablissement] as string | null) ?? '');
  }

  protected async soumettre(): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      const issue = await this.siege.majEtablissement(
        this.config.legalEntityId(), this.modifications(), crypto.randomUUID());
      this.enAttente.set(issue.operationId);
      this.phase.set('soumis');
      await this.rafraichir();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /** Relire après l'envoi : en mode api, rien n'a bougé tant que le second regard n'est pas passé. */
  private async rafraichir(): Promise<void> {
    try {
      this.fiche.set(await this.siege.etablissement(this.config.legalEntityId()));
    } catch {
      // La relecture est un confort : son échec ne doit pas masquer l'envoi réussi.
    }
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
