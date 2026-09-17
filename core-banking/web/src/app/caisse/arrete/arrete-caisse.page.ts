import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { Billetage } from '../../guichet/composants/billetage';
import { Comptage, coupuresDe, totalComptage } from '../../guichet/modele/coupures';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbConfirm, CbNotice, CbSection, CbStateBadge, CbToolbar, EtatOperation,
} from '../../ui';
import { ArreteCaisse, EtatCaisse } from '../modele/caisse.modele';
import { CAISSE } from '../caisse.port';

type Phase = 'chargement' | 'comptage' | 'envoi' | 'arretee' | 'indisponible';

/**
 * Arrêté de caisse.
 *
 * C'est l'écran qui relie le guichet au cycle comptable : le traitement de fin
 * de journée refuse de clore une journée dont une caisse mouvementée n'a pas
 * été arrêtée. Tant que ce comptage n'est pas fait, l'agence entière attend.
 *
 * Le solde théorique vient du registre, le comptage des doigts du guichetier,
 * et l'écart de la soustraction des deux. **Le poste ne corrige rien** : un
 * écart s'impute au compte d'écart de caisse, reste au nom de celui qui a
 * compté, et se justifie — il ne se rattrape pas le lendemain.
 */
@Component({
  selector: 'cb-arrete-caisse',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Billetage, CbActivity, CbAmount, CbButton, CbNotice, CbSection, CbStateBadge, CbToolbar],
  templateUrl: './arrete-caisse.page.html',
  styleUrl: './arrete-caisse.page.css',
})
export class ArreteDeCaisse {
  private readonly caisse = inject(CAISSE);
  private readonly confirmation = inject(CbConfirm);
  private readonly config = inject(AppConfig);

  protected readonly phase = signal<Phase>('chargement');
  protected readonly etat = signal<EtatCaisse | null>(null);
  protected readonly arrete = signal<ArreteCaisse | null>(null);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly comptage = signal<Comptage>({});

  protected readonly devise = computed(() => this.etat()?.currency ?? this.config.valeur().affichage.deviseParDefaut);
  protected readonly coupures = computed(() => coupuresDe(this.devise()));
  protected readonly compte = computed(() => totalComptage(this.comptage(), this.coupures()));
  protected readonly theorique = computed(() => Number(this.etat()?.book.amount ?? 0));
  protected readonly ecart = computed(() => this.compte() - this.theorique());
  protected readonly rienCompte = computed(() => Object.values(this.comptage()).every((n) => !n));
  protected readonly enCours = computed(() => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly etatOperation = computed<EtatOperation>(() =>
    this.phase() === 'arretee' ? 'comptabilise' : this.phase() === 'indisponible' ? 'bloque' : 'brouillon',
  );

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    this.phase.set('chargement');
    try {
      const etat = await this.caisse.etat(this.config.legalEntityId());
      this.etat.set(etat);
      this.phase.set(etat.arretee ? 'arretee' : 'comptage');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('indisponible');
    }
  }

  /**
   * Un écart n'empêche pas l'arrêté — le cacher serait pire. Mais il se
   * confirme explicitement : c'est irréversible, et c'est le seul moment où
   * l'on peut encore recompter.
   */
  protected async arreterLaCaisse(): Promise<void> {
    const etat = this.etat();
    if (!etat || this.phase() !== 'comptage') return;

    const ecart = this.ecart();
    const accepte = await this.confirmation.demander({
      titre: ecart === 0 ? 'Arrêter la caisse ?' : 'Arrêter la caisse avec un écart ?',
      message:
        `Comptage ${this.texte(this.compte())} ${this.devise()} contre un solde théorique de `
        + `${this.texte(this.theorique())} ${this.devise()}.`,
      consequence:
        ecart === 0
          ? "L'arrêté clôt la caisse pour la journée : plus aucune opération ne passera par elle, et un "
            + 'second comptage ne remplacera pas celui-ci.'
          : `L'écart de ${this.texte(Math.abs(ecart))} ${this.devise()} sera imputé au compte d'écart de `
            + "caisse et restera à votre nom. Il ne se corrige pas après coup : il se justifie. C'est le "
            + 'dernier moment pour recompter.',
      confirmer: ecart === 0 ? 'Arrêter la caisse' : 'Arrêter malgré l’écart',
      danger: ecart !== 0,
    });
    if (!accepte) return;

    this.phase.set('envoi');
    this.refus.set(null);
    try {
      this.arrete.set(await this.caisse.arreter(this.config.legalEntityId(), etat.tillId, this.compte(), this.devise()));
      this.phase.set('arretee');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('comptage');
    }
  }

  protected imprimer(): void {
    window.print();
  }

  protected texte(valeur: number): string {
    return new Intl.NumberFormat('fr-FR').format(valeur).replace(/ /g, ' ');
  }

  protected jour(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
