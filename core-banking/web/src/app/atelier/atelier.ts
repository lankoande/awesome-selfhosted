import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Apparence } from '../core/apparence/apparence';
import { AppConfig } from '../core/config/runtime-config';
import {
  CbActivity,
  CbAmount,
  CbAmountInput,
  CbButton,
  CbConfirm,
  CbDateInput,
  CbDrawer,
  CbField,
  CbInput,
  CbKbd,
  CbNotice,
  CbPagination,
  CbSection,
  CbStateBadge,
  CbTable,
  CbTabs,
  CbToolbar,
  ETATS,
  PRIMITIVES_UI,
} from '../ui';
import { AtelierTiroir } from './atelier-tiroir';

/**
 * L'atelier. Toutes les primitives, tous leurs états, les deux densités, les
 * deux thèmes — dans l'application elle-même, pas dans un outil à côté qui
 * dériverait. C'est le garde-fou visuel : un composant qui n'est pas ici
 * n'existe pas, et un test le vérifie.
 */
@Component({
  selector: 'cb-atelier',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput,
    CbKbd, CbNotice, CbPagination, CbSection, CbStateBadge, CbTable, CbTabs, CbToolbar,
  ],
  templateUrl: './atelier.html',
  styleUrl: './atelier.css',
})
export class Atelier {
  private readonly tiroir = inject(CbDrawer);
  private readonly confirmation = inject(CbConfirm);
  protected readonly apparence = inject(Apparence);
  protected readonly config = inject(AppConfig);

  protected readonly primitives = PRIMITIVES_UI;
  protected readonly etats = ETATS;

  protected readonly montant = signal<number | null>(2500000);
  protected readonly date = signal<string | null>('2026-09-17');
  protected readonly ongletActif = signal('saisie');
  protected readonly taillePage = signal(50);
  protected readonly travaille = signal(false);
  protected readonly dernierChoix = signal<string>('—');

  protected readonly onglets = [
    { id: 'saisie', libelle: 'Saisie' },
    { id: 'validation', libelle: 'À valider', compte: 7 },
    { id: 'historique', libelle: 'Historique' },
  ];

  protected readonly lignes = [
    { heure: '09:41', piece: 'VER-004128', libelle: 'Versement espèces', montant: 2500000, etat: 'comptabilise' as const },
    { heure: '09:12', piece: 'VIR-004127', libelle: 'Virement interne sortant', montant: -450000, etat: 'en-attente' as const },
    { heure: '08:52', piece: 'RET-003911', libelle: 'Retrait espèces', montant: -120000, etat: 'contre-passe' as const },
  ];

  protected ouvrirTiroir(): void {
    this.tiroir.ouvrir(AtelierTiroir, {
      donnees: { intitule: 'SANKARA Aminata' },
      etiquette: 'Contexte du compte',
    });
  }

  protected async demanderConfirmation(): Promise<void> {
    const accepte = await this.confirmation.demander({
      titre: 'Contre-passer cette écriture ?',
      message: "L'écriture VER-2026-09-17-004128 sera annulée par une écriture inverse du même montant.",
      consequence: "Rien n'est effacé : les deux écritures restent au journal, et un second regard est requis.",
      confirmer: 'Contre-passer',
      danger: true,
    });
    this.dernierChoix.set(accepte ? 'Contre-passation confirmée' : 'Contre-passation annulée');
  }

  protected basculerActivite(): void {
    this.travaille.update((valeur) => !valeur);
  }
}
