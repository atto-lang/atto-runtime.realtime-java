import org.attolang.runtime.SerialCarrierScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** HOST_ONLY executable regression tests. No compiled AttoLang guest support implied. */
public final class SerialCarrierSchedulerProbe {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
    private static SerialCarrierScheduler.Snapshot awaitCount(
            SerialCarrierScheduler scheduler, SerialCarrierScheduler.Handle handle, long completed)
            throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            var s = scheduler.snapshot(handle);
            check(s != null && s.accounted(), "accounting not exact during concurrent running");
            if (s.processed() >= completed) return s;
            Thread.sleep(1);
        }
        throw new AssertionError("did not complete expected turns " + completed);
    }
    private static void retrySend(SerialCarrierScheduler scheduler,
            SerialCarrierScheduler.Handle handle, long value) {
        for (int i = 0; i < 1_000_000; ++i) {
            var status = scheduler.try_send(handle, value);
            if (status == SerialCarrierScheduler.SendStatus.ACCEPTED) return;
            check(status == SerialCarrierScheduler.SendStatus.FULL, "unexpected send refusal: " + status);
            Thread.onSpinWait();
            if (i % 1024 == 0) Thread.yield();
        }
        throw new AssertionError("bounded mailbox stopped making progress");
    }

    private static void testAdmissionAndNeighbor() throws Exception {
        try (var scheduler = new SerialCarrierScheduler(2, 3, 3)) {
            var entered = new CountDownLatch(1);
            var unblock = new CountDownLatch(1);
            var actor = scheduler.spawn(0, (state, msg) -> {
                if (msg == 1) { entered.countDown(); awaitLatch(unblock); }
                return Math.addExact(state, msg);
            });
            check(scheduler.try_send(actor, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "first turn not admitted");
            awaitLatch(entered);
            check(scheduler.try_send(actor, 2) == SerialCarrierScheduler.SendStatus.ACCEPTED, "second turn");
            check(scheduler.try_send(actor, 3) == SerialCarrierScheduler.SendStatus.ACCEPTED, "third turn");
            check(scheduler.try_send(actor, 4) == SerialCarrierScheduler.SendStatus.ACCEPTED, "fourth turn");
            check(scheduler.try_send(actor, 5) == SerialCarrierScheduler.SendStatus.FULL,
                    "full mailbox did not refuse without admission");
            var inFlight = scheduler.snapshot(actor);
            check(inFlight.inFlight() == 1 && inFlight.queued() == 3 &&
                    inFlight.accepted() == 4 && inFlight.accounted(), "in-flight turn lost");
            var neighbor = scheduler.spawn(0, Math::addExact);
            check(scheduler.try_send(neighbor, 7) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "neighbor admission failed");
            check(awaitCount(scheduler, neighbor, 1).state() == 7,
                    "independent carrier did not serve healthy neighbor");
            unblock.countDown();
            var done = awaitCount(scheduler, actor, 4);
            check(done.state() == 10 && done.accepted() == 4 && done.maxDepth() == 3,
                    "serial actor lost or duplicated turns");
            check(scheduler.request_stop(actor, true) == SerialCarrierScheduler.StopStatus.STOP_REQUESTED,
                    "stop request refused");
            scheduler.await_retired(actor, 15, TimeUnit.SECONDS);
            check(!scheduler.snapshot(actor).active(), "actor not retired");
            var successor = scheduler.spawn(99, Math::addExact);
            check(successor.slot() == actor.slot() && successor.generation() != actor.generation(),
                    "slot reuse lacks generation fencing");
            check(scheduler.try_send(actor, 100) == SerialCarrierScheduler.SendStatus.STALE_GENERATION,
                    "stale handle reached successor actor");
            check(scheduler.try_send(successor, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "successor admission failed");
            check(awaitCount(scheduler, successor, 1).state() == 100,
                    "reused actor slot inherited predecessor state");
        }
        System.out.println("ATTO_RT_ADMISSION_AND_NEIGHBOR=PASS");
    }

    private static void testAbortAndControl() throws Exception {
        try (var scheduler = new SerialCarrierScheduler(2, 2, 4)) {
            var entered = new CountDownLatch(1);
            var unblock = new CountDownLatch(1);
            var actor = scheduler.spawn(0, (state, msg) -> {
                entered.countDown(); awaitLatch(unblock);
                return Math.addExact(state, msg);
            });
            check(scheduler.try_send(actor, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED, "abort setup 1");
            awaitLatch(entered);
            check(scheduler.try_send(actor, 2) == SerialCarrierScheduler.SendStatus.ACCEPTED, "abort setup 2");
            check(scheduler.try_send(actor, 3) == SerialCarrierScheduler.SendStatus.ACCEPTED, "abort setup 3");
            check(scheduler.request_stop(actor, false) == SerialCarrierScheduler.StopStatus.STOP_REQUESTED,
                    "abort request refused");
            var snap = scheduler.snapshot(actor);
            check(snap.inFlight() == 1 && snap.abandoned() == 2 && snap.accounted(),
                    "abort must abandon only queued turns, not the running turn");
            unblock.countDown();
            scheduler.await_retired(actor, 15, TimeUnit.SECONDS);
            snap = scheduler.snapshot(actor);
            check(snap.state() == 1 && snap.processed() == 1 && snap.abandoned() == 2 &&
                    snap.accepted() == 3 && snap.accounted() && !snap.active(),
                    "abort retirement did not reconcile all accepted work");
            check(scheduler.try_send(actor, 4) == SerialCarrierScheduler.SendStatus.CLOSED,
                    "stopped actor accepted work");

            var failEntered = new CountDownLatch(1);
            var failUnblock = new CountDownLatch(1);
            var broken = scheduler.spawn(77, (state, msg) -> {
                failEntered.countDown(); awaitLatch(failUnblock);
                throw new ArithmeticException("synthetic actor fault");
            });
            check(scheduler.try_send(broken, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "fault setup first admission");
            awaitLatch(failEntered);
            check(scheduler.try_send(broken, 2) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                    "fault setup second admission");
            failUnblock.countDown();
            scheduler.await_retired(broken, 15, TimeUnit.SECONDS);
            snap = scheduler.snapshot(broken);
            check(snap.state() == 77 && snap.accepted() == 2 && snap.failedTurns() == 1 &&
                    snap.abandoned() == 1 && snap.faulted() && snap.accounted(),
                    "fault leaked a partial state mutation or unaccounted mail");
            var control = scheduler.poll_control();
            check(control != null && control.actor().equals(broken) &&
                    control.kind().equals("WORKER_FAILURE") && scheduler.poll_control() == null,
                    "supervisor CONTROL notice absent, duplicated or misrouted");
            check(scheduler.try_send(broken, 3) == SerialCarrierScheduler.SendStatus.FAULTED,
                    "failed actor admitted further turns");
        }
        System.out.println("ATTO_RT_ABORT_AND_SUPERVISOR_CONTROL=PASS");
    }

    private static void testControlEscalation() throws Exception {
        try (var scheduler = new SerialCarrierScheduler(2, 1, 2)) {
            for (int i = 0; i < 4; ++i) {
                var actor = scheduler.spawn(i, (state, msg) -> {
                    throw new IllegalStateException("synthetic failure");
                });
                check(scheduler.try_send(actor, 1) == SerialCarrierScheduler.SendStatus.ACCEPTED,
                        "failure admission failed");
                scheduler.await_retired(actor, 15, TimeUnit.SECONDS);
                check(scheduler.snapshot(actor).accounted(), "fault accounting mismatch");
            }
            check(scheduler.control_escalations() == 3,
                    "full CONTROL ring failed to expose explicit escalation");
            check(scheduler.poll_control() != null && scheduler.poll_control() == null,
                    "CONTROL capacity bound violated");
        }
        System.out.println("ATTO_RT_CONTROL_OVERFLOW_ESCALATES=PASS");
    }

    private static void testSerialCarriersAndShutdown() throws Exception {
        var scheduler = new SerialCarrierScheduler(4, 4, 64);
        try {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            var actor = scheduler.spawn(0, (state, msg) -> {
                int concurrent = active.incrementAndGet();
                maxActive.accumulateAndGet(concurrent, Math::max);
                try {
                    check(concurrent == 1, "same actor ran on two carriers at once");
                    if ((state & 31) == 0) Thread.yield();
                    return Math.addExact(state, msg);
                } finally {
                    active.decrementAndGet();
                }
            });
            var neighbor = scheduler.spawn(0, Math::addExact);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread[] producers = new Thread[4];
            for (int t = 0; t < producers.length; ++t) {
                producers[t] = new Thread(() -> {
                    try { for (int i = 0; i < 3000; ++i) retrySend(scheduler, actor, 1); }
                    catch (Throwable problem) { error.compareAndSet(null, problem); }
                }, "atto-sender-" + t);
                producers[t].start();
            }
            for (int i = 0; i < 200; ++i) retrySend(scheduler, neighbor, 1);
            check(awaitCount(scheduler, neighbor, 200).state() == 200,
                    "healthy neighbor starved during hot actor traffic");
            for (Thread producer : producers) {
                producer.join(15000);
                check(!producer.isAlive(), "producer stuck under finite backlog");
            }
            check(error.get() == null, "producer failed: " + error.get());
            var snap = awaitCount(scheduler, actor, 12000);
            check(snap.state() == 12000 && snap.accepted() == 12000 &&
                    snap.processed() == 12000 && snap.accounted() &&
                    snap.inFlight() == 0 && maxActive.get() == 1,
                    "serial lease violated or actor lost admitted turns");
            check(scheduler.carrier_count() == 4 && snap.lastCarrierThread() !=
                    Thread.currentThread().getId(), "incorrect carrier topology");
            // Migration is permitted between turns, but not required in each test.
            System.out.println("ATTO_RT_ACTOR_MIGRATION_OBSERVED=" + snap.migrated());
            scheduler.close();
            scheduler.close();
            check(!scheduler.snapshot(actor).active(), "idle actor not retired on shutdown");
            check(scheduler.try_send(actor, 1) == SerialCarrierScheduler.SendStatus.SHUTTING_DOWN,
                    "shutdown admitted a turn");
        } finally { scheduler.close(); }
        System.out.println("ATTO_RT_SERIAL_CARRIER_POOL=PASS");
        System.out.println("ATTO_RT_SHUTDOWN_DRAIN=PASS");
    }

    public static void main(String[] args) throws Exception {
        testAdmissionAndNeighbor();
        testAbortAndControl();
        testControlEscalation();
        testSerialCarriersAndShutdown();
        System.out.println("ATTO_RT_HOST_REFERENCE_PROBE=PASS");
        System.out.println("ATTO_RT_COMPILED_GUEST=NOT_EXECUTED");
        System.out.println("ATTO_RT_HARD_REALTIME=NOT_CERTIFIED");
    }
}
