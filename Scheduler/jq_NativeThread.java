// jq_NativeThread.java, created Mon Apr  9  1:52:50 2001 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Scheduler;

import Allocator.CodeAllocator;
import Allocator.HeapAllocator;
import Allocator.RuntimeCodeAllocator;
import Allocator.SimpleAllocator;
import Assembler.x86.x86Constants;
import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_InstanceMethod;
import Clazz.jq_StaticField;
import Clazz.jq_StaticMethod;
import GC.GCManager;
import GC.GCVisitor;
import Main.jq;
import Memory.CodeAddress;
import Memory.HeapAddress;
import Memory.StackAddress;
import Memory.Heap.SegregatedListHeap;
import Memory.Heap.SizeControl;
import Run_Time.StackCodeWalker;
import Run_Time.SystemInterface;
import Run_Time.Unsafe;
import Util.Assert;
import Util.Strings;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: jq_NativeThread.java,v 1.37 2003/08/09 22:43:57 joewhaley Exp $
 */

public class jq_NativeThread implements x86Constants {

    /** Trace flag. */
    public static /*final*/ boolean TRACE = false;

    /** Data structure to represent the native thread that exists at virtual machine startup. */
    public static final jq_NativeThread initial_native_thread = new jq_NativeThread(0);

    /**
     * Initialize the initial native thread.
     * Must be the first thing called at virtual machine startup.
     */
    public static void initInitialNativeThread() {
        initial_native_thread.pid = SystemInterface.init_thread();
        Unsafe.setThreadBlock(initial_native_thread.schedulerThread);
        initial_native_thread.thread_handle = SystemInterface.get_current_thread_handle();
        initial_native_thread.myHeapAllocator.init();
        initial_native_thread.myCodeAllocator.init();
        if (TRACE) SystemInterface.debugwriteln("Initial native thread initialized");
    }

    /** An array of all native threads. */
    public static jq_NativeThread[] native_threads;

    /** Number of Java threads that are currently active.
     *  When this equals the number of active daemon threads, it is time to shut down.
     */
    private static volatile int num_of_java_threads = 0;
    /** Number of daemon threads that are currently active.
     *  Daemon threads do not keep the VM running.
     */
    private static volatile int num_of_daemon_threads = 0;

    /** Handle for this native thread. */
    /** NOTE: C code relies on this field being first. */
    private int thread_handle;

    /** Pointer to the Java thread that is currently executing on this native thread. */
    /** NOTE: C code relies on this field being second. */
    private jq_Thread currentThread;

    /** Process ID for this native thread. */
    /** NOTE: C code relies on this field being third. */
    private int pid;

    /** Queue of ready Java threads. */
    private final jq_ThreadQueue readyQueue;
    /** Queue of idle Java threads. */
    private final jq_ThreadQueue idleQueue;
    /** Queue of Java threads transferred from another native thread. */
    private final jq_SynchThreadQueue transferQueue;

    /** Thread-local allocators. */
    private CodeAllocator myCodeAllocator;
    private HeapAllocator myHeapAllocator;

    /** Original thread's stack pointer and base pointer.
     *  These are used for longjmp'ing back to schedulerLoop when the currently
     *  executing Java thread exits.
     */
    StackAddress original_esp, original_ebp;

    /** This static variable is set to an idle native thread, or null if there are no idle native threads. */
    private static volatile jq_NativeThread idleThread;

    /** The index of this native thread. */
    private final int index;

    /** The Java thread that is executing while we are in the scheduler. */
    private final jq_Thread schedulerThread;

    public SizeControl[] sizes;
    public SizeControl[] GC_INDEX_ARRAY;
    public SegregatedListHeap backingSLHeap;

    public static final int MAX_NATIVE_THREADS = 16;

    private static boolean all_native_threads_initialized;
    private static boolean all_native_threads_started;

    /** Initialize the extra native threads.
     */
    public static void initNativeThreads(jq_NativeThread nt, int num) {
        Assert._assert(num <= MAX_NATIVE_THREADS);
        native_threads = new jq_NativeThread[num];
        native_threads[0] = nt;
        for (int i = 1; i < num; ++i) {
            jq_NativeThread nt2 = native_threads[i] = new jq_NativeThread(i);
            nt2.thread_handle = SystemInterface.create_thread(_nativeThreadEntry.getDefaultCompiledVersion().getEntrypoint(), HeapAddress.addressOf(nt2));
            nt2.myHeapAllocator.init();
            nt2.myCodeAllocator.init();
            if (TRACE) SystemInterface.debugwriteln("Native thread " + i + " initialized");
        }
        all_native_threads_initialized = true;
    }

