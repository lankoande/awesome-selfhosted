package io.corebanking.ledger.domain;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.UnbalancedEntryException;
import io.corebanking.ledger.domain.posting.EntryValidator;
import io.corebanking.ledger.domain.posting.PostingContext;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.ValidatedEntry;
import io.corebanking.ledger.domain.posting.ValidatedLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Invariants du ledger verifies par generation.
 *
 * <p>Les tests par proprietes valent mieux que des cas d'exemple sur un noyau comptable : ils
 * explorent des combinaisons — meme compte debite et credite dans la meme ecriture, montants
 * extremes, ecritures a nombreuses lignes — qu'aucune redaction manuelle n'anticipe.
 */
class LedgerInvariantsProperties {

    private static final int POOL_SIZE = 6;
    private static final List<Account> POOL = buildPool();

    private static List<Account> buildPool() {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            accounts.add(i % 2 == 0
                ? Fixtures.customerAccount("CLI-" + i, XOF)
                : Fixtures.glAccount("GL-" + i, XOF,
                                     i % 4 == 1 ? NormalBalance.DEBIT : NormalBalance.CREDIT));
        }
        return List.copyOf(accounts);
    }

    private static PostingContext context() {
        Map<UUID, Account> map = new LinkedHashMap<>();
        POOL.forEach(a -> map.put(a.id(), a));
        return new PostingContext(XOF, map);
    }

    /** Une ecriture construite par paires debit/credit equilibrees est toujours acceptee. */
    @Property(tries = 500)
    void toute_ecriture_equilibree_est_acceptee(@ForAll("transferts") List<Transfer> transfers) {
        ValidatedEntry entry = EntryValidator.validate(
            Fixtures.command(toLines(transfers)), context());

        Money debits = Money.zero(XOF);
        Money credits = Money.zero(XOF);
        for (ValidatedLine line : entry.lines()) {
            if (line.line().direction() == Direction.DEBIT) {
                debits = debits.plus(line.line().amount());
            } else {
                credits = credits.plus(line.line().amount());
            }
        }
        assertThat(debits).isEqualTo(credits);
    }

    /** Alterer un seul montant suffit a faire rejeter l'ecriture. */
    @Property(tries = 500)
    void toute_alteration_dun_montant_est_detectee(@ForAll("transferts") List<Transfer> transfers,
                                                   @ForAll long perturbation) {
        long delta = Math.floorMod(perturbation, 1_000_000L) + 1;   // jamais nul
        List<PostingLine> lines = new ArrayList<>(toLines(transfers));
        PostingLine first = lines.get(0);
        lines.set(0, new PostingLine(first.accountId(), first.direction(),
                                     first.amount().plus(Money.of(delta, XOF)),
                                     first.valueDate(), first.label(), first.fxRate()));

        assertThatThrownBy(() -> EntryValidator.validate(Fixtures.command(lines), context()))
            .isInstanceOf(UnbalancedEntryException.class);
    }

    /** Une contre-passation ramene chaque compte a son solde d'origine, exactement. */
    @Property(tries = 500)
    void une_contre_passation_neutralise_exactement_lecriture(
            @ForAll("transferts") List<Transfer> transfers) {
        List<PostingLine> original = toLines(transfers);
        List<PostingLine> reversed = original.stream().map(PostingLine::reversed).toList();

        ValidatedEntry entry = EntryValidator.validate(Fixtures.command(original), context());
        ValidatedEntry extourne = EntryValidator.validate(Fixtures.command(reversed), context());

        Map<UUID, Money> net = new LinkedHashMap<>();
        entry.lines().forEach(l -> net.merge(l.account().id(), l.signedAmount(), Money::plus));
        extourne.lines().forEach(l -> net.merge(l.account().id(), l.signedAmount(), Money::plus));

        assertThat(net.values()).allMatch(Money::isZero);
    }

    /** Les dates de valeur sont reprises a l'identique : les interets deja calcules restent justes. */
    @Property(tries = 200)
    void une_contre_passation_conserve_les_dates_de_valeur(
            @ForAll("transferts") List<Transfer> transfers) {
        List<PostingLine> original = toLines(transfers);
        List<PostingLine> reversed = original.stream().map(PostingLine::reversed).toList();

        for (int i = 0; i < original.size(); i++) {
            assertThat(reversed.get(i).valueDate()).isEqualTo(original.get(i).valueDate());
            assertThat(reversed.get(i).direction())
                .isEqualTo(original.get(i).direction().opposite());
        }
    }

    // ------------------------------------------------------------------ generateurs

    record Transfer(int fromIndex, int toIndex, long amount, int valueDateShift) {}

    private List<PostingLine> toLines(List<Transfer> transfers) {
        List<PostingLine> lines = new ArrayList<>();
        for (Transfer t : transfers) {
            var valueDate = Fixtures.TODAY.plusDays(t.valueDateShift());
            Money amount = Money.of(t.amount(), XOF);
            lines.add(PostingLine.debit(POOL.get(t.fromIndex()).id(), amount, valueDate, "D"));
            lines.add(PostingLine.credit(POOL.get(t.toIndex()).id(), amount, valueDate, "C"));
        }
        return lines;
    }

    @Provide
    Arbitrary<List<Transfer>> transferts() {
        Arbitrary<Integer> index = Arbitraries.integers().between(0, POOL_SIZE - 1);
        Arbitrary<Long> amount = Arbitraries.longs().between(1, 500_000_000_000L);
        Arbitrary<Integer> shift = Arbitraries.integers().between(-5, 5);
        return Combinators.build(index, amount, shift);
    }

    /** Assemblage des generateurs, isole pour rester lisible. */
    static final class Combinators {
        private Combinators() {}

        static Arbitrary<List<Transfer>> build(Arbitrary<Integer> index, Arbitrary<Long> amount,
                                               Arbitrary<Integer> shift) {
            Arbitrary<Transfer> transfer = net.jqwik.api.Combinators
                .combine(index, index, amount, shift)
                .as(Transfer::new);
            return transfer.list().ofMinSize(1).ofMaxSize(8);
        }
    }
}
