import { habilitationsDeDemonstration } from './habilitations.demonstration';
import { depasseLePlafond, exigeUnSecondRegard, plafond, portee } from './habilitations';

describe('profil de démonstration', () => {
  const profil = habilitationsDeDemonstration();

  it('ouvre toutes les portes : une démonstration se parcourt en entier', () => {
    expect(profil.connues).toBe(true);
    expect(profil.droits.size).toBeGreaterThan(90);
  });

  it('porte les seconds regards de la politique, pas une valeur par défaut', () => {
    // Débloquer un crédit se fait à deux ; encaisser un remboursement, non.
    expect(exigeUnSecondRegard(profil, 'LOAN_DISBURSE')).toBe(true);
    expect(exigeUnSecondRegard(profil, 'LOAN_REPAYMENT')).toBe(false);
  });

  it('porte les plafonds du profil, et le plafond déplacé plus bas', () => {
    expect(plafond(profil, 'CASH_OPERATION', 'XOF')?.amount).toBe('25000000');
    expect(plafond(profil, 'CASH_OPERATION', 'XOF', true)?.amount).toBe('5000000');
    expect(depasseLePlafond(profil, 'CASH_OPERATION', 30_000_000, 'XOF')).toBe(true);
  });

  it('porte les portées : la caisse ne sort pas de son agence', () => {
    expect(portee(profil, 'CASH_OPERATION')).toBe('OWN_BRANCH');
    expect(portee(profil, 'LEDGER_READ')).toBe('OWN_ENTITY');
  });

  it('retire ce que le déploiement demande de retirer', () => {
    const sansTransmission = habilitationsDeDemonstration(['REGULATORY_REPORT_TRANSMIT']);
    expect(sansTransmission.droits.has('REGULATORY_REPORT_TRANSMIT')).toBe(false);
    expect(sansTransmission.droits.has('REGULATORY_REPORT_PRODUCE')).toBe(true);
  });
});
