package org.attolang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-selector-owner bounded NIO host prototype. Incoming unsigned bytes
 * are admitted to a serial actor through its bounded scheduler mailbox.
 * No guest actor handler is invoked on the selector thread.
 *
 * HOST_ONLY: Java monitors, callback behavior and ordinary OS scheduling do
 * not establish hard-real-time/WCET or compiled .atto guest support.
 */
public final class BoundedNioReactor implements AutoCloseable {
    public enum Status {
        ACCEPTED, CAPACITY, COMMAND_QUEUE_FULL, SHUTTING_DOWN,
        STALE_GENERATION, ALREADY_CLOSING
    }
    public record Ticket(int slot, long generation) {}
    public record Admission(Status status, Ticket ticket) {}
    public record Snapshot(long admittedBytes, long pausedReadCount,
                           long readEvents, long cancelledRegistrations,
                           long refusedCommands, long closedSockets,
                           long selectorLoops, long fatalFailures,
                           int openSlots, int queuedCommands,
                           boolean shutdown, boolean terminated) {}
    public record Options(int maxRegistrations, int commandCapacity,
                          int maxCommandsPerTick, int maxReadyPerTick,
                          int maxBytesPerRead, int maxPausedChecksPerTick,
                          int selectTimeoutMillis) {
        public Options {
            if (maxRegistrations < 1 || maxRegistrations > 4096 ||
                commandCapacity < 1 || commandCapacity > 4096 ||
                maxCommandsPerTick < 1 || maxCommandsPerTick > commandCapacity ||
                maxReadyPerTick < 1 || maxReadyPerTick > maxRegistrations ||
                maxBytesPerRead < 1 || maxBytesPerRead > 65536 ||
                maxPausedChecksPerTick < 1 || maxPausedChecksPerTick > maxRegistrations ||
                selectTimeoutMillis < 1 || selectTimeoutMillis > 1000 ||
                (long)maxRegistrations * maxBytesPerRead > 8L*1024*1024)
                throw new IllegalArgumentException("unbounded reactor configuration");
        }
    }
    private enum Kind { REGISTER, CANCEL }
    private record Command(Kind kind, int slot, long generation) {}
    private static final class Slot {
        final int index;
        final ByteBuffer pending;
        SocketChannel channel;
        SelectionKey key;
        SerialCarrierScheduler.Handle target;
        long generation;
        boolean reserved, paused;
        Slot(int index, int bytes) {
            this.index = index;
            pending = ByteBuffer.allocate(bytes);
            pending.limit(0);
        }
    }

    private final SerialCarrierScheduler scheduler;
    private final Options options;
    private final Object gate = new Object();
    private final Selector selector;
    private final Thread owner;
    private final Slot[] slots;
    private final Command[] commands;
    private int cmdHead, cmdTail, cmdCount, openSlots, pausedScan;
    private boolean shutdown, terminated;
    private final AtomicLong admittedBytes = new AtomicLong();
    private final AtomicLong pausedReadCount = new AtomicLong();
    private final AtomicLong readEvents = new AtomicLong();
    private final AtomicLong cancelledRegistrations = new AtomicLong();
    private final AtomicLong refusedCommands = new AtomicLong();
    private final AtomicLong closedSockets = new AtomicLong();
    private final AtomicLong selectorLoops = new AtomicLong();
    private final AtomicLong fatalFailures = new AtomicLong();

    public BoundedNioReactor(SerialCarrierScheduler scheduler, Options options)
            throws IOException {
        if (scheduler == null || options == null)
            throw new IllegalArgumentException("missing scheduler/options");
        this.scheduler = scheduler;
        this.options = options;
        selector = Selector.open();
        slots = new Slot[options.maxRegistrations()];
        commands = new Command[options.commandCapacity()];
        for (int i = 0; i < slots.length; i++)
            slots[i] = new Slot(i, options.maxBytesPerRead());
        owner = new Thread(this::runOwner, "atto-nio-reactor");
        owner.setDaemon(true);
        owner.start();
    }