    /** Start up the extra native threads.
     */
    public static void startNativeThreads() {
        for (int i = 1; i < native_threads.length; ++i) {
            if (TRACE) SystemInterface.debugwriteln("Native thread " + i + " started");
            native_threads[i].resume();
        }
        all_native_threads_started = true;
    }

    public static boolean allNativeThreadsInitialized() {
        return all_native_threads_initialized;
    }
    
    public jq_Thread getCurrentThread() {
        return currentThread;
    }

    /** Get the native thread-local code allocator. */
    public CodeAllocator getCodeAllocator() {
        return myCodeAllocator;
    }

    /** Get the native thread-local heap allocator. */
    public HeapAllocator getHeapAllocator() {
        return myHeapAllocator;
    }

    /** Get the currently-executing Java thread. */
    public jq_Thread getCurrentJavaThread() {
        return currentThread;
    }

    /** Create a new jq_NativeThread (only called from initNativeThreads(),
     *  and during bootstrap initialization of initial_native_thread and
     *  break_nthread field) */
    private jq_NativeThread(int i) {
        readyQueue = new jq_ThreadQueue();
        idleQueue = new jq_ThreadQueue();
        transferQueue = new jq_SynchThreadQueue();
        myHeapAllocator = new SimpleAllocator();
        myCodeAllocator = new RuntimeCodeAllocator();
        index = i;
        Thread t = new Thread("_scheduler_" + i);
        currentThread = schedulerThread = ThreadUtils.getJQThread(t);
        if (schedulerThread != null) {
            schedulerThread.disableThreadSwitch(); // don't preempt while in the scheduler
            schedulerThread.setNativeThread(this);
        } else {
            // schedulerThread is null if we are not native/bootstrapping.
            Assert._assert(!jq.IsBootstrapping && !jq.RunningNative);
        }
    }

    /** Create a new jq_NativeThread that is tied to a specific jq_Thread. */
    jq_NativeThread(jq_Thread t) {
        readyQueue = null;
        idleQueue = null;
        transferQueue = null;
        myHeapAllocator = new SimpleAllocator();
        myCodeAllocator = new RuntimeCodeAllocator();
        index = -1;
        currentThread = schedulerThread = t;
        t.setNativeThread(this);
    }

    /** Starts up/resumes this native thread. */
    public void resume() {
        SystemInterface.resume_thread(thread_handle);
    }

    /** Suspends this native thread. */
    public void suspend() {
        SystemInterface.suspend_thread(thread_handle);
    }

    /** Gets context of this native thread and puts it in r. */
    public boolean getContext(jq_RegisterState r) {
        return SystemInterface.get_thread_context(pid, r);
    }

    /** Sets context of this native thread to r. */
    public boolean setContext(jq_RegisterState r) {
        return SystemInterface.set_thread_context(pid, r);
    }

    /** Counter for round-robin scheduling. */
    private static volatile int round_robin_counter = -1;

    /** Put the given Java thread on the queue of a (preferably idle) native thread. */
    public static void startJavaThread(jq_Thread t) {
        _num_of_java_threads.getAddress().atomicAdd(1);
        if (t.isDaemon())
            _num_of_daemon_threads.getAddress().atomicAdd(1);
        jq_NativeThread nt = idleThread; // atomic read
        if (nt == null) {
            // no idle thread, use round-robin scheduling.
            int c = round_robin_counter;
            nt = native_threads[++c];
            if (TRACE) SystemInterface.debugwriteln("Round-robin: native thread #" + c);
            if (c + 1 == native_threads.length)
                round_robin_counter = -1;
            else
                round_robin_counter = c;
        } else {
            idleThread = null; // go back to round-robin
        }
        // threads start off as non-preemptable
        CodeAddress ip = t.getRegisterState().getEip();
        if (TRACE) SystemInterface.debugwriteln("Java thread " + t + " enqueued on native thread " + nt + " ip: " + ip.stringRep() + " cc: " + CodeAllocator.getCodeContaining(ip));
        Assert._assert(t.isThreadSwitchEnabled());
        t.disableThreadSwitch(); // threads on queues have thread switch disabled
        nt.transferQueue.enqueue(t);
    }

