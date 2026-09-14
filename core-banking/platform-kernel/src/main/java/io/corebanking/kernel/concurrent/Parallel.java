package io.corebanking.kernel.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Execution en parallele de taches independantes d'un traitement de masse.
 *
 * <h2>Ce que ce petit utilitaire garantit, et pourquoi il existe</h2>
 *
 * <p>Les traitements de masse du socle partagent une meme forme : des milliers d'unites
 * independantes, chacune consommant une connexion a la base le temps d'une ecriture. Le degre de
 * parallelisme utile est donc borne par le pool de connexions, pas par le nombre de coeurs — au
 * dela, les taches attendent une connexion au lieu de travailler.
 *
 * <p>Deux points qui justifient de ne pas ecrire cela a chaque fois :
 *
 * <ul>
 *   <li><b>L'erreur d'origine remonte telle quelle.</b> Un traitement de fin de journee doit
 *       nommer le compte ou le contrat fautif ; une exception d'ordonnancement qui l'enveloppe
 *       rendrait le diagnostic impossible a l'exploitation.</li>
 *   <li><b>Toutes les taches sont attendues avant de rendre la main</b>, meme apres un echec. Sans
 *       cela, une tache encore en vol ecrirait dans une transaction dont l'appelant croit le
 *       traitement termine.</li>
 * </ul>
 */
public final class Parallel {

    private Parallel() {}

    /** Degre de parallelisme par defaut, borne par le pool de connexions attendu. */
    public static int defaultDegree(String property) {
        return Math.max(1, Integer.getInteger(
            property, Math.min(8, Runtime.getRuntime().availableProcessors())));
    }

    public static void runAll(List<Runnable> tasks, int parallelism) {
        if (parallelism <= 1 || tasks.size() <= 1) {
            tasks.forEach(Runnable::run);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()));
        RuntimeException failure = null;
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            tasks.forEach(task -> futures.add(pool.submit(task)));
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    RuntimeException cause = e.getCause() instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(e.getCause());
                    if (failure == null) {
                        failure = cause;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Traitement de masse interrompu", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
