package org.attolang.runtime;

import java.util.concurrent.TimeUnit;
import java.util.function.LongBinaryOperator;

/**
 * Bounded carrier-pool host reference runtime for serial actors.
 *
 * A serial actor has ONE execution lease at a time, not a dedicated thread.
 * Any carrier may acquire the next turn's lease. No allocation is required on
 * the try_send/admission or worker dequeue path after actor creation.
 *
 * NOT a production NIO reactor, hostile guest sandbox, compiler-emitted .atto
 * runtime, or hard-RT implementation. Arbitrary callbacks are unbounded.
 */
public final class SerialCarrierScheduler implements AutoCloseable {
    public enum SendStatus { ACCEPTED, FULL, CLOSED, STALE_GENERATION, SHUTTING_DOWN, FAULTED }
    public enum StopStatus { STOP_REQUESTED, ALREADY_STOPPED, STALE_GENERATION }
    public record Handle(int slot, long generation) {}
    public record ControlNotice(long sequence, Handle actor, String kind) {}
    public record Snapshot(
        long generation, long accepted, long processed, long failedTurns,
        long abandoned, int queued, int inFlight, int maxDepth,
        long state, boolean active, boolean stopping, boolean faulted,
        long lastCarrierThread, boolean migrated
    ) {
        public boolean accounted() {
            return accepted == processed + failedTurns + abandoned + queued + inFlight;
        }
    }

    private static final int MAX_ACTORS = 4096;
    private static final int MAX_PER_MAILBOX = 4096;
    private static final int MAX_CARRIERS = 64;
    private static final long MAX_PREALLOCATED_MESSAGES = 1_048_576L;

    private static final class Actor {
        final long[] mail;
        long generation;
        LongBinaryOperator handler;
        long state;
        int head, tail, queued, inFlight, maxDepth;
        long accepted, processed, failedTurns, abandoned;
        boolean active, running, ready, stopping, faulted, migrated;
        long lastCarrierThread;

        Actor(int capacity) { mail = new long[capacity]; }
        void reset(long state, LongBinaryOperator handler) {
            if (generation == Long.MAX_VALUE) throw new IllegalStateException("actor generation exhausted");
            generation++;
            this.handler = handler;
            this.state = state;
            head = tail = queued = inFlight = maxDepth = 0;
            accepted = processed = failedTurns = abandoned = 0;
            running = ready = stopping = faulted = migrated = false;
            lastCarrierThread = 0;
            active = true;
        }
    }

    private final Object gate = new Object();
    private final Actor[] actors;
    private final Thread[] carriers;
    private final int[] readySlots;
    private final long[] readyGenerations;
    private final int[] controlSlots;
    private final long[] controlGenerations;
    private final long[] controlSequences;
    private final int mailboxCapacity;
    private int readyHead, readyTail, readyCount;
    private int ctrlHead, ctrlTail, ctrlCount;
    private long nextControlSequence;
    private long lostControlEscalations;
    private int inFlightTotal;
    private boolean shuttingDown, closed;

    public SerialCarrierScheduler(int carrierCount, int maxActors, int mailboxCapacity) {
        if (carrierCount < 1 || carrierCount > MAX_CARRIERS ||
            maxActors < 1 || maxActors > MAX_ACTORS ||
            mailboxCapacity < 1 || mailboxCapacity > MAX_PER_MAILBOX ||
            (long) maxActors * mailboxCapacity > MAX_PREALLOCATED_MESSAGES) {
            throw new IllegalArgumentException("carrier/actor/mailbox capacity outside preallocation bounds");
        }
        this.mailboxCapacity = mailboxCapacity;
        this.actors = new Actor[maxActors];
        this.carriers = new Thread[carrierCount];
        readySlots = new int[maxActors];
        readyGenerations = new long[maxActors];
        controlSlots = new int[maxActors];
        controlGenerations = new long[maxActors];
        controlSequences = new long[maxActors];
        for (int i = 0; i < maxActors; ++i) actors[i] = new Actor(mailboxCapacity);
        for (int i = 0; i < carrierCount; ++i) {
            carriers[i] = new Thread(this::runCarrier, "atto-carrier-" + i);
            carriers[i].setDaemon(true);
            carriers[i].start();
        }
    }

