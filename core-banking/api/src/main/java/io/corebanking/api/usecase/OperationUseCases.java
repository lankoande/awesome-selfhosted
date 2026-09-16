package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.OperationsService;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;

/**
 * Operations de guichet et virements : un cas d'usage par operation du catalogue.
 *
 * <p>La cible d'une operation de caisse est <b>la caisse</b> — le guichetier tient celle de son
 * agence — et l'operation est deplacee quand le compte du client releve d'une autre agence. Le
 * cas d'usage nomme la cible ; il ne decide de rien : la regle est dans {@code SecurityConfig}.
 */
public final class OperationUseCases {

    private OperationUseCases() {}

    public static final class Deposit
            implements UseCase<OperationsService.Deposit, OperationsService.Receipt> {
        private final OperationsService operations;
        private final AccountDirectory accounts;

        public Deposit(OperationsService operations, AccountDirectory accounts) {
            this.operations = operations;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CASH_OPERATION; }

        @Override
        public AccessTarget targetOf(OperationsService.Deposit command) {
            return cashTarget(accounts, command.legalEntityId(), command.cashAccountId(),
                              command.accountId(), command.amount());
        }

        @Override
        public OperationsService.Receipt execute(OperationsService.Deposit command) {
            return operations.deposit(command);
        }
    }

    public static final class Withdraw
            implements UseCase<OperationsService.Withdrawal, OperationsService.Receipt> {
        private final OperationsService operations;
        private final AccountDirectory accounts;

        public Withdraw(OperationsService operations, AccountDirectory accounts) {
            this.operations = operations;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CASH_OPERATION; }

        @Override
        public AccessTarget targetOf(OperationsService.Withdrawal command) {
            return cashTarget(accounts, command.legalEntityId(), command.cashAccountId(),
                              command.accountId(), command.amount());
        }

        @Override
        public OperationsService.Receipt execute(OperationsService.Withdrawal command) {
            return operations.withdraw(command);
        }
    }

    public static final class Transfer
            implements UseCase<OperationsService.Transfer, OperationsService.Receipt> {
        private final OperationsService operations;

        public Transfer(OperationsService operations) {
            this.operations = operations;
        }

        @Override public Operation operation() { return Operation.TRANSFER; }

        @Override
        public AccessTarget targetOf(OperationsService.Transfer command) {
            return AccessTarget.inEntity(command.legalEntityId()).withAmount(command.amount());
        }

        @Override
        public OperationsService.Receipt execute(OperationsService.Transfer command) {
            return operations.transfer(command);
        }
    }

    static AccessTarget cashTarget(AccountDirectory accounts, java.util.UUID entity,
                                           java.util.UUID cashAccountId, java.util.UUID accountId,
                                           io.corebanking.kernel.money.Money amount) {
        Account till = accounts.require(cashAccountId);
        Account account = accounts.require(accountId);
        AccessTarget target = AccessTarget.inBranch(entity, till.branchId()).withAmount(amount);
        boolean remote = account.branchId() != null && !account.branchId().equals(till.branchId());
        return remote ? target.performedRemotely() : target;
    }
}