    /** ACCEPTED transfers channel ownership, including pending registration. */
    public Admission register(SocketChannel channel, SerialCarrierScheduler.Handle target) {
        if (channel == null || target == null || channel.isBlocking())
            throw new IllegalArgumentException("requires nonblocking socket and actor handle");
        synchronized (gate) {
            if (shutdown) return new Admission(Status.SHUTTING_DOWN, null);
            for (Slot s : slots)
                if (s.reserved && s.channel == channel)
                    throw new IllegalArgumentException("socket already registered");
            if (openSlots == slots.length) return new Admission(Status.CAPACITY, null);
            if (cmdCount == commands.length) {
                refusedCommands.incrementAndGet();
                return new Admission(Status.COMMAND_QUEUE_FULL, null);
            }
            int index = 0;
            while (slots[index].reserved) index++;
            Slot s = slots[index];
            if (s.generation == Long.MAX_VALUE)
                return new Admission(Status.CAPACITY, null);
            s.generation++;
            s.reserved = true;
            s.channel = channel;
            s.target = target;
            s.paused = false;
            s.pending.clear();
            s.pending.limit(0);
            openSlots++;
            offer(new Command(Kind.REGISTER, index, s.generation));
            return new Admission(Status.ACCEPTED, new Ticket(index, s.generation));
        }
    }

    public Status cancel(Ticket ticket) {
        synchronized (gate) {
            if (shutdown) return Status.SHUTTING_DOWN;
            if (lookup(ticket) == null) return Status.STALE_GENERATION;
            if (cmdCount == commands.length) {
                refusedCommands.incrementAndGet();
                return Status.COMMAND_QUEUE_FULL;
            }
            offer(new Command(Kind.CANCEL, ticket.slot(), ticket.generation()));
            return Status.ACCEPTED;
        }
    }

    private Slot lookup(Ticket ticket) {
        if (ticket == null || ticket.slot() < 0 || ticket.slot() >= slots.length)
            return null;
        Slot s = slots[ticket.slot()];
        return s.reserved && s.generation == ticket.generation() ? s : null;
    }

    private void offer(Command c) {
        commands[cmdTail] = c;
        cmdTail = (cmdTail + 1) % commands.length;
        cmdCount++;
        // Publication and wakeup are ordered under gate: no lost registration.
        selector.wakeup();
    }

    public Snapshot snapshot() {
        synchronized (gate) {
            return new Snapshot(admittedBytes.get(), pausedReadCount.get(),
                readEvents.get(), cancelledRegistrations.get(), refusedCommands.get(),
                closedSockets.get(), selectorLoops.get(), fatalFailures.get(),
                openSlots, cmdCount, shutdown, terminated);
        }
    }

    private void drainCommands() {
        for (int i = 0; i < options.maxCommandsPerTick(); i++) {
            Command c;
            synchronized (gate) {
                if (cmdCount == 0 || shutdown) break;
                c = commands[cmdHead];
                commands[cmdHead] = null;
                cmdHead = (cmdHead + 1) % commands.length;
                cmdCount--;
            }
            Slot s = slots[c.slot()];
            if (!s.reserved || s.generation != c.generation()) continue;
            if (c.kind() == Kind.CANCEL) {
                retire(c.slot(), s);
                cancelledRegistrations.incrementAndGet();
            } else {
                try {
                    s.key = s.channel.register(selector, SelectionKey.OP_READ, s);
                } catch (IOException | RuntimeException invalid) {
                    retire(c.slot(), s);
                }
            }
        }
    }

    private void retire(int slot, Slot s) {
        // Retired only by the selector owner, never by an external sender.
        if (s.key != null) s.key.cancel();
        s.key = null;
        try { if (s.channel != null) s.channel.close(); }
        catch (IOException ignored) {}
        s.channel = null;
        s.target = null;
        s.pending.clear();
        s.pending.limit(0);
        s.paused = false;
        closedSockets.incrementAndGet();
        synchronized (gate) {
            if (!s.reserved) throw new AssertionError("double socket retirement");
            s.reserved = false;
            openSlots--;
            gate.notifyAll();
        }
    }