    public Handle spawn(long initialState, LongBinaryOperator handler) {
        if (handler == null) throw new IllegalArgumentException("handler required");
        synchronized (gate) {
            if (shuttingDown) throw new IllegalStateException("scheduler shutting down");
            for (int i = 0; i < actors.length; ++i) {
                Actor actor = actors[i];
                if (!actor.active && !actor.running && !actor.ready) {
                    actor.reset(initialState, handler);
                    return new Handle(i, actor.generation);
                }
            }
            throw new IllegalStateException("actor capacity exhausted");
        }
    }

    private Actor checkedSlot(Handle handle) {
        if (handle == null || handle.slot() < 0 || handle.slot() >= actors.length) return null;
        return actors[handle.slot()];
    }

    private void schedule(int slot, Actor actor) {
        if (actor.ready || actor.running || actor.queued == 0) return;
        // One enqueue token per active actor; hence readyCount <= maxActors.
        if (readyCount >= readySlots.length) throw new AssertionError("runnable ring over capacity");
        readySlots[readyTail] = slot;
        readyGenerations[readyTail] = actor.generation;
        readyTail = (readyTail + 1) % readySlots.length;
        readyCount++;
        actor.ready = true;
        gate.notifyAll();
    }

    public SendStatus try_send(Handle handle, long message) {
        synchronized (gate) {
            Actor actor = checkedSlot(handle);
            if (actor == null || actor.generation != handle.generation()) return SendStatus.STALE_GENERATION;
            if (actor.faulted) return SendStatus.FAULTED;
            if (shuttingDown) return SendStatus.SHUTTING_DOWN;
            if (!actor.active || actor.stopping) return SendStatus.CLOSED;
            if (actor.queued == mailboxCapacity) return SendStatus.FULL;
            actor.mail[actor.tail] = message;
            actor.tail = (actor.tail + 1) % mailboxCapacity;
            actor.queued++;
            actor.accepted++;
            actor.maxDepth = Math.max(actor.maxDepth, actor.queued);
            schedule(handle.slot(), actor);
            return SendStatus.ACCEPTED;
        }
    }

    // Bounded O(maxActors) ring removal under the gate; not a hard-RT proof.
    private void unschedule(int slot, long generation) {
        int n = readyCount, destination = 0;
        for (int j = 0; j < n; ++j) {
            int index = (readyHead + j) % readySlots.length;
            int s = readySlots[index];
            long g = readyGenerations[index];
            if (s == slot && g == generation) continue;
            int target = (readyHead + destination++) % readySlots.length;
            readySlots[target] = s;
            readyGenerations[target] = g;
        }
        readyCount = destination;
        readyTail = (readyHead + readyCount) % readySlots.length;
        actors[slot].ready = false;
    }

    public StopStatus request_stop(Handle handle, boolean drain) {
        synchronized (gate) {
            Actor a = checkedSlot(handle);
            if (a == null || a.generation != handle.generation()) return StopStatus.STALE_GENERATION;
            if (!a.active) return StopStatus.ALREADY_STOPPED;
            a.stopping = true;
            if (!drain && a.queued > 0) {
                a.abandoned += a.queued;
                a.queued = 0;
                a.head = a.tail;
                if (a.ready) unschedule(handle.slot(), a.generation);
            }
            if (!a.running && a.queued == 0) a.active = false;
            else if (!a.running) schedule(handle.slot(), a);
            gate.notifyAll();
            return StopStatus.STOP_REQUESTED;
        }
    }

    private void putControl(int slot, Actor a) {
        ++nextControlSequence;
        if (ctrlCount == controlSlots.length) {
            // Fail closed: explicit escalation, never silently lose the failure.
            ++lostControlEscalations;
            return;
        }
        controlSlots[ctrlTail] = slot;
        controlGenerations[ctrlTail] = a.generation;
        controlSequences[ctrlTail] = nextControlSequence;
        ctrlTail = (ctrlTail + 1) % controlSlots.length;
        ctrlCount++;
        gate.notifyAll();
    }

    // Supervisor-only API; guest actors must never receive CONTROL notices.
    public ControlNotice poll_control() {
        synchronized (gate) {
            if (ctrlCount == 0) return null;
            int slot = controlSlots[ctrlHead];
            long gen = controlGenerations[ctrlHead];
            long seq = controlSequences[ctrlHead];
            ctrlHead = (ctrlHead + 1) % controlSlots.length;
            --ctrlCount;
            return new ControlNotice(seq, new Handle(slot, gen), "WORKER_FAILURE");
        }
    }

