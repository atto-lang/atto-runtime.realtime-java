import org.attolang.runtime.BoundedNioReactor;
import org.attolang.runtime.SerialCarrierScheduler;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual loopback IO + real SerialCarrierScheduler host integration. */
public final class BoundedNioReactorProbe {
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(10, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
    @FunctionalInterface private interface Condition {
        boolean get();
    }
    private static void eventually(Condition condition, String why) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(2);
        }
        throw new AssertionError(why);
    }
    private static BoundedNioReactor.Options opts(int maxRegistrations) {
        return new BoundedNioReactor.Options(maxRegistrations, 8, 2, 1, 3, 1, 2);
    }
    private record Pair(SocketChannel receiver, SocketChannel sender)
            implements AutoCloseable {
        @Override public void close() throws Exception {
            sender.close();
            receiver.close();
        }
    }
    private static Pair pair() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel sender = SocketChannel.open(listener.getLocalAddress());
            SocketChannel receiver = listener.accept();
            receiver.configureBlocking(false);
            return new Pair(receiver, sender);
        }
    }
    private static void write(SocketChannel socket, byte... bytes) throws Exception {
        ByteBuffer data = ByteBuffer.wrap(bytes);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (data.hasRemaining() && System.nanoTime() < deadline)
            socket.write(data);
        check(!data.hasRemaining(), "sender stalled");
    }

    private static void pressureAndDelivery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        try (var scheduler = new SerialCarrierScheduler(2, 2, 1);
             var reactor = new BoundedNioReactor(scheduler, opts(2));
             var socket = pair()) {
            var actor = scheduler.spawn(0, (state, msg) -> {
                if (state == 0) {
                    entered.countDown();
                    await(unblock);
                }
                return Math.addExact(state, msg);
            });
            var registered = reactor.register(socket.receiver(), actor);
            check(registered.status() == BoundedNioReactor.Status.ACCEPTED,
                  "nonblocking registration refused");
            write(socket.sender(), (byte) 1);
            await(entered);
            write(socket.sender(), (byte) 2, (byte) 3,
                  (byte) 4, (byte) 5, (byte) 6);
            eventually(() -> reactor.snapshot().pausedReadCount() > 0,
                  "read interest not masked when actor mailbox filled");
            check(reactor.snapshot().admittedBytes() <= 2,
                  "reactor overcommitted full mailbox");
            unblock.countDown();
            eventually(() -> {
                var snapshot = scheduler.snapshot(actor);
                return snapshot != null && snapshot.processed() == 6;
            }, "read bytes not delivered after backpressure");
            var done = scheduler.snapshot(actor);
            check(done.state() == 21 && done.accounted() &&
                  reactor.snapshot().admittedBytes() == 6,
                  "byte order, state or accounting corrupted");
            check(reactor.snapshot().fatalFailures() == 0, "reactor owner faulted");
            check(reactor.cancel(registered.ticket()) == BoundedNioReactor.Status.ACCEPTED,
                  "cancel refused");
            eventually(() -> reactor.snapshot().openSlots() == 0,
                  "cancel not applied by selector owner");
        }
        System.out.println("ATTO_NIO_BACKPRESSURE_NO_LOSS=PASS");
    }

    private static void generationAndCapacity() throws Exception {
        try (var scheduler = new SerialCarrierScheduler(1, 2, 8);
             var reactor = new BoundedNioReactor(scheduler, opts(1));
             var first = pair();
             var second = pair()) {
            var actor = scheduler.spawn(0, Math::addExact);
            var old = reactor.register(first.receiver(), actor);
            check(old.status() == BoundedNioReactor.Status.ACCEPTED,
                  "first registration refused");
            var rejected = reactor.register(second.receiver(), actor);
            check(rejected.status() == BoundedNioReactor.Status.CAPACITY &&
                  second.receiver().isOpen(),
                  "capacity refusal stole caller-owned descriptor");
            check(reactor.cancel(old.ticket()) == BoundedNioReactor.Status.ACCEPTED,
                  "cancel first registration failed");
            eventually(() -> reactor.snapshot().openSlots() == 0,
                  "first descriptor not retired");
            var fresh = reactor.register(second.receiver(), actor);
            check(fresh.status() == BoundedNioReactor.Status.ACCEPTED,
                  "second registration refused");
            check(old.ticket().slot() == fresh.ticket().slot() &&
                  fresh.ticket().generation() > old.ticket().generation(),
                  "slot generation not advanced");
            check(reactor.cancel(old.ticket()) == BoundedNioReactor.Status.STALE_GENERATION,
                  "stale cancellation reached reused descriptor");
            write(second.sender(), (byte) 7);
            eventually(() -> scheduler.snapshot(actor).processed() == 1,
                  "second registration did not dispatch data");
            check(scheduler.snapshot(actor).state() == 7,
                  "stale handle affected successor slot");
        }
        System.out.println("ATTO_NIO_DESCRIPTOR_GENERATION_FENCE=PASS");
    }

    private static void concurrentShutdown() throws Exception {
        var scheduler = new SerialCarrierScheduler(2, 3, 32);
        var reactor = new BoundedNioReactor(scheduler, opts(3));
        try (var first = pair(); var second = pair()) {
            var actor = scheduler.spawn(0, Math::addExact);
            check(reactor.register(first.receiver(), actor).status() ==
                    BoundedNioReactor.Status.ACCEPTED, "shutdown registration 1");
            check(reactor.register(second.receiver(), actor).status() ==
                    BoundedNioReactor.Status.ACCEPTED, "shutdown registration 2");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable closer = () -> {
                try { reactor.close(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
            };
            Thread a = new Thread(closer, "atto-nio-close-1");
            Thread b = new Thread(closer, "atto-nio-close-2");
            a.start();
            b.start();
            a.join(10000);
            b.join(10000);
            check(!a.isAlive() && !b.isAlive() && failure.get() == null,
                  "concurrent reactor shutdown deadlocked");
            var finished = reactor.snapshot();
            check(finished.terminated() && finished.openSlots() == 0 &&
                  finished.queuedCommands() == 0 && finished.closedSockets() == 2,
                  "reactor shutdown leaked registrations or commands");
            check(reactor.register(first.receiver(), actor).status() ==
                    BoundedNioReactor.Status.SHUTTING_DOWN,
                  "late registration accepted after close");
        } finally {
            reactor.close();
            scheduler.close();
        }
        System.out.println("ATTO_NIO_CONCURRENT_SHUTDOWN=PASS");
    }
    public static void main(String[] args) throws Exception {
        pressureAndDelivery();
        generationAndCapacity();
        concurrentShutdown();
        System.out.println("ATTO_NIO_LOOPBACK_HOST_PROBE=PASS");
        System.out.println("ATTO_NIO_COMPILED_GUEST=NOT_EXECUTED");
        System.out.println("ATTO_NIO_HARD_REALTIME=NOT_CERTIFIED");
    }
}