    /** End the currently-executing Java thread and go back to the scheduler loop
     *  to pick up another thread.
     */
    public static void endCurrentJavaThread() {
        jq_Thread t = Unsafe.getThreadBlock();
        if (TRACE) SystemInterface.debugwriteln("Ending Java thread " + t);
        t.disableThreadSwitch();
        _num_of_java_threads.getAddress().atomicSub(1);
        if (t.isDaemon())
            _num_of_daemon_threads.getAddress().atomicSub(1);
        jq_NativeThread nt = t.getNativeThread();
        Unsafe.setThreadBlock(nt.schedulerThread);
        nt.currentThread = nt.schedulerThread;
        // long jump back to entry of schedulerLoop
        CodeAddress ip = _schedulerLoop.getDefaultCompiledVersion().getEntrypoint();
        StackAddress fp = nt.original_ebp;
        StackAddress sp = nt.original_esp;
        if (TRACE) SystemInterface.debugwriteln("Long jumping back to schedulerLoop, ip:" + ip.stringRep() + " fp: " + fp.stringRep() + " sp: " + sp.stringRep());
        HeapAddress a = (HeapAddress)sp.offset(4).peek();
        Assert._assert(a.asObject() == nt, "arg to schedulerLoop got corrupted: " + a.stringRep() + " should be " + HeapAddress.addressOf(nt).stringRep());
        Unsafe.longJump(ip, fp, sp, 0);
        Assert.UNREACHABLE();
    }

    public static boolean USE_INTERRUPTER_THREAD = false;

    /** The entry point for new native threads.
     */
    public void nativeThreadEntry() {
        if (this != initial_native_thread)
            this.pid = SystemInterface.init_thread();
        Unsafe.setThreadBlock(this.schedulerThread);
        Assert._assert(this.currentThread == this.schedulerThread);

        if (USE_INTERRUPTER_THREAD) {
            // start up another native thread to periodically interrupt this one.
            jq_InterrupterThread it = new jq_InterrupterThread(this);
        } else {
            // use setitimer
            SystemInterface.set_interval_timer(SystemInterface.ITIMER_VIRTUAL, 10);
        }

        // store for longJump
        StackAddress sp = StackAddress.getStackPointer();
        StackAddress fp = StackAddress.getBasePointer();
        // make room for return address and "this" pointer.
        this.original_esp = (StackAddress) sp.offset(-CodeAddress.size()-HeapAddress.size());
        this.original_ebp = fp;

        if (TRACE) SystemInterface.debugwriteln("Started native thread: " + this + " sp: " + this.original_esp.stringRep() + " fp: " + this.original_ebp.stringRep());

        // enter the scheduler loop
        this.schedulerLoop();
        Assert.UNREACHABLE();
    }

    public void schedulerLoop() {
        HeapAddress a = (HeapAddress) this.original_esp.offset(4).peek();
        Assert._assert(a.asObject() == this);

        // preemption cannot occur in the scheduler loop because the
        // schedulerThread has thread switching disabled.
        Assert._assert(Unsafe.getThreadBlock() == this.schedulerThread);
        for (;;) {
            // only initial native thread can shut down the VM.
            if (this == initial_native_thread &&
                num_of_daemon_threads == num_of_java_threads)
                break;
            Assert._assert(currentThread == schedulerThread);
            jq_Thread t = getNextReadyThread();
            if (t == null) {
                // no ready threads!
                if (TRACE) SystemInterface.debugwriteln("Native thread " + this + " is idle!");
                idleThread = this;
                SystemInterface.yield();
            } else {
                Assert._assert(!t.isThreadSwitchEnabled());
                if (TRACE) SystemInterface.debugwriteln("Native thread " + this + " scheduler loop: switching to Java thread " + t);
                currentThread = t;
                SystemInterface.set_current_context(t, t.getRegisterState());
                Assert.UNREACHABLE();
            }
        }
        SystemInterface.die(0);
        Assert.UNREACHABLE();
    }