    public long control_escalations() {
        synchronized (gate) { return lostControlEscalations; }
    }

    public Snapshot snapshot(Handle handle) {
        synchronized (gate) {
            Actor a = checkedSlot(handle);
            if (a == null || a.generation != handle.generation()) return null;
            return new Snapshot(a.generation, a.accepted, a.processed, a.failedTurns,
                    a.abandoned, a.queued, a.inFlight, a.maxDepth, a.state,
                    a.active, a.stopping, a.faulted, a.lastCarrierThread, a.migrated);
        }
    }

    private void runCarrier() {
        while (true) {
            int slot;
            long generation, message, oldState;
            LongBinaryOperator handler;
            synchronized (gate) {
                for (;;) {
                    if (readyCount > 0) break;
                    if (shuttingDown && inFlightTotal == 0) return;
                    try { gate.wait(); }
                    catch (InterruptedException ignored) {
                        // Wakeup only; external interrupts cannot steal a turn.
                    }
                }
                slot = readySlots[readyHead];
                generation = readyGenerations[readyHead];
                readyHead = (readyHead + 1) % readySlots.length;
                --readyCount;
                Actor a = actors[slot];
                if (a.generation != generation || !a.ready || !a.active || a.queued == 0) {
                    // A failed actor may have been retired/cancelled while ready.
                    continue;
                }
                if (a.running) throw new AssertionError("serial actor acquired concurrently");
                a.ready = false;
                a.running = true;
                a.inFlight = 1;
                ++inFlightTotal;
                message = a.mail[a.head];
                a.head = (a.head + 1) % mailboxCapacity;
                --a.queued;
                long workerId = Thread.currentThread().threadId();
                if (a.lastCarrierThread != 0 && a.lastCarrierThread != workerId) a.migrated = true;
                a.lastCarrierThread = workerId;
                oldState = a.state;
                handler = a.handler;
            }
            long computed = oldState;
            Throwable failure = null;
            try { computed = handler.applyAsLong(oldState, message); }
            catch (Throwable t) { failure = t; }
            synchronized (gate) {
                Actor a = actors[slot];
                if (a.generation != generation || !a.running || a.inFlight != 1)
                    throw new AssertionError("actor lease corrupted");
                a.inFlight = 0;
                --inFlightTotal;
                a.running = false;
                if (failure == null) {
                    a.state = computed;
                    ++a.processed;
                } else {
                    ++a.failedTurns;
                    a.faulted = true;
                    a.stopping = true;
                    a.abandoned += a.queued;
                    a.queued = 0;
                    a.head = a.tail;
                    putControl(slot, a);
                }
                if (a.queued > 0 && !a.faulted) schedule(slot, a);
                if (a.stopping && a.queued == 0) a.active = false;
                gate.notifyAll();
            }
        }
    }

    public void await_retired(Handle handle, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (gate) {
            for (;;) {
                Actor a = checkedSlot(handle);
                if (a == null || a.generation != handle.generation() || !a.active) return;
                long remain = deadline - System.nanoTime();
                if (remain <= 0) throw new IllegalStateException("actor retirement timed out");
                TimeUnit.NANOSECONDS.timedWait(gate, remain);
            }
        }
    }

    public int carrier_count() { return carriers.length; }
    public int max_actor_count() { return actors.length; }

    /** Finite join watchdog; may throw if a host callback blocks indefinitely. */
    @Override public void close() throws InterruptedException {
        for (Thread carrier : carriers) {
            if (Thread.currentThread() == carrier)
                throw new IllegalStateException("carrier cannot synchronously join itself");
        }
        synchronized (gate) {
            if (closed) return;
            shuttingDown = true;
            for (Actor a : actors) if (a.active) {
                a.stopping = true;
                if (!a.running && a.queued == 0) a.active = false;
            }
            gate.notifyAll();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (Thread carrier : carriers) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0) throw new IllegalStateException("carrier shutdown timed out");
            TimeUnit.NANOSECONDS.timedJoin(carrier, remain);
            if (carrier.isAlive()) throw new IllegalStateException("carrier failed to stop");
        }
        synchronized (gate) { closed = true; gate.notifyAll(); }
    }
}