    private void interest(Slot s, boolean enableRead) {
        if (s.key != null && s.key.isValid()) {
            try { s.key.interestOps(enableRead ? SelectionKey.OP_READ : 0); }
            catch (CancelledKeyException ignored) {}
        }
        if (s.paused == enableRead) {
            s.paused = !enableRead;
            if (!enableRead) pausedReadCount.incrementAndGet();
        }
    }

    private void flushPending(int slot, Slot s) {
        while (s.pending.hasRemaining()) {
            int b = Byte.toUnsignedInt(s.pending.get(s.pending.position()));
            SerialCarrierScheduler.SendStatus result = scheduler.try_send(s.target, b);
            if (result == SerialCarrierScheduler.SendStatus.ACCEPTED) {
                s.pending.get();
                admittedBytes.incrementAndGet();
            } else if (result == SerialCarrierScheduler.SendStatus.FULL) {
                // Retain unadmitted bytes and mask OP_READ until credits return.
                interest(s, false);
                return;
            } else {
                // Stale/closed/faulted target: fail closed, do not retarget.
                retire(slot, s);
                return;
            }
        }
        s.pending.clear();
        s.pending.limit(0);
        interest(s, true);
    }

    private void read(SelectionKey key) {
        if (!(key.attachment() instanceof Slot s)) return;
        int slot = s.index;
        if (!s.reserved || s.key != key || !key.isValid()) return;
        if (s.pending.hasRemaining()) {
            flushPending(slot, s);
            if (!s.reserved || s.paused) return;
        }
        s.pending.clear();
        int count;
        try { count = s.channel.read(s.pending); }
        catch (IOException | RuntimeException failure) { retire(slot, s); return; }
        if (count < 0) { retire(slot, s); return; }
        if (count == 0) return;
        readEvents.incrementAndGet();
        s.pending.flip();
        flushPending(slot, s);
    }

    private void scanPaused() {
        for (int i = 0; i < options.maxPausedChecksPerTick(); i++) {
            int slot = pausedScan;
            pausedScan = (pausedScan + 1) % slots.length;
            Slot s = slots[slot];
            if (s.reserved && s.paused && s.pending.hasRemaining())
                flushPending(slot, s);
        }
    }

    private void runOwner() {
        try {
            for (;;) {
                synchronized (gate) { if (shutdown) break; }
                selectorLoops.incrementAndGet();
                drainCommands();
                synchronized (gate) { if (shutdown) break; }
                int count = 0;
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext() && count < options.maxReadyPerTick()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    count++;
                    try { if (key.isValid() && key.isReadable()) read(key); }
                    catch (CancelledKeyException ignored) {}
                }
                scanPaused();
                synchronized (gate) { if (shutdown) break; }
                // Do not block with an unconsumed bounded ready batch.
                if (selector.selectedKeys().isEmpty())
                    selector.select(options.selectTimeoutMillis());
                else selector.selectNow();
            }
        } catch (Throwable fatal) {
            fatalFailures.incrementAndGet();
            synchronized (gate) { shutdown = true; }
        } finally {
            for (int i = 0; i < slots.length; i++)
                if (slots[i].reserved) retire(i, slots[i]);
            synchronized (gate) {
                for (int i = 0; i < commands.length; i++) commands[i] = null;
                cmdHead = cmdTail = cmdCount = 0;
                shutdown = terminated = true;
                gate.notifyAll();
            }
            try { selector.close(); } catch (IOException ignored) {}
        }
    }

    public void request_stop() {
        synchronized (gate) {
            shutdown = true;
            selector.wakeup();
            gate.notifyAll();
        }
    }

    @Override public void close() throws InterruptedException {
        if (Thread.currentThread() == owner)
            throw new IllegalStateException("reactor cannot join its own thread");
        request_stop();
        owner.join(TimeUnit.SECONDS.toMillis(30));
        if (owner.isAlive())
            throw new IllegalStateException("reactor did not stop");
    }
}