    /** Performs a thread switch based on a timer interrupt. */
    public void threadSwitch() {
        // thread switching for the current thread is disabled on entry.
        jq_Thread t1 = this.currentThread;
        Unsafe.setThreadBlock(this.schedulerThread);
        this.currentThread = this.schedulerThread;
        CodeAddress ip = (CodeAddress) StackAddress.getBasePointer().offset(StackAddress.size()).peek();
        if (TRACE) SystemInterface.debugwriteln("Thread switch in native thread: " + this + " Java thread: " + t1 + " ip: " + ip.stringRep() + " cc: " + CodeAllocator.getCodeContaining(ip));
        if (t1.isThreadSwitchEnabled()) {
            SystemInterface.debugwriteln("Java thread " + t1 + " has thread switching enabled on threadSwitch entry!");
            SystemInterface.die(-1);
        }
        Assert._assert(t1 != this.schedulerThread);

        // simulate a return in the current register state, so when the thread gets swapped back
        // in, it will continue where it left off.
        jq_RegisterState state = t1.getRegisterState();
        state.Eip = (CodeAddress) state.getEsp().peek();
        state.Esp = (StackAddress) state.getEsp().offset(StackAddress.size() + CodeAddress.size());

        jq_Thread t2 = getNextReadyThread();
        transferExtraWork();
        if (t2 == null) {
            // only one thread!
            t2 = t1;
        } else {
            ip = t2.getRegisterState().Eip;
            if (TRACE) SystemInterface.debugwriteln("New ready Java thread: " + t2 + " ip: " + ip.stringRep() + " cc: " + CodeAllocator.getCodeContaining(ip));
            readyQueue.enqueue(t1);
            Assert._assert(!t2.isThreadSwitchEnabled());
        }
        currentThread = t2;
        SystemInterface.set_current_context(t2, t2.getRegisterState());
        Assert.UNREACHABLE();
    }

    /** Performs a thread switch to a specific thread in our local queue. */
    public void threadSwitch(jq_Thread t2) {
        // thread switching for the current thread is disabled on entry.
        jq_Thread t1 = this.currentThread;

        Unsafe.setThreadBlock(this.schedulerThread);
        this.currentThread = this.schedulerThread;
        CodeAddress ip = (CodeAddress) StackAddress.getBasePointer().offset(StackAddress.size()).peek();
        if (TRACE) SystemInterface.debugwriteln("Thread switch in native thread: " + this + " Java thread: " + t1 + " ip: " + ip.stringRep() + " cc: " + CodeAllocator.getCodeContaining(ip));
        if (t1.isThreadSwitchEnabled()) {
            SystemInterface.debugwriteln("Java thread " + t1 + " has thread switching enabled on threadSwitch entry!");
            SystemInterface.die(-1);
        }
        Assert._assert(t1 != this.schedulerThread);

        // simulate a return in the current register state, so when the thread gets swapped back
        // in, it will continue where it left off.
        jq_RegisterState state = t1.getRegisterState();
        state.Eip = (CodeAddress) state.getEsp().peek();
        state.Esp = (StackAddress) state.getEsp().offset(StackAddress.size() + CodeAddress.size());

        if (t1 != t2) {
            // find given thread in our queue.
            boolean exists = readyQueue.remove(t2);
            Assert._assert(exists);
            transferExtraWork();
            ip = t2.getRegisterState().Eip;
            if (TRACE) SystemInterface.debugwriteln("New ready Java thread: " + t2 + " ip: " + ip.stringRep() + " cc: " + CodeAllocator.getCodeContaining(ip));
            readyQueue.enqueue(t1);
            Assert._assert(!t2.isThreadSwitchEnabled());
        } else {
            transferExtraWork();
        }
        currentThread = t2;
        SystemInterface.set_current_context(t2, t2.getRegisterState());
        Assert.UNREACHABLE();
    }

    /** Transfer a Java thread from our ready queue to an idle native thread. */
    private void transferExtraWork() {
        // if we have extra work, transfer it to an idle thread
        jq_NativeThread idle = idleThread; // atomic read
        if (idle != null && !readyQueue.isEmpty()) {
            jq_Thread t2 = readyQueue.dequeue();
            Assert._assert(!t2.isThreadSwitchEnabled());
            idle.transferQueue.enqueue(t2);
            idleThread = null;
        }
    }

    /** Get the next ready thread from the transfer queue or the ready queue.
     *  Return null if there are no threads ready.
     */
    private jq_Thread getNextReadyThread() {
        if (!transferQueue.isEmpty()) {
            jq_Thread t = transferQueue.dequeue();
            t.setNativeThread(this);
            Assert._assert(!t.isThreadSwitchEnabled());
            return t;
        }
        while (!readyQueue.isEmpty()) {
            jq_Thread t = readyQueue.dequeue();
            if (!t.isAlive()) continue;
            Assert._assert(t.getNativeThread() == this);
            Assert._assert(!t.isThreadSwitchEnabled());
            return t;
        }
        return null;
    }

