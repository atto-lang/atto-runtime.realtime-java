import org.attolang.runtime.SerialCarrierScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Additional HOST_ONLY shutdown and fairness conformance probes. */
public final class SerialCarrierShutdownProbe {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "latch timed out"); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
    private static void runningShutdown() throws Exception {
        var scheduler = new SerialCarrierScheduler(2, 2, 4);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            var actor = scheduler.spawn(0, (state, msg) -> {
                if (msg == 1) { entered.countDown(); awaitLatch(unblock); }
                return Math.addExact(state, msg);
            });
            check(scheduler.try_send(actor, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED, "first admission");
            awaitLatch(entered);
            check(scheduler.try_send(actor, 2) == SerialCarrierScheduler.SendStatus.ACCEPTED, "second admission");
            check(scheduler.try_send(actor, 3) == SerialCarrierScheduler.SendStatus.ACCEPTED, "third admission");
            var error = new AtomicReference<Throwable>();
            Runnable closer = () -> {
                try { scheduler.close(); }
                catch (Throwable ex) { error.compareAndSet(null, ex); }
            };
            Thread closerA = new Thread(closer, "atto-close-A");
            Thread closerB = new Thread(closer, "atto-close-B");
            closerA.start(); closerB.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean observedShutdown = false;
            while (System.nanoTime() < deadline) {
                var status = scheduler.try_send(actor, 0);
                check(status == SerialCarrierScheduler.SendStatus.ACCEPTED ||
                      status == SerialCarrierScheduler.SendStatus.SHUTTING_DOWN ||
                      status == SerialCarrierScheduler.SendStatus.FULL,
                      "unexpected send result racing shutdown: " + status);
                if (status == SerialCarrierScheduler.SendStatus.SHUTTING_DOWN) {
                    observedShutdown = true;
                    break;
                }
                Thread.yield();
            }
            check(observedShutdown, "shutdown did not fence new admissions");
            var before = scheduler.snapshot(actor);
            check(before.inFlight() == 1 && before.accounted(), "in-flight accounting during shutdown");
            unblock.countDown();
            closerA.join(10000); closerB.join(10000);
            check(!closerA.isAlive() && !closerB.isAlive(), "concurrent shutdown deadlocked");
            check(error.get() == null, "shutdown exception: " + error.get());
            var after = scheduler.snapshot(actor);
            check(after != null && after.accounted() && !after.active() &&
                  after.processed() == after.accepted() && after.inFlight() == 0 &&
                  after.queued() == 0, "shutdown failed to drain already admitted turns");
            check(scheduler.try_send(actor, 7) == SerialCarrierScheduler.SendStatus.SHUTTING_DOWN,
                  "closed scheduler accepted new mail");
        } finally { scheduler.close(); }
        System.out.println("ATTO_RT_CONCURRENT_SHUTDOWN_WITH_IN_FLIGHT=PASS");
    }

    private static void singleCarrierFairness() throws Exception {
        try (var scheduler = new SerialCarrierScheduler(1, 2, 64)) {
            CountDownLatch blockedA = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            AtomicInteger aTurns = new AtomicInteger();
            AtomicInteger neighborObserved = new AtomicInteger(-1);
            var a = scheduler.spawn(0, (state, msg) -> {
                if (aTurns.incrementAndGet() == 1) {
                    blockedA.countDown();
                    awaitLatch(releaseA);
                }
                return Math.addExact(state, msg);
            });
            var b = scheduler.spawn(0, (state, msg) -> {
                neighborObserved.set(aTurns.get());
                return Math.addExact(state, msg);
            });
            check(scheduler.try_send(a, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED, "first A");
            awaitLatch(blockedA);
            for (int i = 0; i < 40; i++)
                check(scheduler.try_send(a, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "A backlog unexpectedly full");
            check(scheduler.try_send(b, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                  "neighbor admission");
            releaseA.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (neighborObserved.get() < 0 && System.nanoTime() < deadline) Thread.yield();
            check(neighborObserved.get() == 1,
                  "hot actor monopolized carrier instead of yielding: " + neighborObserved.get());
            scheduler.close();
            check(scheduler.snapshot(a).state() == 41 && scheduler.snapshot(b).state() == 1,
                  "one-carrier fairness probe lost work");
        }
        System.out.println("ATTO_RT_SINGLE_CARRIER_FAIRNESS=PASS");
    }

    public static void main(String[] args) throws Exception {
        runningShutdown();
        singleCarrierFairness();
        System.out.println("ATTO_RT_SHUTDOWN_FAIRNESS_HOST_PROBE=PASS");
    }
}
