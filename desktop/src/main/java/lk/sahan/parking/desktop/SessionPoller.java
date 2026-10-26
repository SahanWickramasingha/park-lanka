package lk.sahan.parking.desktop;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
/** A single scheduler per login, shared by broadcasts and notifications. Failed delivery backs off to 60s. */
final class SessionPoller implements AutoCloseable {
    interface Job { void run() throws Exception; }
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{ Thread t=new Thread(r,"parking-notifications"); t.setDaemon(true); return t; });
    private final List<Job> jobs=new CopyOnWriteArrayList<>(); private final BooleanSupplier active;
    private volatile boolean closed; private boolean started,running,refreshRequested; private int failures; private ScheduledFuture<?> scheduled;
    SessionPoller(BooleanSupplier active) { this.active=active; }
    void add(Job job) { jobs.add(job); }
    synchronized void start() { if(!closed&&!started) { started=true; scheduled=worker.schedule(this::tick,0,TimeUnit.SECONDS); } }
    synchronized void refresh() { if(closed||!started) return; if(running){refreshRequested=true;return;} if(scheduled!=null&&!scheduled.isDone()) scheduled.cancel(false); scheduled=worker.schedule(this::tick,0,TimeUnit.SECONDS); }
    private void tick() {
        if(closed||!active.getAsBoolean()) return;
        synchronized(this){if(closed)return;running=true;}
        boolean failed=false;
        for(Job job:jobs) { if(closed||!active.getAsBoolean()) return; try { job.run(); } catch(Exception ex) { failed=true; } }
        synchronized(this) { running=false;if(closed) return; failures=failed?Math.min(3,failures+1):0;long delay=refreshRequested?0:Math.min(60,10L*(1L<<failures));refreshRequested=false;scheduled=worker.schedule(this::tick,delay,TimeUnit.SECONDS); }
    }
    @Override public synchronized void close() { closed=true; jobs.clear(); if(scheduled!=null) scheduled.cancel(true); worker.shutdownNow(); }
}