    public String toString() {
        return "NT " + index + ":" + thread_handle + "(" + Strings.hex(this) + ")";
    }

    public int getIndex() { return index; }

    public static void ctrl_break_handler() {
        // warning: not reentrant.
        Unsafe.setThreadBlock(break_jthread);
        break_nthread.thread_handle = SystemInterface.get_current_thread_handle();
        if (!has_break_occurred) {
            break_nthread.myHeapAllocator.init();
            break_nthread.myCodeAllocator.init();
            has_break_occurred = true;
        }
        SystemInterface.debugwriteln("*** BREAK! ***");
        for (int i = 0; i < native_threads.length; ++i) {
            SystemInterface.suspend_thread(native_threads[i].thread_handle);
        }
        //dumpThreads();
        Debugger.OnlineDebugger.debuggerEntryPoint();
        for (int i = 0; i < native_threads.length; ++i) {
            SystemInterface.resume_thread(native_threads[i].thread_handle);
        }
    }

    public static void dumpAllThreads() {
        jq_RegisterState rs = new jq_RegisterState();
        rs.ContextFlags = jq_RegisterState.CONTEXT_CONTROL;
        for (int i = 0; i < native_threads.length; ++i) {
            SystemInterface.get_thread_context(native_threads[i].pid, rs);
            native_threads[i].dump(rs);
        }
    }

    private static boolean has_break_occurred = false;
    private static jq_NativeThread break_nthread;
    private static jq_Thread break_jthread;

    public static void initBreakThread() {
        break_nthread = new jq_NativeThread(-1);
        Thread t = new Thread("_break_");
        break_jthread = ThreadUtils.getJQThread(t);
        break_jthread.disableThreadSwitch();
        break_jthread.setNativeThread(break_nthread);
        if (TRACE) SystemInterface.debugwriteln("Break thread initialized");
    }

    private static int gcType = GCManager.MS_GC;
    private static jq_NativeThread gc_nthread;
    private static jq_Thread gc_jthread;

    // Stop all the native threads and start GC
    public static void stopTheWorld() {
        jq_Thread t = Unsafe.getThreadBlock();
        if (TRACE) SystemInterface.debugwriteln("Ending Java thread " + t);
        t.disableThreadSwitch();
        _num_of_java_threads.getAddress().atomicSub(1);
        if (t.isDaemon()) {
            _num_of_daemon_threads.getAddress().atomicSub(1);
        }
        Unsafe.setThreadBlock(gc_jthread);
        gc_nthread.currentThread = gc_jthread;
        gc_nthread.thread_handle = SystemInterface.get_current_thread_handle();
        SystemInterface.debugwriteln("*** GC Starts! ***");
        for (int i = 0; i < native_threads.length; ++i) {
            SystemInterface.suspend_thread(native_threads[i].thread_handle);
        }
        jq_RegisterState rs = new jq_RegisterState();
        rs.ContextFlags = jq_RegisterState.CONTEXT_FULL;
        GCVisitor visitor = (GCVisitor)GCManager.getGC(gcType);
        for (int i = 0; i < native_threads.length; ++i) {
            SystemInterface.get_thread_context(native_threads[i].pid, rs);
            visitor.visit(rs);
            if (TRACE) native_threads[i].dump(rs);
        }
    }

    public static void resumeTheFeast() {
        GCVisitor visitor = (GCVisitor)GCManager.getGC(gcType);
        visitor.farewell(native_threads);
        startNativeThreads();
    }

    public static void initGCThread() {
        gc_nthread = new jq_NativeThread(-2);
        // TO DO: select gc from command line
        Thread t = new Thread((Runnable)GCManager.getGC(gcType), "_gc_");
        t.setDaemon(true);
        gc_jthread = ThreadUtils.getJQThread(t);
        gc_jthread.disableThreadSwitch();
        gc_jthread.setNativeThread(gc_nthread);
        if (TRACE) SystemInterface.debugwriteln("GC thread initialized");
    }

    public void dump(jq_RegisterState regs) {
        SystemInterface.debugwriteln(this + ": current Java thread = " + currentThread);
        StackCodeWalker.stackDump(regs.Eip, regs.getEbp());
        SystemInterface.debugwriteln(this + ": ready queue = " + readyQueue);
        SystemInterface.debugwriteln(this + ": idle queue = " + idleQueue);
        SystemInterface.debugwriteln(this + ": transfer queue = " + transferQueue);
    }

    public jq_ThreadQueue getReadyQueue() {
        return this.readyQueue;
    }
    public jq_ThreadQueue getIdleQueue() {
        return this.idleQueue;
    }
    public jq_ThreadQueue getTransferQueue() {
        return this.transferQueue;
    }

    /** Initialize the thread switch stub function. */
    /*
    public void initializeThreadSwitchStub() {
        List code_relocs = new LinkedList();
        List data_relocs = new LinkedList();
        x86Assembler asm = new x86Assembler(0, 128);
// pop EAX    (ret addr)
        asm.emitShort_Reg(x86.POP_r, EAX);
// pop EAX    (jthread)
        asm.emitShort_Reg(x86.POP_r, EAX);
// pop ECX    (context)
        asm.emitShort_Reg(x86.POP_r, ECX);
// mov FS:14h, EAX
        asm.emitprefix(x86.PREFIX_FS);
        asm.emit2_Reg_Mem(x86.MOV_m_r32, EAX, 0x14);
// mov EAX, [ECX+20h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EAX, jq_RegisterState._eip.getOffset(), ECX);
// mov <label>, EAX
        asm.emit1_Imm32(x86.MOV_mo32_ra, 0x00000000);
        // TODO: this contains an absolute reference that will break if this code is relocated.
        int cloc = asm.getCurrentOffset();
// mov EAX, [ECX+28h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EAX, jq_RegisterState._fp_state.getOffset(), ECX);
// frstor [EAX]
        asm.emit2_Mem(x86.FRSTOR, 0, EAX);
// mov EAX, [ECX+24h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EAX, jq_RegisterState._eflags.getOffset(), ECX);
// sahf
        asm.emit1(x86.SAHF);
// mov EAX, [ECX+00h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EAX, jq_RegisterState._eax.getOffset(), ECX);
// mov EDX, [ECX+08h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EDX, jq_RegisterState._edx.getOffset(), ECX);
// mov EBX, [ECX+0Ch]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EBX, jq_RegisterState._ebx.getOffset(), ECX);
// mov ESI, [ECX+10h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, ESI, jq_RegisterState._esi.getOffset(), ECX);
// mov EDI, [ECX+14h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EDI, jq_RegisterState._edi.getOffset(), ECX);
// mov EBP, [ECX+18h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, EBP, jq_RegisterState._ebp.getOffset(), ECX);
// mov ESP, [ECX+1Ch]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, ESP, jq_RegisterState._esp.getOffset(), ECX);
// mov ECX, [ECX+04h]
        asm.emit2_Reg_Mem(x86.MOV_r_m32, ECX, jq_RegisterState._ecx.getOffset(), ECX);
// jmp <abs>
        asm.emit1(x86.JMP_abs);
        asm.patch4_endian(cloc-4, asm.getCurrentAddress());
        asm.emitDATA(0);
        asm.emitDATA(0x0000001b); // 0x1b = ocde segment, 2 xtra bytes here.
        tswitch_routine = asm.getCodeBuffer().allocateCodeBlock(null, null, null, null, code_relocs, data_relocs);
    }
     */

    public static final jq_Class _class;
    public static final jq_InstanceMethod _nativeThreadEntry;
    public static final jq_InstanceMethod _schedulerLoop;
    public static final jq_InstanceMethod _threadSwitch;
    public static final jq_StaticMethod _ctrl_break_handler;
    public static final jq_StaticField _num_of_java_threads;
    public static final jq_StaticField _num_of_daemon_threads;

    static {
        _class = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("LScheduler/jq_NativeThread;");
        _nativeThreadEntry = _class.getOrCreateInstanceMethod("nativeThreadEntry", "()V");
        _schedulerLoop = _class.getOrCreateInstanceMethod("schedulerLoop", "()V");
        _threadSwitch = _class.getOrCreateInstanceMethod("threadSwitch", "()V");
        _ctrl_break_handler = _class.getOrCreateStaticMethod("ctrl_break_handler", "()V");
        _num_of_java_threads = _class.getOrCreateStaticField("num_of_java_threads", "I");
        _num_of_daemon_threads = _class.getOrCreateStaticField("num_of_daemon_threads", "I");
    }
}
