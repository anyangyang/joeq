// MethodSummary.java, created Thu Apr 25 16:32:26 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Array;
import Clazz.jq_Class;
import Clazz.jq_Field;
import Clazz.jq_InstanceField;
import Clazz.jq_Member;
import Clazz.jq_Method;
import Clazz.jq_Reference;
import Clazz.jq_StaticField;
import Clazz.jq_Type;
import Compil3r.Quad.AndersenInterface.AndersenField;
import Compil3r.Quad.AndersenInterface.AndersenMethod;
import Compil3r.Quad.AndersenInterface.AndersenReference;
import Compil3r.Quad.BytecodeToQuad.jq_ReturnAddressType;
import Compil3r.Quad.Operand.AConstOperand;
import Compil3r.Quad.Operand.PConstOperand;
import Compil3r.Quad.Operand.ParamListOperand;
import Compil3r.Quad.Operand.RegisterOperand;
import Compil3r.Quad.Operator.ALoad;
import Compil3r.Quad.Operator.AStore;
import Compil3r.Quad.Operator.Binary;
import Compil3r.Quad.Operator.CheckCast;
import Compil3r.Quad.Operator.Getfield;
import Compil3r.Quad.Operator.Getstatic;
import Compil3r.Quad.Operator.Invoke;
import Compil3r.Quad.Operator.Jsr;
import Compil3r.Quad.Operator.Move;
import Compil3r.Quad.Operator.New;
import Compil3r.Quad.Operator.NewArray;
import Compil3r.Quad.Operator.Putfield;
import Compil3r.Quad.Operator.Putstatic;
import Compil3r.Quad.Operator.Return;
import Compil3r.Quad.Operator.Special;
import Compil3r.Quad.Operator.Unary;
import Compil3r.Quad.RegisterFactory.Register;
import Memory.Address;
import Run_Time.Reflection;
import Util.Assert;
import Util.Strings;
import Util.Collections.CollectionTestWrapper;
import Util.Collections.FilterIterator;
import Util.Collections.HashCodeComparator;
import Util.Collections.IdentityHashCodeWrapper;
import Util.Collections.IndexMap;
import Util.Collections.InstrumentedSetWrapper;
import Util.Collections.Pair;
import Util.Collections.SetFactory;
import Util.Collections.SortedArraySet;

/**
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: MethodSummary.java,v 1.64 2003/08/12 12:06:33 joewhaley Exp $
 */
public class MethodSummary {

    public static java.io.PrintStream out = System.out;
    public static /*final*/ boolean TRACE_INTRA = false;
    public static /*final*/ boolean TRACE_INTER = false;
    public static /*final*/ boolean TRACE_INST = false;
    public static final boolean IGNORE_INSTANCE_FIELDS = false;
    public static final boolean IGNORE_STATIC_FIELDS = false;
    public static final boolean VERIFY_ASSERTIONS = false;

    public static final boolean USE_IDENTITY_HASHCODE = false;
    public static final boolean DETERMINISTIC = !USE_IDENTITY_HASHCODE && true;
    
    public static final class MethodSummaryBuilder implements ControlFlowGraphVisitor {
        public void visitCFG(ControlFlowGraph cfg) {
            MethodSummary s = getSummary(cfg);
            //System.out.println(s.toString());
            try {
                DataOutputStream dos = new DataOutputStream(System.out);
                s.dotGraph(dos);
            } catch (IOException x) {
                x.printStackTrace();
            }
        }
    }
    
    public static HashMap summary_cache = new HashMap();
    public static MethodSummary getSummary(ControlFlowGraph cfg) {
        MethodSummary s = (MethodSummary)summary_cache.get(cfg);
        if (s == null) {
            if (TRACE_INTER) out.println("vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv");
            if (TRACE_INTER) out.println("Building summary for "+cfg.getMethod());
            try {
                BuildMethodSummary b = new BuildMethodSummary(cfg);
                s = b.getSummary();
            } catch (RuntimeException t) {
                System.err.println("Runtime exception when getting method summary for "+cfg.getMethod());
                throw t;
            } catch (Error t) {
                System.err.println("Error when getting method summary for "+cfg.getMethod());
                throw t;
            }
            summary_cache.put(cfg, s);
            if (TRACE_INTER) out.println("Summary for "+cfg.getMethod()+":");
            if (TRACE_INTER) out.println(s);
            if (TRACE_INTER) out.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
        }
        return s;
    }
    public static void clearSummaryCache() {
        summary_cache.clear();
        ConcreteTypeNode.FACTORY.clear();
        UnknownTypeNode.FACTORY.clear();
        GlobalNode.GLOBAL = new GlobalNode();
    }
    
    public static HashMap clone_cache;
    public static MethodSummary getSummary(ControlFlowGraph cfg, CallSite cs) {
        if (clone_cache != null) {
            //System.out.println("Checking cache for "+new Pair(cfg, cs));
            MethodSummary ms = (MethodSummary) clone_cache.get(new Pair(cfg, cs));
            if (ms != null) {
                //System.out.println("Using specialized version of "+ms.getMethod()+" for call site "+cs);
                return ms;
            }
        }
        return getSummary(cfg);
    }
    
    /** Visitor class to build an intramethod summary. */
    public static final class BuildMethodSummary extends QuadVisitor.EmptyVisitor {
        
        /** The method that we are building a summary for. */
        protected final jq_Method method;
        /** The number of locals and number of registers. */
        protected final int nLocals, nRegisters;
        /** The parameter nodes. */
        protected final ParamNode[] param_nodes;
        /** The global node. */
        protected final GlobalNode my_global;
        /** The start states of the iteration. */
        protected final State[] start_states;
        /** The set of returned and thrown nodes. */
        protected final Set returned, thrown;
        /** The set of method calls made. */
        protected final Set methodCalls;
        /** Map from a method call to its ReturnValueNode. */
        protected final HashMap callToRVN;
        /** Map from a method call to its ThrownExceptionNode. */
        protected final HashMap callToTEN;
        /** The set of nodes that were ever passed as a parameter, or returned/thrown from a call site. */
        protected final Set passedAsParameter;
        /** The current basic block. */
        protected BasicBlock bb;
        /** The current state. */
        protected State s;
        /** Change bit for worklist iteration. */
        protected boolean change;
        
        /** Factory for nodes. */
        protected final HashMap quadsToNodes;
        
        /** Returns the summary. Call this after iteration has completed. */
        public MethodSummary getSummary() {
            MethodSummary s = new MethodSummary(method, param_nodes, my_global, methodCalls, callToRVN, callToTEN, returned, thrown, passedAsParameter);
            return s;
        }

        /** Set the given local in the current state to point to the given node. */
        protected void setLocal(int i, Node n) { s.registers[i] = n; }
        /** Set the given register in the current state to point to the given node. */
        protected void setRegister(Register r, Node n) {
            int i = r.getNumber();
            if (r.isTemp()) i += nLocals;
            s.registers[i] = n;
            if (TRACE_INTRA) out.println("Setting register "+r+" to "+n);
        }
        /** Set the given register in the current state to point to the given node or set of nodes. */
        protected void setRegister(Register r, Object n) {
            int i = r.getNumber();
            if (r.isTemp()) i += nLocals;
            if (n instanceof Set) n = NodeSet.FACTORY.makeSet((Set)n);
            else Assert._assert(n instanceof Node);
            s.registers[i] = n;
            if (TRACE_INTRA) out.println("Setting register "+r+" to "+n);
        }
        /** Get the node or set of nodes in the given register in the current state. */
        protected Object getRegister(Register r) {
            int i = r.getNumber();
            if (r.isTemp()) i += nLocals;
            Assert._assert(s.registers[i] != null);
            return s.registers[i];
        }

        /** Build a summary for the given method. */
        public BuildMethodSummary(ControlFlowGraph cfg) {
            RegisterFactory rf = cfg.getRegisterFactory();
            this.nLocals = rf.getLocalSize(PrimordialClassLoader.getJavaLangObject());
            this.nRegisters = this.nLocals + rf.getStackSize(PrimordialClassLoader.getJavaLangObject());
            this.method = cfg.getMethod();
            this.start_states = new State[cfg.getNumberOfBasicBlocks()];
            this.methodCalls = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
            this.callToRVN = new HashMap();
            this.callToTEN = new HashMap();
            this.passedAsParameter = NodeSet.FACTORY.makeSet();
            this.quadsToNodes = new HashMap();
            this.s = this.start_states[0] = new State(this.nRegisters);
            jq_Type[] params = this.method.getParamTypes();
            this.param_nodes = new ParamNode[params.length];
            for (int i=0, j=0; i<params.length; ++i, ++j) {
                if (params[i].isReferenceType()
                    /*&& !params[i].isAddressType()*/
                    ) {
                    setLocal(j, param_nodes[i] = new ParamNode(method, i, (jq_Reference)params[i]));
                } else if (params[i].getReferenceSize() == 8) ++j;
            }
            this.my_global = new GlobalNode();
            this.returned = NodeSet.FACTORY.makeSet(); this.thrown = NodeSet.FACTORY.makeSet();
            
            if (TRACE_INTRA) out.println("Building summary for "+this.method);
            
            // iterate until convergence.
            Util.Templates.List.BasicBlock rpo_list = cfg.reversePostOrder(cfg.entry());
            for (;;) {
                Util.Templates.ListIterator.BasicBlock rpo = rpo_list.basicBlockIterator();
                this.change = false;
                while (rpo.hasNext()) {
                    this.bb = rpo.nextBasicBlock();
                    this.s = start_states[bb.getID()];
                    if (this.s == null) {
                        continue;
                    }
                    this.s = this.s.copy();
                    /*
                    if (this.bb.isExceptionHandlerEntry()) {
                        java.util.Iterator i = cfg.getExceptionHandlersMatchingEntry(this.bb);
                        jq.Assert(i.hasNext());
                        ExceptionHandler eh = (ExceptionHandler)i.next();
                        CaughtExceptionNode n = new CaughtExceptionNode(eh);
                        if (i.hasNext()) {
                            Set set = NodeSet.FACTORY.makeSet(); set.add(n);
                            while (i.hasNext()) {
                                eh = (ExceptionHandler)i.next();
                                n = new CaughtExceptionNode(eh);
                                set.add(n);
                            }
                            s.merge(nLocals, set);
                        } else {
                            s.merge(nLocals, n);
                        }
                    }
                     */
                    if (TRACE_INTRA) {
                        out.println("State at beginning of "+this.bb+":");
                        this.s.dump(out);
                    }
                    this.bb.visitQuads(this);
                    Util.Templates.ListIterator.BasicBlock succs = this.bb.getSuccessors().basicBlockIterator();
                    while (succs.hasNext()) {
                        BasicBlock succ = succs.nextBasicBlock();
                        if (this.bb.endsInRet()) {
                            if (jsr_states != null) {
                                State s2 = (State) jsr_states.get(succ);
                                if (s2 != null) {
                                    JSRInfo info = cfg.getJSRInfo(this.bb);
                                    boolean[] changedLocals = info.changedLocals;
                                    mergeWithJSR(succ, s2, changedLocals);
                                } else {
                                    if (TRACE_INTRA) out.println("jsr before "+succ+" not yet visited!");
                                }
                            } else {
                                if (TRACE_INTRA) out.println("no jsr's visited yet! was looking for jsr successor "+succ);
                            }
                        } else {
                            mergeWith(succ);
                        }
                    }
                }
                if (!this.change) break;
            }
        }

        /** Merge the current state into the start state for the given basic block.
         *  If that start state is uninitialized, it is initialized with a copy of
         *  the current state.  This updates the change flag if anything is changed. */
        protected void mergeWith(BasicBlock succ) {
            if (this.start_states[succ.getID()] == null) {
                if (TRACE_INTRA) out.println(succ+" not yet visited.");
                this.start_states[succ.getID()] = this.s.copy();
                this.change = true;
            } else {
                //if (TRACE_INTRA) out.println("merging out set of "+bb+" "+Strings.hex8(this.s.hashCode())+" into in set of "+succ+" "+Strings.hex8(this.start_states[succ.getID()].hashCode()));
                if (TRACE_INTRA) out.println("merging out set of "+bb+" into in set of "+succ);
                if (this.start_states[succ.getID()].merge(this.s)) {
                    if (TRACE_INTRA) out.println(succ+" in set changed");
                    this.change = true;
                }
            }
        }
        
        protected void mergeWithJSR(BasicBlock succ, State s2, boolean[] changedLocals) {
            State state = this.start_states[succ.getID()];
            if (state == null) {
                if (TRACE_INTRA) out.println(succ+" not yet visited.");
                this.start_states[succ.getID()] = state = this.s.copy();
                this.change = true;
            }
            //if (TRACE_INTRA) out.println("merging out set of jsr "+bb+" "+Strings.hex8(this.s.hashCode())+" into in set of "+succ+" "+Strings.hex8(this.start_states[succ.getID()].hashCode()));
            if (TRACE_INTRA) out.println("merging out set of jsr "+bb+" into in set of "+succ);
            for (int i=0; i<changedLocals.length; ++i) {
                if (changedLocals[i]) {
                    if (state.merge(i, this.s.registers[i])) {
                        if (TRACE_INTRA) out.println(succ+" in set changed by register "+i+" in jsr subroutine");
                        this.change = true;
                    }
                } else {
                    if (state.merge(i, s2.registers[i])) {
                        if (TRACE_INTRA) out.println(succ+" in set changed by register "+i+" before jsr subroutine");
                        this.change = true;
                    }
                }
            }
        }
        
        /** Merge the current state into the start state for the given basic block.
         *  If that start state is uninitialized, it is initialized with a copy of
         *  the current state.  This updates the change flag if anything is changed. */
        protected void mergeWith(ExceptionHandler eh) {
            BasicBlock succ = eh.getEntry();
            if (this.start_states[succ.getID()] == null) {
                if (TRACE_INTRA) out.println(succ+" not yet visited.");
                this.start_states[succ.getID()] = this.s.copy();
                for (int i=nLocals; i<this.s.registers.length; ++i) {
                    this.start_states[succ.getID()].registers[i] = null;
                }
                this.change = true;
            } else {
                //if (TRACE_INTRA) out.println("merging out set of "+bb+" "+Strings.hex8(this.s.hashCode())+" into in set of ex handler "+succ+" "+Strings.hex8(this.start_states[succ.getID()].hashCode()));
                if (TRACE_INTRA) out.println("merging out set of "+bb+" into in set of ex handler "+succ);
                for (int i=0; i<nLocals; ++i) {
                    if (this.start_states[succ.getID()].merge(i, this.s.registers[i]))
                        this.change = true;
                }
                if (TRACE_INTRA && this.change) out.println(succ+" in set changed");
            }
        }
        
        public static final boolean INSIDE_EDGES = false;
        
        /** Abstractly perform a heap load operation on the given base and field
         *  with the given field node, putting the result in the given set. */
        protected void heapLoad(Set result, Node base, jq_Field f, FieldNode fn) {
            //base.addAccessPathEdge(f, fn);
            result.add(fn);
            if (INSIDE_EDGES)
                base.getEdges(f, result);
        }
        /** Abstractly perform a heap load operation corresponding to quad 'obj'
         *  with the given destination register, bases and field.  The destination
         *  register in the current state is changed to the result. */
        protected void heapLoad(Quad obj, Register dest_r, Set base_s, jq_Field f) {
            Set result = NodeSet.FACTORY.makeSet();
            for (Iterator i=base_s.iterator(); i.hasNext(); ) {
                Node base = (Node)i.next();
                FieldNode fn = FieldNode.get(base, f, obj);
                heapLoad(result, base, f, fn);
            }
            setRegister(dest_r, result);
        }
        /** Abstractly perform a heap load operation corresponding to quad 'obj'
         *  with the given destination register, base and field.  The destination
         *  register in the current state is changed to the result. */
        protected void heapLoad(Quad obj, Register dest_r, Node base_n, jq_Field f) {
            FieldNode fn = FieldNode.get(base_n, f, obj);
            Set result = NodeSet.FACTORY.makeSet();
            heapLoad(result, base_n, f, fn);
            setRegister(dest_r, result);
        }
        /** Abstractly perform a heap load operation corresponding to quad 'obj'
         *  with the given destination register, base register and field.  The
         *  destination register in the current state is changed to the result. */
        protected void heapLoad(Quad obj, Register dest_r, Register base_r, jq_Field f) {
            Object o = getRegister(base_r);
            if (o instanceof Set) {
                heapLoad(obj, dest_r, (Set)o, f);
            } else {
                heapLoad(obj, dest_r, (Node)o, f);
            }
        }
        
        /** Abstractly perform a heap store operation of the given source node on
         *  the given base node and field. */
        protected void heapStore(Node base, Node src, jq_Field f, Quad q) {
            base.addEdge(f, src, q);
        }
        /** Abstractly perform a heap store operation of the given source nodes on
         *  the given base node and field. */
        protected void heapStore(Node base, Set src, jq_Field f, Quad q) {
            base.addEdges(f, NodeSet.FACTORY.makeSet(src), q);
        }
        /** Abstractly perform a heap store operation of the given source node on
         *  the nodes in the given register in the current state and the given field. */
        protected void heapStore(Register base_r, Node src_n, jq_Field f, Quad q) {
            Object base = getRegister(base_r);
            if (base instanceof Set) {
                for (Iterator i = ((Set)base).iterator(); i.hasNext(); ) {
                    heapStore((Node)i.next(), src_n, f, q);
                }
            } else {
                heapStore((Node)base, src_n, f, q);
            }
        }
        /** Abstractly perform a heap store operation of the nodes in the given register
         *  on the given base node and field. */
        protected void heapStore(Node base, Register src_r, jq_Field f, Quad q) {
            Object src = getRegister(src_r);
            if (src instanceof Node) {
                heapStore(base, (Node)src, f, q);
            } else {
                heapStore(base, (Set)src, f, q);
            }
        }
        /** Abstractly perform a heap store operation of the nodes in the given register
         *  on the nodes in the given register in the current state and the given field. */
        protected void heapStore(Register base_r, Register src_r, jq_Field f, Quad q) {
            Object base = getRegister(base_r);
            Object src = getRegister(src_r);
            if (src instanceof Node) {
                heapStore(base_r, (Node)src, f, q);
                return;
            }
            Set src_h = (Set)src;
            if (base instanceof Set) {
                for (Iterator i = ((Set)base).iterator(); i.hasNext(); ) {
                    heapStore((Node)i.next(), src_h, f, q);
                }
            } else {
                heapStore((Node)base, src_h, f, q);
            }
        }

        /** Record that the nodes in the given register were passed to the given
         *  method call as the given parameter. */
        void passParameter(Register r, ProgramLocation m, int p) {
            Object v = getRegister(r);
            if (TRACE_INTRA) out.println("Passing "+r+" to "+m+" param "+p+": "+v);
            if (v instanceof Set) {
                for (Iterator i = ((Set)v).iterator(); i.hasNext(); ) {
                    Node n = (Node)i.next();
                    n.recordPassedParameter(m, p);
                    passedAsParameter.add(n);
                }
            } else {
                Node n = (Node)v;
                n.recordPassedParameter(m, p);
                passedAsParameter.add(n);
            }
        }
        
        /** Visit an array load instruction. */
        public void visitALoad(Quad obj) {
            if (obj.getOperator() instanceof Operator.ALoad.ALOAD_A
                || obj.getOperator() instanceof Operator.ALoad.ALOAD_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register r = ALoad.getDest(obj).getRegister();
                Operand o = ALoad.getBase(obj);
                if (o instanceof RegisterOperand) {
                    Register b = ((RegisterOperand)o).getRegister();
                    heapLoad(obj, r, b, null);
                } else {
                    // base is not a register?!
                }
            }
        }
        /** Visit an array store instruction. */
        public void visitAStore(Quad obj) {
            if (obj.getOperator() instanceof Operator.AStore.ASTORE_A
                || obj.getOperator() instanceof Operator.AStore.ASTORE_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Operand base = AStore.getBase(obj);
                Operand val = AStore.getValue(obj);
                if (base instanceof RegisterOperand) {
                    Register base_r = ((RegisterOperand)base).getRegister();
                    if (val instanceof RegisterOperand) {
                        Register src_r = ((RegisterOperand)val).getRegister();
                        heapStore(base_r, src_r, null, obj);
                    } else if (val instanceof AConstOperand) {
                        jq_Reference type = ((AConstOperand)val).getType();
                        Object key = type;
                        ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                        if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                        heapStore(base_r, n, null, obj);
                    } else {
                        jq_Reference type = ((PConstOperand)val).getType();
                        UnknownTypeNode n = UnknownTypeNode.get(type);
                        heapStore(base_r, n, null, obj);
                    }
                } else {
                    // base is not a register?!
                }
            }
        }
        public void visitBinary(Quad obj) {
            if (obj.getOperator() == Binary.ADD_P.INSTANCE) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register dest_r = Binary.getDest(obj).getRegister();
                Operand src = Binary.getSrc1(obj);
                if (src instanceof RegisterOperand) {
                    RegisterOperand rop = ((RegisterOperand)src);
                    Register src_r = rop.getRegister();
                    setRegister(dest_r, getRegister(src_r));
                } else if (src instanceof AConstOperand) {
                    jq_Reference type = ((AConstOperand)src).getType();
                    Object key = type;
                    ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                    if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                    setRegister(dest_r, n);
                } else {
                    jq_Reference type = ((PConstOperand)src).getType();
                    UnknownTypeNode n = UnknownTypeNode.get(type);
                    setRegister(dest_r, n);
                }
            }
        }
        /** Visit a type cast check instruction. */
        public void visitCheckCast(Quad obj) {
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            Register dest_r = CheckCast.getDest(obj).getRegister();
            Operand src = CheckCast.getSrc(obj);
            // TODO: treat it like a move for now.
            if (src instanceof RegisterOperand) {
                Register src_r = ((RegisterOperand)src).getRegister();
                setRegister(dest_r, getRegister(src_r));
            } else if (src instanceof AConstOperand) {
                jq_Reference type = ((AConstOperand)src).getType();
                Object key = type;
                ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                setRegister(dest_r, n);
            } else {
                jq_Reference type = ((PConstOperand)src).getType();
                UnknownTypeNode n = UnknownTypeNode.get(type);
                setRegister(dest_r, n);
            }
        }
        /** Visit a get instance field instruction. */
        public void visitGetfield(Quad obj) {
            if (obj.getOperator() instanceof Operator.Getfield.GETFIELD_A
                || obj.getOperator() instanceof Operator.Getfield.GETFIELD_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register r = Getfield.getDest(obj).getRegister();
                Operand o = Getfield.getBase(obj);
                Getfield.getField(obj).resolve();
                jq_Field f = Getfield.getField(obj).getField();
                if (IGNORE_INSTANCE_FIELDS) f = null;
                if (o instanceof RegisterOperand) {
                    Register b = ((RegisterOperand)o).getRegister();
                    heapLoad(obj, r, b, f);
                } else {
                    // base is not a register?!
                }
            }
        }
        /** Visit a get static field instruction. */
        public void visitGetstatic(Quad obj) {
            if (obj.getOperator() instanceof Operator.Getstatic.GETSTATIC_A
                || obj.getOperator() instanceof Operator.Getstatic.GETSTATIC_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register r = Getstatic.getDest(obj).getRegister();
                Getstatic.getField(obj).resolve();
                jq_Field f = Getstatic.getField(obj).getField();
                if (IGNORE_STATIC_FIELDS) f = null;
                heapLoad(obj, r, my_global, f);
            }
        }
        /** Visit a type instance of instruction. */
        public void visitInstanceOf(Quad obj) {
            // skip for now.
        }
        /** Visit an invoke instruction. */
        public void visitInvoke(Quad obj) {
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            Invoke.getMethod(obj).resolve();
            jq_Method m = Invoke.getMethod(obj).getMethod();
            ProgramLocation mc = new ProgramLocation.QuadProgramLocation(method, obj);
            this.methodCalls.add(mc);
            jq_Type[] params = m.getParamTypes();
            ParamListOperand plo = Invoke.getParamList(obj);
            Assert._assert(m == Run_Time.Arrays._multinewarray || params.length == plo.length());
            for (int i=0; i<params.length; ++i) {
                if (!params[i].isReferenceType()
                    /*|| params[i].isAddressType()*/
                    ) continue;
                Register r = plo.get(i).getRegister();
                passParameter(r, mc, i);
            }
            if (m.getReturnType().isReferenceType()
                /*&& !m.getReturnType().isAddressType()*/
                ) {
                RegisterOperand dest = Invoke.getDest(obj);
                if (dest != null) {
                    Register dest_r = dest.getRegister();
                    ReturnValueNode n = (ReturnValueNode)callToRVN.get(mc);
                    if (n == null) {
                        callToRVN.put(mc, n = new ReturnValueNode(mc));
                        passedAsParameter.add(n);
                    }
                    setRegister(dest_r, n);
                }
            }
            // exceptions are handled by visitExceptionThrower.
        }
        
        HashMap jsr_states;
        /**
         * @see Compil3r.Quad.QuadVisitor#visitJsr(Compil3r.Quad.Quad)
         */
        public void visitJsr(Quad obj) {
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            if (jsr_states == null) jsr_states = new HashMap();
            BasicBlock succ = Jsr.getSuccessor(obj).getTarget();
            jsr_states.put(succ, this.s);
        }
        
        /** Visit a register move instruction. */
        public void visitMove(Quad obj) {
            if (obj.getOperator() instanceof Operator.Move.MOVE_A
                || obj.getOperator() instanceof Operator.Move.MOVE_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register dest_r = Move.getDest(obj).getRegister();
                Operand src = Move.getSrc(obj);
                if (src instanceof RegisterOperand) {
                    RegisterOperand rop = ((RegisterOperand)src);
                    if (rop.getType() instanceof jq_ReturnAddressType) return;
                    Register src_r = rop.getRegister();
                    setRegister(dest_r, getRegister(src_r));
                } else if (src instanceof AConstOperand) {
                    jq_Reference type = ((AConstOperand)src).getType();
                    Object key = type;
                    ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                    if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                    setRegister(dest_r, n);
                } else {
                    jq_Reference type = ((PConstOperand)src).getType();
                    UnknownTypeNode n = UnknownTypeNode.get(type);
                    setRegister(dest_r, n);
                }
            }
        }
        /** Visit an object allocation instruction. */
        public void visitNew(Quad obj) {
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            Register dest_r = New.getDest(obj).getRegister();
            jq_Reference type = (jq_Reference)New.getType(obj).getType();
            ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(obj);
            if (n == null) quadsToNodes.put(obj, n = new ConcreteTypeNode(type, obj));
            setRegister(dest_r, n);
        }
        /** Visit an array allocation instruction. */
        public void visitNewArray(Quad obj) {
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            Register dest_r = NewArray.getDest(obj).getRegister();
            jq_Reference type = (jq_Reference)NewArray.getType(obj).getType();
            ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(obj);
            if (n == null) quadsToNodes.put(obj, n = new ConcreteTypeNode(type, obj));
            setRegister(dest_r, n);
        }
        /** Visit a put instance field instruction. */
        public void visitPutfield(Quad obj) {
            if (obj.getOperator() instanceof Operator.Putfield.PUTFIELD_A
                || obj.getOperator() instanceof Operator.Putfield.PUTFIELD_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Operand base = Putfield.getBase(obj);
                Operand val = Putfield.getSrc(obj);
                Putfield.getField(obj).resolve();
                jq_Field f = Putfield.getField(obj).getField();
                if (IGNORE_INSTANCE_FIELDS) f = null;
                if (base instanceof RegisterOperand) {
                    Register base_r = ((RegisterOperand)base).getRegister();
                    if (val instanceof RegisterOperand) {
                        Register src_r = ((RegisterOperand)val).getRegister();
                        heapStore(base_r, src_r, f, obj);
                    } else if (val instanceof AConstOperand) {
                        jq_Reference type = ((AConstOperand)val).getType();
                        Object key = type;
                        ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                        if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                        heapStore(base_r, n, f, obj);
                    } else {
                        jq_Reference type = ((PConstOperand)val).getType();
                        UnknownTypeNode n = UnknownTypeNode.get(type);
                        heapStore(base_r, n, f, obj);
                    }
                } else {
                    // base is not a register?!
                }
            }
        }
        /** Visit a put static field instruction. */
        public void visitPutstatic(Quad obj) {
            if (obj.getOperator() instanceof Operator.Putstatic.PUTSTATIC_A
                || obj.getOperator() instanceof Operator.Putstatic.PUTSTATIC_P
                ) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Operand val = Putstatic.getSrc(obj);
                Putstatic.getField(obj).resolve();
                jq_Field f = Putstatic.getField(obj).getField();
                if (IGNORE_STATIC_FIELDS) f = null;
                if (val instanceof RegisterOperand) {
                    Register src_r = ((RegisterOperand)val).getRegister();
                    heapStore(my_global, src_r, f, obj);
                } else if (val instanceof AConstOperand) {
                    jq_Reference type = ((AConstOperand)val).getType();
                    Object key = type;
                    ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                    if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                    heapStore(my_global, n, f, obj);
                } else {
                    jq_Reference type = ((PConstOperand)val).getType();
                    UnknownTypeNode n = UnknownTypeNode.get(type);
                    heapStore(my_global, n, f, obj);
                }
            }
        }
        
        static void addToSet(Set s, Object o) {
            if (o instanceof Set) s.addAll((Set)o);
            else if (o != null) s.add(o);
        }
        
        /** Visit a return/throw instruction. */
        public void visitReturn(Quad obj) {
            Operand src = Return.getSrc(obj);
            Set r;
            if (obj.getOperator() == Return.RETURN_A.INSTANCE
                || obj.getOperator() == Return.RETURN_P.INSTANCE
                ) r = returned;
            else if (obj.getOperator() == Return.THROW_A.INSTANCE) r = thrown;
            else return;
            if (TRACE_INTRA) out.println("Visiting: "+obj);
            if (src instanceof RegisterOperand) {
                Register src_r = ((RegisterOperand)src).getRegister();
                addToSet(r, getRegister(src_r));
            } else if (src instanceof AConstOperand) {
                jq_Reference type = ((AConstOperand)src).getType();
                Object key = type;
                ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                r.add(n);
            } else {
                jq_Reference type = ((PConstOperand)src).getType();
                UnknownTypeNode n = UnknownTypeNode.get(type);
                r.add(n);
            }
        }
        
        static void setAsEscapes(Object o) {
            if (o instanceof Set) {
                for (Iterator i=((Set)o).iterator(); i.hasNext(); ) {
                    ((Node)i.next()).escapes = true;
                }
            } else {
                ((Node)o).escapes = true;
            }
        }
        
        public void visitSpecial(Quad obj) {
            if (obj.getOperator() == Special.GET_THREAD_BLOCK.INSTANCE) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register dest_r = ((RegisterOperand)Special.getOp1(obj)).getRegister();
                jq_Reference type = Scheduler.jq_Thread._class;
                ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(obj);
                if (n == null) quadsToNodes.put(obj, n = new ConcreteTypeNode(type, obj));
                n.setEscapes();
                setRegister(dest_r, n);
            } else if (obj.getOperator() == Special.SET_THREAD_BLOCK.INSTANCE) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register src_r = ((RegisterOperand)Special.getOp2(obj)).getRegister();
                setAsEscapes(getRegister(src_r));
                /*
            } else if (obj.getOperator() == Special.GET_TYPE_OF.INSTANCE) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register dest_r = ((RegisterOperand)Special.getOp1(obj)).getRegister();
                jq_Reference type = Clazz.jq_Reference._class;
                UnknownTypeNode n = UnknownTypeNode.get(type);
                setRegister(dest_r, n);
                */
            }
        }
        public void visitUnary(Quad obj) {
            if (obj.getOperator() == Unary.OBJECT_2ADDRESS.INSTANCE ||
                obj.getOperator() == Unary.ADDRESS_2OBJECT.INSTANCE) {
                if (TRACE_INTRA) out.println("Visiting: "+obj);
                Register dest_r = Unary.getDest(obj).getRegister();
                Operand src = Unary.getSrc(obj);
                if (src instanceof RegisterOperand) {
                    RegisterOperand rop = ((RegisterOperand)src);
                    Register src_r = rop.getRegister();
                    setRegister(dest_r, getRegister(src_r));
                } else if (src instanceof AConstOperand) {
                    jq_Reference type = ((AConstOperand)src).getType();
                    Object key = type;
                    ConcreteTypeNode n = (ConcreteTypeNode)quadsToNodes.get(key);
                    if (n == null) quadsToNodes.put(key, n = new ConcreteTypeNode(type, obj));
                    setRegister(dest_r, n);
                } else {
                    jq_Reference type = ((PConstOperand)src).getType();
                    UnknownTypeNode n = UnknownTypeNode.get(type);
                    setRegister(dest_r, n);
                }
            } else if (obj.getOperator() == Unary.INT_2ADDRESS.INSTANCE) {
                Register dest_r = Unary.getDest(obj).getRegister();
                jq_Reference type = Address._class;
                UnknownTypeNode n = UnknownTypeNode.get(type);
                setRegister(dest_r, n);
            }
        }
        public void visitExceptionThrower(Quad obj) {
            // special case for method invocation.
            if (obj.getOperator() instanceof Invoke) {
                Invoke.getMethod(obj).resolve();
                jq_Method m = Invoke.getMethod(obj).getMethod();
                ProgramLocation mc = new ProgramLocation.QuadProgramLocation(method, obj);
                ThrownExceptionNode n = (ThrownExceptionNode) callToTEN.get(mc);
                if (n == null) {
                    callToTEN.put(mc, n = new ThrownExceptionNode(mc));
                    passedAsParameter.add(n);
                }
                Util.Templates.ListIterator.ExceptionHandler eh = bb.getExceptionHandlers().exceptionHandlerIterator();
                while (eh.hasNext()) {
                    ExceptionHandler h = eh.nextExceptionHandler();
                    this.mergeWith(h);
                    this.start_states[h.getEntry().getID()].merge(nLocals, n);
                    if (h.mustCatch(Bootstrap.PrimordialClassLoader.getJavaLangThrowable()))
                        return;
                }
                this.thrown.add(n);
                return;
            }
            Util.Templates.ListIterator.jq_Class xs = obj.getThrownExceptions().classIterator();
            while (xs.hasNext()) {
                jq_Class x = xs.nextClass();
                UnknownTypeNode n = UnknownTypeNode.get(x);
                Util.Templates.ListIterator.ExceptionHandler eh = bb.getExceptionHandlers().exceptionHandlerIterator();
                boolean caught = false;
                while (eh.hasNext()) {
                    ExceptionHandler h = eh.nextExceptionHandler();
                    if (h.mayCatch(x)) {
                        this.mergeWith(h);
                        this.start_states[h.getEntry().getID()].merge(nLocals, n);
                    }
                    if (h.mustCatch(x)) {
                        caught = true;
                        break;
                    }
                }
                if (!caught) this.thrown.add(n);
            }
        }
        
    }
    
    /** Represents a particular parameter passed to a particular method call. */
    public static class PassedParameter {
        final ProgramLocation m; final int paramNum;
        public PassedParameter(ProgramLocation m, int paramNum) {
            this.m = m; this.paramNum = paramNum;
        }
        public ProgramLocation getCall() { return m; }
        public int getParamNum() { return paramNum; }
        public int hashCode() {
            return m.hashCode() ^ paramNum;
        }
        public boolean equals(PassedParameter that) { return this.m.equals(that.m) && this.paramNum == that.paramNum; }
        public boolean equals(Object o) { if (o instanceof PassedParameter) return equals((PassedParameter)o); return false; }
        public String toString() { return "Param "+paramNum+" for "+m; }
    }
    
    public static class CallSite {
        final MethodSummary caller; final ProgramLocation m;
        public CallSite(MethodSummary caller, ProgramLocation m) {
            this.caller = caller; this.m = m;
        }
        public int hashCode() { return (caller == null?0x0:caller.hashCode()) ^ m.hashCode(); }
        public boolean equals(CallSite that) { return this.m.equals(that.m) && this.caller == that.caller; }
        public boolean equals(Object o) { if (o instanceof CallSite) return equals((CallSite)o); return false; }
        public String toString() { return (caller!=null?caller.getMethod():null)+" "+m.getID()+" "+(m.getTargetMethod()!=null?m.getTargetMethod().getName():null); }
    }
    
    public static class Edge {
        // Node source;
        Node dest;
        AndersenField field;
        public Edge(Node source, Node dest, AndersenField field) {
            //this.source = source;
            this.dest = dest; this.field = field;
        }
        
        private static Edge INSTANCE = new Edge(null, null, null);
        
        private static Edge get(Node source, Node dest, AndersenField field) {
            //INSTANCE.source = source;
            INSTANCE.dest = dest; INSTANCE.field = field;
            return INSTANCE;
        }
        
        public int hashCode() {
            return 
                // source.hashCode() ^
                dest.hashCode() ^ ((field==null)?0x1ee7:field.hashCode());
        }
        public boolean equals(Edge that) {
            return this.field == that.field &&
                // this.source.equals(that.source) &&
                this.dest.equals(that.dest);
        }
        public boolean equals(Object o) {
            return equals((Edge)o);
        }
        public String toString() {
            return
                //source+
                "-"+((field==null)?"[]":field.getName().toString())+"->"+dest;
        }
    }
    
    public abstract static class Node implements Comparable {
        /** Map from fields to sets of predecessors on that field. 
         *  This only includes inside edges; outside edge predecessors are in FieldNode. */
        protected Map predecessors;
        /** Set of passed parameters for this node. */
        protected Set passedParameters;
        /** Map from fields to sets of inside edges from this node on that field. */
        protected Map addedEdges;
        /** Map from fields to sets of outside edges from this node on that field. */
        protected Map accessPathEdges;
        /** Unique id number. */
        protected final int id;
        /** Whether or not this node escapes into some unanalyzable code. */
        private boolean escapes;
        
        public static boolean TRACK_REASONS = false;
        
        /** Maps added edges to the quads that they come from.
            Only used if TRACK_REASONS is true. */
        HashMap edgesToReasons;
        
        private static int current_id = 0;
        
        protected Node() { this.id = ++current_id; }
        protected Node(Node that) {
            this.predecessors = that.predecessors;
            this.passedParameters = that.passedParameters;
            this.addedEdges = that.addedEdges;
            this.accessPathEdges = that.accessPathEdges;
            this.id = ++current_id;
            this.escapes = that.escapes;
            if (TRACK_REASONS) this.edgesToReasons = that.edgesToReasons;
        }
        
        public int hashCode() {
            if (USE_IDENTITY_HASHCODE)
                return System.identityHashCode(this);
            else
                return id;
        }
        
        public final int compareTo(Node that) {
            if (this.id > that.id) return 1;
            else if (this.id == that.id) return 0;
            else return -1;
        }
        public final int compareTo(Object o) {
            return compareTo((Node)o);
        }
        
        public Set getPassedParameters() { return passedParameters; }
        
        /** Replace this node by the given set of nodes.  All inside and outside
         *  edges to and from this node are replaced by sets of edges to and from
         *  the nodes in the set.  The passed parameter set of this node is also
         *  added to every node in the given set. */
        public void replaceBy(Set set, boolean removeSelf) {
            if (TRACE_INTRA) out.println("Replacing "+this+" with "+set+(removeSelf?", and removing self":""));
            if (set.contains(this)) {
                if (TRACE_INTRA) out.println("Replacing a node with itself, turning off remove self.");
                set.remove(this);
                if (set.isEmpty()) {
                    if (TRACE_INTRA) out.println("Replacing a node with only itself! Nothing to do.");
                    return;
                }
                removeSelf = false;
            }
            if (VERIFY_ASSERTIONS) Assert._assert(!set.contains(this));
            if (this.predecessors != null) {
                for (Iterator i=this.predecessors.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (removeSelf)
                        i.remove();
                    if (TRACE_INTRA) out.println("Looking at predecessor on field "+f+": "+o);
                    if (o == null) continue;
                    if (o instanceof Node) {
                        Node that = (Node)o;
                        Object q = (TRACK_REASONS && edgesToReasons != null) ? edgesToReasons.get(Edge.get(that, this, f)) : null;
                        if (removeSelf)
                            that._removeEdge(f, this);
                        if (that == this) {
                            // add self-cycles on f to all nodes in set.
                            if (TRACE_INTRA) out.println("Adding self-cycles on field "+f);
                            for (Iterator j=set.iterator(); j.hasNext(); ) {
                                Node k = (Node)j.next();
                                k.addEdge(f, k, q);
                            }
                        } else {
                            for (Iterator j=set.iterator(); j.hasNext(); ) {
                                that.addEdge(f, (Node)j.next(), q);
                            }
                        }
                    } else {
                        for (Iterator k=((Set)o).iterator(); k.hasNext(); ) {
                            Node that = (Node)k.next();
                            if (removeSelf) {
                                k.remove();
                                that._removeEdge(f, this);
                            }
                            Object q = (TRACK_REASONS && edgesToReasons != null) ? edgesToReasons.get(Edge.get(that, this, f)) : null;
                            if (that == this) {
                                // add self-cycles on f to all mapped nodes.
                                if (TRACE_INTRA) out.println("Adding self-cycles on field "+f);
                                for (Iterator j=set.iterator(); j.hasNext(); ) {
                                    Node k2 = (Node)j.next();
                                    k2.addEdge(f, k2, q);
                                }
                            } else {
                                for (Iterator j=set.iterator(); j.hasNext(); ) {
                                    that.addEdge(f, (Node)j.next(), q);
                                }
                            }
                        }
                    }
                }
            }
            if (this.addedEdges != null) {
                for (Iterator i=this.addedEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (removeSelf)
                        i.remove();
                    if (o == null) continue;
                    if (TRACE_INTRA) out.println("Looking at successor on field "+f+": "+o);
                    if (o instanceof Node) {
                        Node that = (Node)o;
                        if (that == this) continue; // cyclic edges handled above.
                        Object q = (TRACK_REASONS && edgesToReasons != null) ? edgesToReasons.get(Edge.get(this, that, f)) : null;
                        if (removeSelf) {
                            boolean b = that.removePredecessor(f, this);
                            if (TRACE_INTRA) out.println("Removed "+this+" from predecessor set of "+that+"."+f);
                            Assert._assert(b);
                        }
                        for (Iterator j=set.iterator(); j.hasNext(); ) {
                            Node node2 = (Node)j.next();
                            node2.addEdge(f, that, q);
                        }
                    } else {
                        for (Iterator k=((Set)o).iterator(); k.hasNext(); ) {
                            Node that = (Node)k.next();
                            if (removeSelf)
                                k.remove();
                            if (that == this) continue; // cyclic edges handled above.
                            Object q = (TRACK_REASONS && edgesToReasons != null) ? edgesToReasons.get(Edge.get(this, that, f)) : null;
                            if (removeSelf) {
                                boolean b = that.removePredecessor(f, this);
                                if (TRACE_INTRA) out.println("Removed "+this+" from predecessor set of "+that+"."+f);
                                Assert._assert(b);
                            }
                            for (Iterator j=set.iterator(); j.hasNext(); ) {
                                Node node2 = (Node)j.next();
                                node2.addEdge(f, that, q);
                            }
                        }
                    }
                }
            }
            if (this.accessPathEdges != null) {
                for (Iterator i=this.accessPathEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (removeSelf)
                        i.remove();
                    if (o == null) continue;
                    if (TRACE_INTRA) out.println("Looking at access path successor on field "+f+": "+o);
                    if (o instanceof FieldNode) {
                        FieldNode that = (FieldNode)o;
                        if (that == this) continue; // cyclic edges handled above.
                        if (removeSelf) {
                            that.field_predecessors.remove(this);
                            if (TRACE_INTRA) out.println("Removed "+this+" from access path predecessor set of "+that);
                        }
                        for (Iterator j=set.iterator(); j.hasNext(); ) {
                            Node node2 = (Node)j.next();
                            if (TRACE_INTRA) out.println("Adding access path edge "+node2+"->"+that);
                            node2.addAccessPathEdge(f, that);
                        }
                    } else {
                        for (Iterator k=((Set)o).iterator(); k.hasNext(); ) {
                            FieldNode that = (FieldNode)k.next();
                            if (removeSelf)
                                k.remove();
                            if (that == this) continue; // cyclic edges handled above.
                            if (removeSelf)
                                that.field_predecessors.remove(this);
                            for (Iterator j=set.iterator(); j.hasNext(); ) {
                                Node node2 = (Node)j.next();
                                node2.addAccessPathEdge(f, that);
                            }
                        }
                    }
                }
            }
            if (this.passedParameters != null) {
                if (TRACE_INTRA) out.println("Node "+this+" is passed as parameters: "+this.passedParameters+", adding those parameters to "+set);
                for (Iterator i=this.passedParameters.iterator(); i.hasNext(); ) {
                    PassedParameter pp = (PassedParameter)i.next();
                    for (Iterator j=set.iterator(); j.hasNext(); ) {
                        ((Node)j.next()).recordPassedParameter(pp);
                    }
                }
            }
        }
        
        /** Helper function to update map m given an update map um. */
        static void updateMap(Map um, Iterator i, Map m) {
            while (i.hasNext()) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                Object f = e.getKey();
                Object o = e.getValue();
                if (o == null) continue;
                if (o instanceof Node) {
                    Object q = um.get(o);
                    if (o instanceof UnknownTypeNode) q = o;
                    if (o == GlobalNode.GLOBAL) q = o;
                    if (VERIFY_ASSERTIONS) Assert._assert(q != null, o+" is missing from map");
                    if (TRACE_INTRA) out.println("Updated edge "+f+" "+o+" to "+q);
                    m.put(f, q);
                } else {
                    Set lhs = NodeSet.FACTORY.makeSet();
                    m.put(f, lhs);
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        Object r = j.next();
                        Assert._assert(r != null);
                        Object q = um.get(r);
                        if (r instanceof UnknownTypeNode) q = r;
                        if (r == GlobalNode.GLOBAL) q = o;
                        if (VERIFY_ASSERTIONS) Assert._assert(q != null, r+" is missing from map");
                        if (TRACE_INTRA) out.println("Updated edge "+f+" "+r+" to "+q);
                        lhs.add(q);
                    }
                }
            }
        }
        
        static void addGlobalEdges(Node n) {
            if (n.predecessors != null) {
                for (Iterator i=n.predecessors.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (o == GlobalNode.GLOBAL) {
                        // TODO: propagate reason.
                        GlobalNode.GLOBAL.addEdge(f, n, null);
                    } else if (o instanceof UnknownTypeNode) {
                        // TODO: propagate reason.
                        ((UnknownTypeNode)o).addEdge(f, n, null);
                    } else if (o instanceof Set) {
                        for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                            Object r = j.next();
                            if (r == GlobalNode.GLOBAL) {
                                // TODO: propagate reason.
                                GlobalNode.GLOBAL.addEdge(f, n, null);
                            } else if (r instanceof UnknownTypeNode) {
                                // TODO: propagate reason.
                                ((UnknownTypeNode)r).addEdge(f, n, null);
                            }
                        }
                    }
                }
            }
            if (n.addedEdges != null) {
                for (Iterator i=n.addedEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (o instanceof UnknownTypeNode) {
                        // TODO: propagate reason.
                        n.addEdge(f, (UnknownTypeNode)o, null);
                    } else if (o instanceof Set) {
                        for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                            Object r = j.next();
                            if (r instanceof UnknownTypeNode) {
                                // TODO: propagate reason.
                                n.addEdge(f, (UnknownTypeNode)r, null);
                            }
                        }
                    }
                }
            }
        }
        
        static void updateMap_unknown(Map um, Iterator i, Map m) {
            while (i.hasNext()) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o == null) continue;
                if (o instanceof Node) {
                    Object q = um.get(o);
                    if (q == null) q = o;
                    else if (TRACE_INTRA) out.println("Updated edge "+f+" "+o+" to "+q);
                    m.put(f, q);
                } else {
                    Set lhs = NodeSet.FACTORY.makeSet();
                    m.put(f, lhs);
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        Object r = j.next();
                        Assert._assert(r != null);
                        Object q = um.get(r);
                        if (q == null) q = r;
                        else if (TRACE_INTRA) out.println("Updated edge "+f+" "+r+" to "+q);
                        lhs.add(q);
                    }
                }
            }
        }
        
        /** Update all predecessor and successor nodes with the given update map.
         *  Also clones the passed parameter set.
         */
        public void update(HashMap um) {
            if (TRACE_INTRA) out.println("Updating edges for node "+this.toString_long());
            Map m = this.predecessors;
            if (m != null) {
                this.predecessors = new LinkedHashMap();
                updateMap(um, m.entrySet().iterator(), this.predecessors);
            }
            m = this.addedEdges;
            if (m != null) {
                this.addedEdges = new LinkedHashMap();
                updateMap(um, m.entrySet().iterator(), this.addedEdges);
            }
            m = this.accessPathEdges;
            if (m != null) {
                this.accessPathEdges = new LinkedHashMap();
                updateMap(um, m.entrySet().iterator(), this.accessPathEdges);
            }
            if (this.passedParameters != null) {
                Set pp = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
                pp.addAll(this.passedParameters);
                this.passedParameters = pp;
            }
            addGlobalEdges(this);
        }
        
        /** Return the declared type of this node. */
        public abstract AndersenReference getDeclaredType();
        
        /** Return true if this node equals another node.
         *  Two nodes are equal if they have all the same edges and equivalent passed
         *  parameter sets.
         */
        /*
        public boolean equals(Node that) {
            if (this.predecessors != that.predecessors) {
                if ((this.predecessors == null) || (that.predecessors == null)) return false;
                if (!this.predecessors.equals(that.predecessors)) return false;
            }
            if (this.passedParameters != that.passedParameters) {
                if ((this.passedParameters == null) || (that.passedParameters == null)) return false;
                if (!this.passedParameters.equals(that.passedParameters)) return false;
            }
            if (this.addedEdges != that.addedEdges) {
                if ((this.addedEdges == null) || (that.addedEdges == null)) return false;
                if (!this.addedEdges.equals(that.addedEdges)) return false;
            }
            if (this.accessPathEdges != that.accessPathEdges) {
                if ((this.accessPathEdges == null) || (that.accessPathEdges == null)) return false;
                if (!this.accessPathEdges.equals(that.accessPathEdges)) return false;
            }
            return true;
        }
        public boolean equals(Object o) {
            if (o instanceof Node) return equals((Node)o);
            return false;
        }
         */
        /** Return a shallow copy of this node. */
        public abstract Node copy();
        
        public boolean hasPredecessor(AndersenField f, Node n) {
            Object o = this.predecessors.get(f);
            if (o instanceof Node) {
                if (n != o) {
                    Assert.UNREACHABLE("predecessor of "+this+" should be "+n+", but is "+o);
                    return false;
                }
            } else if (o == null) {
                Assert.UNREACHABLE("predecessor of "+this+" should be "+n+", but is missing");
                return false;
            } else {
                Set s = (Set) o;
                if (!s.contains(n)) {
                    Assert.UNREACHABLE("predecessor of "+this+" should be "+n);
                    return false;
                }
            }
            return true;
        }

        /** Remove the given predecessor node on the given field from the predecessor set.
         *  Returns true if that predecessor existed, false otherwise. */
        public boolean removePredecessor(AndersenField m, Node n) {
            if (predecessors == null) return false;
            Object o = predecessors.get(m);
            if (o instanceof Set) return ((Set)o).remove(n);
            else if (o == n) { predecessors.remove(m); return true; }
            else return false;
        }
        /** Add the given predecessor node on the given field to the predecessor set.
         *  Returns true if that predecessor didn't already exist, false otherwise. */
        public boolean addPredecessor(AndersenField m, Node n) {
            if (predecessors == null) predecessors = new LinkedHashMap();
            Object o = predecessors.get(m);
            if (o == null) {
                predecessors.put(m, n);
                return true;
            }
            if (o instanceof Set) return ((Set)o).add(n);
            if (o == n) return false;
            Set s = NodeSet.FACTORY.makeSet(); s.add(o); s.add(n);
            predecessors.put(m, s);
            return true;
        }
        
        /** Return a set of Map.Entry objects corresponding to the incoming inside edges
         *  of this node. */
        public Set getPredecessors() {
            if (predecessors == null) return Collections.EMPTY_SET;
            return predecessors.entrySet();
        }
        
        /** Record the given passed parameter in the set for this node.
         *  Returns true if that passed parameter didn't already exist, false otherwise. */
        public boolean recordPassedParameter(PassedParameter cm) {
            if (passedParameters == null) passedParameters = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
            return passedParameters.add(cm);
        }
        /** Record the passed parameter of the given method call and argument number in
         *  the set for this node.
         *  Returns true if that passed parameter didn't already exist, false otherwise. */
        public boolean recordPassedParameter(ProgramLocation m, int paramNum) {
            if (passedParameters == null) passedParameters = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
            PassedParameter cm = new PassedParameter(m, paramNum);
            return passedParameters.add(cm);
        }
        private boolean _removeEdge(AndersenField m, Node n) {
            Object o = addedEdges.get(m);
            if (o instanceof Set) return ((Set)o).remove(n);
            else if (o == n) { addedEdges.remove(m); return true; }
            else return false;
        }
        /** Remove the given successor node on the given field from the inside edge set.
         *  Also removes the predecessor link from the successor node to this node.
         *  Returns true if that edge existed, false otherwise. */
        public boolean removeEdge(AndersenField m, Node n) {
            if (addedEdges == null) return false;
            n.removePredecessor(m, this);
            return _removeEdge(m, n);
        }
        public boolean hasEdge(AndersenField m, Node n) {
            if (addedEdges == null) return false;
            Object o = addedEdges.get(m);
            if (o == n) return true;
            if (o instanceof Set) {
                return ((Set)o).contains(n);
            }
            return false;
        }
        /** Add the given successor node on the given field to the inside edge set.
         *  Also adds a predecessor link from the successor node to this node.
         *  Returns true if that edge didn't already exist, false otherwise. */
        public boolean addEdge(AndersenField m, Node n, Object q) {
            if (TRACK_REASONS) {
                if (edgesToReasons == null) edgesToReasons = new HashMap();
                //if (!edgesToReasons.containsKey(Edge.get(this, n, m)))
                    edgesToReasons.put(new Edge(this, n, m), q);
            }
            n.addPredecessor(m, this);
            if (addedEdges == null) addedEdges = new LinkedHashMap();
            Object o = addedEdges.get(m);
            if (o == null) {
                addedEdges.put(m, n);
                return true;
            }
            if (o instanceof Set) {
                return ((Set)o).add(n);
            }
            if (o == n) {
                return false;
            }
            Set s = NodeSet.FACTORY.makeSet(); s.add(o); s.add(n);
            addedEdges.put(m, s);
            return true;
        }
        /** Add the given set of successor nodes on the given field to the inside edge set.
         *  The given set is consumed.
         *  Also adds predecessor links from the successor nodes to this node.
         *  Returns true if the inside edge set changed, false otherwise. */
        public boolean addEdges(AndersenField m, Set s, Object q) {
            if (TRACK_REASONS) {
                if (edgesToReasons == null) edgesToReasons = new HashMap();
            }
            for (Iterator i=s.iterator(); i.hasNext(); ) {
                Node n = (Node)i.next();
                if (TRACK_REASONS) {
                    //if (!edgesToReasons.containsKey(Edge.get(this, n, m)))
                        edgesToReasons.put(new Edge(this, n, m), q);
                }
                n.addPredecessor(m, this);
            }
            if (addedEdges == null) addedEdges = new LinkedHashMap();
            Object o = addedEdges.get(m);
            if (o == null) {
                addedEdges.put(m, s);
                return true;
            }
            if (o instanceof Set) {
                return ((Set)o).addAll(s);
            }
            addedEdges.put(m, s); return s.add(o); 
        }
        /** Add the given successor node on the given field to the inside edge set
         *  of all of the given set of nodes.
         *  Also adds predecessor links from the successor node to the given nodes.
         *  Returns true if anything was changed, false otherwise. */
        public static boolean addEdges(Set s, AndersenField f, Node n, Quad q) {
            boolean b = false;
            for (Iterator i=s.iterator(); i.hasNext(); ) {
                Node a = (Node)i.next();
                if (a.addEdge(f, n, q)) b = true;
            }
            return b;
        }
        
        private boolean _removeAccessPathEdge(AndersenField m, FieldNode n) {
            Object o = accessPathEdges.get(m);
            if (o instanceof Set) return ((Set)o).remove(n);
            else if (o == n) { accessPathEdges.remove(m); return true; }
            else return false;
        }
        /** Remove the given successor node on the given field from the outside edge set.
         *  Also removes the predecessor link from the successor node to this node.
         *  Returns true if that edge existed, false otherwise. */
        public boolean removeAccessPathEdge(AndersenField m, FieldNode n) {
            if (accessPathEdges == null) return false;
            if (n.field_predecessors != null) n.field_predecessors.remove(this);
            return _removeAccessPathEdge(m, n);
        }
        public boolean hasAccessPathEdge(AndersenField m, Node n) {
            if (accessPathEdges == null) return false;
            Object o = accessPathEdges.get(m);
            if (o == n) return true;
            if (o instanceof Set) {
                return ((Set)o).contains(n);
            }
            return false;
        }
        /** Add the given successor node on the given field to the outside edge set.
         *  Also adds a predecessor link from the successor node to this node.
         *  Returns true if that edge didn't already exist, false otherwise. */
        public boolean addAccessPathEdge(AndersenField m, FieldNode n) {
            if (n.field_predecessors == null) n.field_predecessors = NodeSet.FACTORY.makeSet();
            n.field_predecessors.add(this);
            if (accessPathEdges == null) accessPathEdges = new LinkedHashMap();
            Object o = accessPathEdges.get(m);
            if (o == null) {
                accessPathEdges.put(m, n);
                return true;
            }
            if (o instanceof Set) return ((Set)o).add(n);
            if (o == n) return false;
            Set s = NodeSet.FACTORY.makeSet(); s.add(o); s.add(n);
            accessPathEdges.put(m, s);
            return true;
        }
        /** Add the given set of successor nodes on the given field to the outside edge set.
         *  The given set is consumed.
         *  Also adds predecessor links from the successor nodes to this node.
         *  Returns true if the inside edge set changed, false otherwise. */
        public boolean addAccessPathEdges(jq_Field m, Set s) {
            for (Iterator i=s.iterator(); i.hasNext(); ) {
                FieldNode n = (FieldNode)i.next();
                if (n.field_predecessors == null) n.field_predecessors = NodeSet.FACTORY.makeSet();
                n.field_predecessors.add(this);
            }
            if (accessPathEdges == null) accessPathEdges = new LinkedHashMap();
            Object o = accessPathEdges.get(m);
            if (o == null) {
                accessPathEdges.put(m, s);
                return true;
            }
            if (o instanceof Set) return ((Set)o).addAll(s);
            accessPathEdges.put(m, s); return s.add(o); 
        }
        
        /** Add the nodes that are targets of inside edges on the given field
         *  to the given result set. */
        public final void getEdges(AndersenField m, Set result) {
            if (addedEdges != null) {
                Object o = addedEdges.get(m);
                if (o != null) {
                    if (o instanceof Set) {
                        result.addAll((Set)o);
                    } else {
                        result.add(o);
                    }
                }
            }
            if (this.escapes)
                getEdges_escaped(m, result);
        }
        
        public final Set getEdges(AndersenField m) {
            if (addedEdges != null) {
                Object o = addedEdges.get(m);
                if (o != null) {
                    if (o instanceof Set) {
                        Set s = (Set)o;
                        if (this.escapes)
                            getEdges_escaped(m, s);
                        return s;
                    } else {
                        if (this.escapes) {
                            Set s = NodeSet.FACTORY.makeSet(2);
                            s.add(o);
                            getEdges_escaped(m, s);
                            return s;
                        }
                        return Collections.singleton(o);
                    }
                }
            }
            if (this.escapes) {
                Set s = NodeSet.FACTORY.makeSet(1);
                getEdges_escaped(m, s);
                return s;
            }
            return Collections.EMPTY_SET;
        }
        
        public final Set getNonEscapingEdges(AndersenField m) {
            if (addedEdges == null) return Collections.EMPTY_SET;
            Object o = addedEdges.get(m);
            if (o == null) return Collections.EMPTY_SET;
            if (o instanceof Set) {
                return (Set)o;
            } else {
                return Collections.singleton(o);
            }
        }
        
        /** Add the nodes that are targets of inside edges on the given field
         *  to the given result set. */
        public void getEdges_escaped(AndersenField m, Set result) {
            if (TRACE_INTER) out.println("Getting escaped edges "+this+"."+m);
            AndersenReference type = this.getDeclaredType();
            if (m == null) {
                if (type != null && (type.isArrayType() || type == PrimordialClassLoader.getJavaLangObject()))
                    result.add(UnknownTypeNode.get(PrimordialClassLoader.getJavaLangObject()));
                return;
            }
            if (type != null) {
                type.prepare();
                m.and_getDeclaringClass().prepare();
                if (Run_Time.TypeCheck.isAssignable((jq_Type)type, (jq_Type)m.and_getDeclaringClass()) ||
                    Run_Time.TypeCheck.isAssignable((jq_Type)m.and_getDeclaringClass(), (jq_Type)type)) {
                    jq_Reference r = (jq_Reference)m.and_getType();
                    result.add(UnknownTypeNode.get(r));
                } else {
                    if (TRACE_INTER) out.println("Object of type "+type+" cannot possibly have field "+m);
                }
            }
            if (TRACE_INTER) out.println("New result: "+result);
        }
        
        /** Return a set of Map.Entry objects corresponding to the inside edges
         *  of this node. */
        public Set getEdges() {
            if (addedEdges == null) return Collections.EMPTY_SET;
            return addedEdges.entrySet();
        }

        /** Return the set of fields that this node has inside edges with. */
        public Set getEdgeFields() {
            if (addedEdges == null) return Collections.EMPTY_SET;
            return addedEdges.keySet();
        }
        
        /** Returns true if this node has any added inside edges. */
        public boolean hasEdges() {
            return addedEdges != null;
        }
        
        /** Returns true if this node has any added outside edges. */
        public boolean hasAccessPathEdges() {
            return accessPathEdges != null;
        }
        
        /** Add the nodes that are targets of outside edges on the given field
         *  to the given result set. */
        public void getAccessPathEdges(jq_Field m, Set result) {
            if (accessPathEdges == null) return;
            Object o = accessPathEdges.get(m);
            if (o == null) return;
            if (o instanceof Set) {
                result.addAll((Set)o);
            } else {
                result.add(o);
            }
        }
        
        /** Return a set of Map.Entry objects corresponding to the outside edges
         *  of this node. */
        public Set getAccessPathEdges() {
            if (accessPathEdges == null) return Collections.EMPTY_SET;
            return accessPathEdges.entrySet();
        }
        
        /** Return the set of fields that this node has outside edges with. */
        public Set getAccessPathEdgeFields() {
            if (accessPathEdges == null) return Collections.EMPTY_SET;
            return accessPathEdges.keySet();
        }
        
        public Quad getSourceQuad(AndersenField f, Node n) {
            if (false) {
                if (edgesToReasons == null) return null;
                return (Quad)edgesToReasons.get(Edge.get(this, n, f));
            }
            return null;
        }
        
        public void setEscapes() { this.escapes = true; }
        public boolean getEscapes() { return this.escapes; }
        
        /** Return a string representation of the node in short form. */
        public abstract String toString_short();
        public String toString() { return toString_short() + (this.escapes?"*":""); }
        /** Return a string representation of the node in long form.
         *  Includes inside and outside edges and passed parameters. */
        public String toString_long() {
            StringBuffer sb = new StringBuffer();
            if (addedEdges != null) {
                sb.append(" writes: ");
                for (Iterator i=addedEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    jq_Field f = (jq_Field)e.getKey();
                    Object o = e.getValue();
                    if (o == null) continue;
                    sb.append(f);
                    sb.append("={");
                    if (o instanceof Node)
                        sb.append(((Node)o).toString_short());
                    else {
                        for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                           sb.append(((Node)j.next()).toString_short());
                           if (j.hasNext()) sb.append(", ");
                        }
                    }
                    sb.append("} ");
                }
            }
            if (accessPathEdges != null) {
                sb.append(" reads: ");
                sb.append(accessPathEdges);
            }
            if (passedParameters != null) {
                sb.append(" called: ");
                sb.append(passedParameters);
            }
            return sb.toString();
        }
        
        /**
         * Prints an identifier of this node to the given output writer.
         * 
         * @param ms method summary that contains this node
         * @param out print writer to output to
         */
        public abstract void write(MethodSummary ms, DataOutput out) throws IOException;
        
    }
    
    /** A ConcreteTypeNode refers to an object with a concrete type.
     *  This is the result of a 'new' operation or a constant object.
     *  It is tied to the quad that created it, so nodes of the same type but
     *  from different quads are not equal.
     */
    public static final class ConcreteTypeNode extends Node {
        final AndersenReference type; final Quad q;
        
        static final HashMap FACTORY = new HashMap();
        public static ConcreteTypeNode get(AndersenReference type) {
            ConcreteTypeNode n = (ConcreteTypeNode)FACTORY.get(type);
            if (n == null) {
                FACTORY.put(type, n = new ConcreteTypeNode(type));
            }
            return n;
        }
        
        public final Node copy() { return new ConcreteTypeNode(this); }
        
        public ConcreteTypeNode(AndersenReference type) { this.type = type; this.q = null; }
        public ConcreteTypeNode(AndersenReference type, Quad q) { this.type = type; this.q = q; }

        private ConcreteTypeNode(ConcreteTypeNode that) {
            super(that);
            this.type = that.type; this.q = that.q;
        }
        
        public Quad getQuad() { return q; }
        
        public AndersenReference getDeclaredType() { return type; }
        
        public String toString_long() { return Integer.toHexString(this.hashCode())+": "+toString_short()+super.toString_long(); }
        public String toString_short() { return "Concrete: "+type+" q: "+(q==null?-1:q.getID()); }

        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("Concrete ");
            writeType(out, (jq_Reference) type);
            out.writeByte(' ');
            jq_Method m = (jq_Method) ms.getMethod();
            writeQuad(out, m, q);
        }
    }
    
    public static Node readNode(StringTokenizer st) throws IOException {
        String s = st.nextToken();
        if (s.equals("Global")) {
            return new GlobalNode();
        }
        if (s.equals("Concrete")) {
            jq_Reference r = readType(st);
            //Quad q = readQuad(st, New.class);
            Quad q = readQuad(st, Operator.class);
            return new ConcreteTypeNode(r, q);
        }
        if (s.equals("ConcreteObject")) {
            // TODO.
            jq_Reference r = readType(st);
            return new ConcreteTypeNode(r);
        }
        if (s.equals("Unknown")) {
            jq_Reference r = readType(st);
            return new UnknownTypeNode(r);
        }
        if (s.equals("ReturnValue")) {
            ProgramLocation pl = readLocation(st);
            return new ReturnValueNode(pl);
        }
        if (s.equals("ThrownException")) {
            ProgramLocation pl = readLocation(st);
            return new ThrownExceptionNode(pl);
        }
        if (s.equals("Param")) {
            jq_Method m = (jq_Method) readMember(st);
            int i = Integer.parseInt(st.nextToken());
            jq_Reference t = (jq_Reference) m.getParamTypes()[i];
            return new ParamNode(m, i, t);
        }
        if (s.equals("Field")) {
            jq_Field m = (jq_Field) readMember(st);
            return new FieldNode(m);
        }
        if (s.equals("null")) {
            return null;
        }
        Assert.UNREACHABLE(s);
        return null;
    }
    
    public static void writeType(DataOutput out, jq_Reference type) throws IOException {
        if (type == null) out.writeBytes("null");
        else out.writeBytes(type.getDesc().toString());
    }
    
    public static jq_Reference readType(StringTokenizer st) {
        String desc = st.nextToken();
        if (desc.equals("null")) return null;
        jq_Reference r = (jq_Reference) PrimordialClassLoader.loader.getOrCreateBSType(desc);
        return r;
    }
    
    public static void writeMember(DataOutput out, jq_Member m) throws IOException {
        if (m == null) {
            out.writeBytes("null");
        } else {
            writeType(out, m.getDeclaringClass());
            out.writeBytes(" "+m.getName()+" "+m.getDesc());
        }
    }
    
    public static jq_Member readMember(StringTokenizer st) {
        jq_Class c = (jq_Class) readType(st);
        if (c == null) return null;
        c.load();
        String name = st.nextToken();
        String desc = st.nextToken();
        return c.getDeclaredMember(name, desc);
    }
    
    public static void writeQuad(DataOutput out, jq_Method m, Quad q) throws IOException {
        if (q == null) {
            out.writeBytes("null"); 
        } else {
            Map map = CodeCache.getBCMap(m);
            Integer i = (Integer) map.get(q);
            if (i == null) {
                Assert.UNREACHABLE("Error: no mapping for quad "+q);
            }
            int bcIndex = i.intValue();
            writeMember(out, m);
            out.writeBytes(" "+bcIndex);
        }
    }
    
    public static Quad readQuad(StringTokenizer st, Class op) {
        jq_Method m = (jq_Method) readMember(st);
        if (m == null) return null;
        int bcIndex = Integer.parseInt(st.nextToken());
        Map map = CodeCache.getBCMap(m);
        for (Iterator i = map.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry e = (Map.Entry) i.next();
            int index = ((Integer) e.getValue()).intValue();
            if (index != bcIndex) continue;
            Quad q = (Quad) e.getKey();
            if (op.isInstance(q.getOperator())) return q;
        }
        //Assert.UNREACHABLE("Error: no quad of type "+op+" at bytecode index "+bcIndex);
        return null;
    }
    
    public static void writeLocation(DataOutput out, ProgramLocation l) throws IOException {
        writeMember(out, (jq_Method) l.getMethod());
        out.writeBytes(" "+l.getBytecodeIndex());
    }
    
    public static ProgramLocation readLocation(StringTokenizer st) throws IOException {
        jq_Method m = (jq_Method) readMember(st);
        int index = Integer.parseInt(st.nextToken());
        return new ProgramLocation.BCProgramLocation(m, index);
    }
    
    /** A ConcreteObjectNode refers to an object that we discovered through reflection.
     * It includes a reference to the actual object instance.
     */
    public static final class ConcreteObjectNode extends Node {
        final Object object;
        
        public static Collection getAll() {
            return FACTORY.values();
        }
        
        public static final boolean ADD_EDGES = true;
        static final HashMap FACTORY = new HashMap();
        public static ConcreteObjectNode get(Object o) {
            ConcreteObjectNode n = (ConcreteObjectNode) FACTORY.get(o);
            if (n == null) {
                FACTORY.put(o, n = new ConcreteObjectNode(o));
            } else {
                return n;
            }
            if (o != null) {
                if (ADD_EDGES) {
                    // add edges.
                    jq_Reference type = jq_Reference.getTypeOf(o);
                    if (type.isClassType()) {
                        jq_Class c = (jq_Class) type;
                        c.prepare();
                        jq_InstanceField[] ifs = c.getInstanceFields();
                        for (int i=0; i<ifs.length; ++i) {
                            if (ifs[i].getType().isPrimitiveType()) continue;
                            Object p = Reflection.getfield_A(o, ifs[i]);
                            n.addEdge(ifs[i], get(p), null);
                        }
                    } else {
                        Assert._assert(type.isArrayType());
                        jq_Array a = (jq_Array) type;
                        if (a.getElementType().isReferenceType()) {
                            Object[] oa = (Object[]) o;
                            for (int i=0; i<oa.length; ++i) {
                                n.addEdge(null, get(oa[i]), null);
                            }
                        }
                    }
                }
            }
            return n;
        }
        
        public final Node copy() { return new ConcreteObjectNode(this); }
        
        public ConcreteObjectNode(Object o) { this.object = o; }

        private ConcreteObjectNode(ConcreteObjectNode that) {
            super(that);
            this.object = that.object;
        }
        
        public AndersenReference getDeclaredType() {
            if (object == null) return null;
            return jq_Reference.getTypeOf(object);
        }
        
        public String toString_long() { return Integer.toHexString(this.hashCode())+": "+toString_short()+super.toString_long(); }
        public String toString_short() { return "Object "+getDeclaredType(); }
        
        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#getEdgeFields()
         */
        public Set getEdgeFields() {
            if (ADD_EDGES)
                return super.getEdgeFields();
            if (object == null) return Collections.EMPTY_SET;
            jq_Reference type = jq_Reference.getTypeOf(object);
            HashSet ll = new HashSet();
            if (type.isClassType()) {
                jq_Class c = (jq_Class) type;
                c.prepare();
                jq_InstanceField[] ifs = c.getInstanceFields();
                for (int i=0; i<ifs.length; ++i) {
                    if (ifs[i].getType().isPrimitiveType()) continue;
                    ll.add(ifs[i]);
                }
            } else {
                Assert._assert(type.isArrayType());
                jq_Array a = (jq_Array) type;
                if (a.getElementType().isReferenceType()) {
                    ll.add(null);
                }
            }
            ll.addAll(super.getEdgeFields());
            return ll;
        }

        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#getEdges()
         */
        public Set getEdges() {
            if (ADD_EDGES)
                return super.getEdges();
            if (object == null) return Collections.EMPTY_SET;
            jq_Reference type = jq_Reference.getTypeOf(object);
            HashMap ll = new HashMap();
            if (type.isClassType()) {
                jq_Class c = (jq_Class) type;
                c.prepare();
                jq_InstanceField[] ifs = c.getInstanceFields();
                for (int i=0; i<ifs.length; ++i) {
                    if (ifs[i].getType().isPrimitiveType()) continue;
                    ll.put(ifs[i], get(Reflection.getfield_A(object, ifs[i])));
                }
            } else {
                Assert._assert(type.isArrayType());
                jq_Array a = (jq_Array) type;
                if (a.getElementType().isReferenceType()) {
                    Object[] oa = (Object[]) object;
                    for (int i=0; i<oa.length; ++i) {
                        ll.put(null, get(oa[i]));
                    }
                }
            }
            if (addedEdges != null)
                ll.putAll(addedEdges);
            return ll.entrySet();
        }

        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#hasEdge(Compil3r.Quad.AndersenInterface.AndersenField, Compil3r.Quad.MethodSummary.Node)
         */
        public boolean hasEdge(AndersenField m, Node n) {
            if (ADD_EDGES)
                return super.hasEdge(m, n);
            if (object == null)
                return false;
            if (!(n instanceof ConcreteObjectNode))
                return super.hasEdge(m, n);
            Object other = ((ConcreteObjectNode) n).object;
            jq_Reference type = jq_Reference.getTypeOf(object);
            if (type.isClassType()) {
                jq_Class c = (jq_Class) type;
                c.prepare();
                jq_InstanceField[] ifs = c.getInstanceFields();
                if (!Arrays.asList(ifs).contains(m)) return false;
                Object p = Reflection.getfield_A(object, (jq_InstanceField) m);
                if (p == other) return true;
            } else {
                Assert._assert(type.isArrayType());
                if (m != null) return false;
                jq_Array a = (jq_Array) type;
                if (!a.getElementType().isReferenceType()) return false;
                Object[] oa = (Object[]) object;
                for (int i=0; i<oa.length; ++i) {
                    if (other == oa[i]) return true;
                }
            }
            return super.hasEdge(m, n);
        }

        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#hasEdges()
         */
        public boolean hasEdges() {
            if (ADD_EDGES)
                return super.hasEdges();
            return object != null;
        }

        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#removeEdge(Compil3r.Quad.AndersenInterface.AndersenField, Compil3r.Quad.MethodSummary.Node)
         */
        public boolean removeEdge(AndersenField m, Node n) {
            Assert._assert(!(n instanceof ConcreteObjectNode));
            return super.removeEdge(m, n);
        }

        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("ConcreteObject ");
            writeType(out, (jq_Reference) getDeclaredType());
        }

    }
    
    /** A UnknownTypeNode refers to an object with an unknown type.  All that is
     *  known is that the object is the same or a subtype of some given type.
     *  Nodes with the same "type" are considered to be equal.
     *  This class includes a factory to get UnknownTypeNode's.
     */
    public static final class UnknownTypeNode extends Node {
        public static final boolean ADD_DUMMY_EDGES = false;
        
        static final HashMap FACTORY = new HashMap();
        public static UnknownTypeNode get(AndersenReference type) {
            UnknownTypeNode n = (UnknownTypeNode)FACTORY.get(type);
            if (n == null) {
                FACTORY.put(type, n = new UnknownTypeNode(type));
                if (ADD_DUMMY_EDGES) n.addDummyEdges();
            }
            return n;
        }
        
        public static Collection getAll() {
            return FACTORY.values();
        }
        
        final AndersenReference type;
        
        private UnknownTypeNode(AndersenReference type) {
            this.type = type; this.setEscapes();
        }
        
        private void addDummyEdges() {
            if (type instanceof jq_Class) {
                jq_Class klass = (jq_Class)type;
                klass.prepare();
                jq_InstanceField[] fields = klass.getInstanceFields();
                for (int i=0; i<fields.length; ++i) {
                    jq_InstanceField f = fields[i];
                    if (f.getType() instanceof jq_Reference) {
                        UnknownTypeNode n = get((jq_Reference)f.getType());
                        this.addEdge(f, n, null);
                    }
                }
            } else {
                jq_Array array = (jq_Array)type;
                if (array.getElementType() instanceof jq_Reference) {
                    UnknownTypeNode n = get((jq_Reference)array.getElementType());
                    this.addEdge(null, n, null);
                }
            }
        }
        
        /** Update all predecessor and successor nodes with the given update map.
         *  Also clones the passed parameter set.
         */
        public void update(HashMap um) {
            if (false) {
                if (TRACE_INTRA) out.println("Updating edges for node "+this.toString_long());
                Map m = this.predecessors;
                if (m != null) {
                    this.predecessors = new LinkedHashMap();
                    updateMap_unknown(um, m.entrySet().iterator(), this.predecessors);
                }
                m = this.addedEdges;
                if (m != null) {
                    this.addedEdges = new LinkedHashMap();
                    updateMap_unknown(um, m.entrySet().iterator(), this.addedEdges);
                }
                m = this.accessPathEdges;
                if (m != null) {
                    this.accessPathEdges = new LinkedHashMap();
                    updateMap_unknown(um, m.entrySet().iterator(), this.accessPathEdges);
                }
                if (this.passedParameters != null) {
                    Set pp = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
                    pp.addAll(this.passedParameters); 
                    this.passedParameters = pp;
                }
                addGlobalEdges(this);
            }
        }
        
        public AndersenReference getDeclaredType() { return type; }
        
        public final Node copy() { return this; }
        
        public String toString_long() { return Integer.toHexString(this.hashCode())+": "+toString_short()+super.toString_long(); }
        public String toString_short() { return "Unknown: "+type; }

        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("Unknown ");
            writeType(out, (jq_Reference) getDeclaredType());
        }
    }
    
    /** An outside node is some node that can be mapped to other nodes.
     *  This is just a marker for some of the other node classes below.
     */
    public abstract static class OutsideNode extends Node {
        OutsideNode() {}
        OutsideNode(Node n) { super(n); }
        
        public abstract AndersenReference getDeclaredType();
        
        OutsideNode skip;
        boolean visited;
        
    }
    
    /** A GlobalNode stores references to the static variables.
     *  It has no predecessors, and there is a global copy stored in GLOBAL.
     */
    public static final class GlobalNode extends OutsideNode {
        public GlobalNode() {
            if (TRACE_INTRA) out.println("Created "+this.toString_long());
        }
        private GlobalNode(GlobalNode that) {
            super(that);
        }
        public AndersenReference getDeclaredType() { Assert.UNREACHABLE(); return null; }
        public final Node copy() {
            Assert._assert(this != GLOBAL);
            return new GlobalNode(this);
        }
        public String toString_long() { return Integer.toHexString(this.hashCode())+": "+toString_short()+super.toString_long(); }
        public String toString_short() { return "global@"+Integer.toHexString(this.hashCode()); }
        public static GlobalNode GLOBAL = new GlobalNode();
        
        public void addDefaultStatics() {
            jq_Class c;
            jq_StaticField f;
            Node n;
            
            c = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("Ljava/lang/System;");
            c.load();
            f = (jq_StaticField) c.getDeclaredMember("in", "Ljava/io/InputStream;");
            Assert._assert(f != null);
            n = ConcreteObjectNode.get(System.in);
            addEdge(f, n, null);
            f = (jq_StaticField) c.getDeclaredMember("out", "Ljava/io/PrintStream;");
            Assert._assert(f != null);
            n = ConcreteObjectNode.get(System.out);
            addEdge(f, n, null);
            f = (jq_StaticField) c.getDeclaredMember("err", "Ljava/io/PrintStream;");
            Assert._assert(f != null);
            n = ConcreteObjectNode.get(System.err);
            addEdge(f, n, null);
            
            //System.out.println("Edges from global: "+getEdges());
        }
        
        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("Global");
        }
        
    }
    
    /** A ReturnedNode represents a return value or thrown exception from a method call. */
    public abstract static class ReturnedNode extends OutsideNode {
        final ProgramLocation m;
        public ReturnedNode(ProgramLocation m) { this.m = m; }
        public ReturnedNode(ReturnedNode that) {
            super(that); this.m = that.m;
        }
        public final ProgramLocation getMethodCall() { return m; }
    }
    
    /** A ReturnValueNode represents the return value of a method call.
     */
    public static final class ReturnValueNode extends ReturnedNode {
        public ReturnValueNode(ProgramLocation m) { super(m); }
        private ReturnValueNode(ReturnValueNode that) { super(that); }
        
        public AndersenReference getDeclaredType() {
            return (AndersenReference) m.getTargetMethod().and_getReturnType();
        }
        
        public final Node copy() { return new ReturnValueNode(this); }
        
        public String toString_long() { return toString_short()+super.toString_long(); }
        public String toString_short() { return Integer.toHexString(this.hashCode())+": "+"Return value of "+m; }
        
        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("ReturnValue ");
            writeLocation(out, m);
        }
    }
    
    /*
    public static final class CaughtExceptionNode extends OutsideNode {
        final ExceptionHandler eh;
        Set caughtExceptions;
        public CaughtExceptionNode(ExceptionHandler eh) { this.eh = eh; }
        private CaughtExceptionNode(CaughtExceptionNode that) {
            super(that);
            this.eh = that.eh; this.caughtExceptions = that.caughtExceptions;
        }
        
        public void addCaughtException(ThrownExceptionNode n) {
            if (caughtExceptions == null) caughtExceptions = NodeSet.FACTORY.makeSet();
            caughtExceptions.add(n);
        }
        
        public final Node copy() {
            return new CaughtExceptionNode(this);
        }
        
        public AndersenReference getDeclaredType() { return (AndersenReference)eh.getExceptionType(); }
        
        public String toString_long() { return toString_short()+super.toString_long(); }
        public String toString_short() { return Strings.hex(this)+": "+"Caught exception: "+eh; }
    }
    */
    
    /** A ThrownExceptionNode represents the thrown exception of a method call.
     */
    public static final class ThrownExceptionNode extends ReturnedNode {
        public ThrownExceptionNode(ProgramLocation m) { super(m); }
        private ThrownExceptionNode(ThrownExceptionNode that) { super(that); }
        
        public AndersenReference getDeclaredType() { return Bootstrap.PrimordialClassLoader.getJavaLangObject(); }
        
        public final Node copy() { return new ThrownExceptionNode(this); }
        
        public String toString_long() { return toString_short()+super.toString_long(); }
        public String toString_short() { return Integer.toHexString(this.hashCode())+": "+"Thrown exception of "+m; }
        
        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#print(Compil3r.Quad.MethodSummary, java.io.PrintWriter)
         */
        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("ThrownException ");
            writeLocation(out, m);
        }
    }
    
    /** A ParamNode represents an incoming parameter.
     */
    public static final class ParamNode extends OutsideNode {
        final AndersenMethod m; final int n; final AndersenReference declaredType;
        
        public ParamNode(AndersenMethod m, int n, AndersenReference declaredType) { this.m = m; this.n = n; this.declaredType = declaredType; }
        private ParamNode(ParamNode that) {
            this.m = that.m; this.n = that.n; this.declaredType = that.declaredType;
        }
        public AndersenReference getDeclaredType() { return declaredType; }
        
        public final Node copy() { return new ParamNode(this); }
        
        public String toString_long() { return Integer.toHexString(this.hashCode())+": "+this.toString_short()+super.toString_long(); }
        public String toString_short() { return "Param#"+n+" method "+m.getName(); }
        
        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#print(Compil3r.Quad.MethodSummary, java.io.PrintWriter)
         */
        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("Param ");
            writeMember(out, (jq_Method) m);
            out.writeBytes(" "+n);
        }
    }
    
    /** A FieldNode represents the result of a 'load' instruction.
     *  There are outside edge links from the nodes that can be the base object
     *  of the load to this node.
     *  Two nodes are equal if the fields match and they are from the same quad.
     */
    public static final class FieldNode extends OutsideNode {
        final AndersenField f; final Set quads;
        Set field_predecessors;
        
        private static FieldNode findPredecessor(FieldNode base, Quad obj) {
            if (TRACE_INTRA) out.println("Checking "+base+" for predecessor "+obj.getID());
            if (base.quads.contains(obj)) {
                if (TRACE_INTRA) out.println("Success!");
                return base;
            }
            if (base.visited) {
                if (TRACE_INTRA) out.println(base+" already visited");
                return null;
            }
            base.visited = true;
            if (base.field_predecessors != null) {
                for (Iterator i=base.field_predecessors.iterator(); i.hasNext(); ) {
                    Object o = i.next();
                    if (o instanceof FieldNode) {
                        FieldNode fn = (FieldNode)o;
                        FieldNode fn2 = findPredecessor(fn, obj);
                        if (fn2 != null) {
                            base.visited = false;
                            return fn2;
                        }
                    }
                }
            }
            base.visited = false;
            return null;
        }
        
        public static FieldNode get(Node base, AndersenField f, Quad obj) {
            if (TRACE_INTRA) out.println("Getting field node for "+base+(f==null?"[]":("."+f.getName()))+" quad "+(obj==null?-1:obj.getID()));
            Set s = null;
            if (base.accessPathEdges != null) {
                Object o = base.accessPathEdges.get(f);
                if (o instanceof FieldNode) {
                    if (TRACE_INTRA) out.println("Field node for "+base+" already exists, reusing: "+o);
                    return (FieldNode)o;
                } else if (o != null) {
                    s = (Set)o;
                    if (!s.isEmpty()) {
                        if (TRACE_INTRA) out.println("Field node for "+base+" already exists, reusing: "+o);
                        return (FieldNode)s.iterator().next();
                    }
                }
            } else {
                base.accessPathEdges = new LinkedHashMap();
            }
            FieldNode fn;
            if (base instanceof FieldNode) fn = findPredecessor((FieldNode)base, obj);
            else fn = null;
            if (fn == null) {
                fn = new FieldNode(f, obj);
                if (TRACE_INTRA) out.println("Created field node: "+fn.toString_long());
            } else {
                if (TRACE_INTRA) out.println("Using existing field node: "+fn.toString_long());
            }
            if (fn.field_predecessors == null) fn.field_predecessors = NodeSet.FACTORY.makeSet();
            fn.field_predecessors.add(base);
            if (s != null) {
                if (VERIFY_ASSERTIONS) Assert._assert(base.accessPathEdges.get(f) == s);
                s.add(fn);
            } else {
                base.accessPathEdges.put(f, fn);
            }
            if (TRACE_INTRA) out.println("Final field node: "+fn.toString_long());
            return fn;
        }
        
        private FieldNode(AndersenField f, Quad q) { this.f = f; this.quads = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE); this.quads.add(q); }
        private FieldNode(AndersenField f) { this.f = f; this.quads = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE); }

        private FieldNode(FieldNode that) {
            this.f = that.f;
            this.quads = SortedArraySet.FACTORY.makeSet(that.quads);
            this.field_predecessors = that.field_predecessors;
        }

        /** Returns a new FieldNode that is the unification of the given set of FieldNodes.
         *  In essence, all of the given nodes are replaced by a new, returned node.
         *  The given field nodes must be on the given field.
         */
        public static FieldNode unify(jq_Field f, Set s) {
            if (TRACE_INTRA) out.println("Unifying the set of field nodes: "+s);
            FieldNode dis = new FieldNode(f);
            // go through once to add all quads, so that the hash code will be stable.
            for (Iterator i=s.iterator(); i.hasNext(); ) {
                FieldNode dat = (FieldNode)i.next();
                Assert._assert(f == dat.f);
                dis.quads.addAll(dat.quads);
            }
            // once again to do the replacement.
            for (Iterator i=s.iterator(); i.hasNext(); ) {
                FieldNode dat = (FieldNode)i.next();
                Set s2 = Collections.singleton(dis);
                dat.replaceBy(s2, true);
            }
            if (TRACE_INTRA) out.println("Resulting field node: "+dis.toString_long());
            return dis;
        }
        
        public void replaceBy(Set set, boolean removeSelf) {
            if (TRACE_INTRA) out.println("Replacing "+this+" with "+set+(removeSelf?", and removing self":""));
            if (set.contains(this)) {
                if (TRACE_INTRA) out.println("Replacing a node with itself, turning off remove self.");
                set.remove(this);
                if (set.isEmpty()) {
                    if (TRACE_INTRA) out.println("Replacing a node with only itself! Nothing to do.");
                    return;
                }
                removeSelf = false;
            }
            if (VERIFY_ASSERTIONS) Assert._assert(!set.contains(this));
            if (this.field_predecessors != null) {
                for (Iterator i=this.field_predecessors.iterator(); i.hasNext(); ) {
                    Node that = (Node)i.next();
                    Assert._assert(that != null);
                    if (removeSelf) {
                        i.remove();
                        that._removeAccessPathEdge(f, this);
                    }
                    if (that == this) {
                        // add self-cycles on f to all nodes in set.
                        if (TRACE_INTRA) out.println("Found self-cycle on outside edge of "+that);
                        for (Iterator j=set.iterator(); j.hasNext(); ) {
                            FieldNode k = (FieldNode)j.next();
                            k.addAccessPathEdge(f, k);
                        }
                    } else {
                        for (Iterator j=set.iterator(); j.hasNext(); ) {
                            that.addAccessPathEdge(f, (FieldNode)j.next());
                        }
                    }
                }
            }
            super.replaceBy(set, removeSelf);
        }
        
        static void addGlobalAccessPathEdges(FieldNode n) {
            if (n.field_predecessors == null) return;
            AndersenField f = n.f;
            for (Iterator i=n.field_predecessors.iterator(); i.hasNext(); ) {
                Object o = i.next();
                if (o == GlobalNode.GLOBAL) {
                    GlobalNode.GLOBAL.addAccessPathEdge(f, n);
                } else if (o instanceof UnknownTypeNode) {
                    ((UnknownTypeNode)o).addAccessPathEdge(f, n);
                }
            }
        }
        
        public void update(HashMap um) {
            super.update(um);
            Set m = this.field_predecessors;
            if (m != null) {
                this.field_predecessors = NodeSet.FACTORY.makeSet();
                for (Iterator j=m.iterator(); j.hasNext(); ) {
                    Object p = j.next();
                    Assert._assert(p != null);
                    Object o = um.get(p);
                    if (p instanceof UnknownTypeNode) o = p;
                    if (p == GlobalNode.GLOBAL) o = p;
                    if (VERIFY_ASSERTIONS) Assert._assert(o != null, ((Node)p).toString_long()+" (field predecessor of "+this.toString_long()+")");
                    this.field_predecessors.add(o);
                }
                addGlobalAccessPathEdges(this);
            }
        }
        
        /** Return the set of outside edge predecessors of this node. */
        public Set getAccessPathPredecessors() {
            if (field_predecessors == null) return Collections.EMPTY_SET;
            return field_predecessors;
        }
        
        public String fieldName() {
            if (f != null) return f.getName().toString();
            return getDeclaredType()+"[]";
        }
        
        public final Node copy() {
            return new FieldNode(this);
        }
        
        public AndersenReference getDeclaredType() {
            if (f != null) {
                return (AndersenReference)f.and_getType();
            }
            if (quads.isEmpty()) return PrimordialClassLoader.getJavaLangObject();
            RegisterOperand r = ALoad.getDest((Quad)quads.iterator().next());
            return (AndersenReference)r.getType();
        }
        
        public String toString_long() {
            StringBuffer sb = new StringBuffer();
            //sb.append(Strings.hex(this));
            //sb.append(": ");
            sb.append(this.toString_short());
            sb.append(super.toString_long());
            if (field_predecessors != null) {
                sb.append(" field pred:");
                sb.append(field_predecessors);
            }
            return sb.toString();
        }
        public String toString_short() {
            StringBuffer sb = new StringBuffer();
            sb.append(Integer.toHexString(this.hashCode()));
            sb.append(": ");
            sb.append("FieldLoad ");
            sb.append(fieldName());
            Iterator i=quads.iterator();
            if (i.hasNext()) {
                int id = ((Quad)i.next()).getID();
                if (!i.hasNext()) {
                    sb.append(" quad ");
                    sb.append(id);
                } else {
                    sb.append(" quads {");
                    sb.append(id);
                    while (i.hasNext()) {
                        sb.append(',');
                        sb.append(((Quad)i.next()).getID());
                    }
                    sb.append('}');
                }
            }
            return sb.toString();
        }

        /* (non-Javadoc)
         * @see Compil3r.Quad.MethodSummary.Node#print(Compil3r.Quad.MethodSummary, java.io.PrintWriter)
         */
        public void write(MethodSummary ms, DataOutput out) throws IOException {
            out.writeBytes("Field ");
            writeMember(out, (jq_Field) f);
            // TODO: predecessors 
        }
    }
    
    /** Records the state of the intramethod analysis at some point in the method. */
    public static final class State implements Cloneable {
        final Object[] registers;
        /** Return a new state with the given number of registers. */
        public State(int nRegisters) {
            this.registers = new Object[nRegisters];
        }
        /** Return a shallow copy of this state.
         *  Sets of nodes are copied, but the individual nodes are not. */
        public State copy() {
            State that = new State(this.registers.length);
            for (int i=0; i<this.registers.length; ++i) {
                Object a = this.registers[i];
                if (a == null) continue;
                if (a instanceof Node)
                    that.registers[i] = a;
                else
                    that.registers[i] = NodeSet.FACTORY.makeSet((Set)a);
            }
            return that;
        }
        /** Merge two states.  Mutates this state, the other is unchanged. */
        public boolean merge(State that) {
            boolean change = false;
            for (int i=0; i<this.registers.length; ++i) {
                if (merge(i, that.registers[i])) change = true;
            }
            return change;
        }
        /** Merge the given node or set of nodes into the given register. */
        public boolean merge(int i, Object b) {
            if (b == null) return false;
            Object a = this.registers[i];
            if (b.equals(a)) return false;
            Set q;
            if (!(a instanceof Set)) {
                this.registers[i] = q = NodeSet.FACTORY.makeSet();
                if (a != null) q.add(a);
            } else {
                q = (Set)a;
            }
            if (b instanceof Set) {
                if (q.addAll((Set)b)) {
                    if (TRACE_INTRA) out.println("change in register "+i+" from adding set");
                    return true;
                }
            } else {
                if (q.add(b)) {
                    if (TRACE_INTRA) out.println("change in register "+i+" from adding "+b);
                    return true;
                }
            }
            return false;
        }
        /** Dump a textual representation of the state to the given print stream. */
        public void dump(java.io.PrintStream out) {
            for (int i=0; i<registers.length; ++i) {
                if (registers[i] == null) continue;
                out.print(i+": "+registers[i]+" ");
            }
            out.println();
        }
    }
    
    public static final class NodeSet implements Set, Cloneable {
    
        private Node elementData[];
        private int size;
        
        public NodeSet(int initialCapacity) {
            super();
            if (initialCapacity < 0)
                throw new IllegalArgumentException("Illegal Capacity: "+initialCapacity);
            this.elementData = new Node[initialCapacity];
            this.size = 0;
        }
        
        public NodeSet() {
            this(10);
        }
        
        public NodeSet(Collection c) {
            this((int) Math.min((c.size()*110L)/100, Integer.MAX_VALUE));
            this.addAll(c);
        }
        
        public int size() {
            //jq.Assert(new LinkedHashSet(this).size() == this.size);
            return this.size;
        }
        
        private static final int compare(Node n1, Node n2) {
            int n1i = n1.id, n2i = n2.id;
            if (n1i > n2i) return 1;
            if (n1i < n2i) return -1;
            return 0;
        }
        
        private int whereDoesItGo(Node o) {
            int lo = 0;
            int hi = this.size-1;
            if (hi < 0)
                return 0;
            int mid = hi >> 1;
            for (;;) {
                Node o2 = this.elementData[mid];
                int r = compare(o, o2);
                if (r < 0) {
                    hi = mid - 1;
                    if (lo > hi) return mid;
                } else if (r > 0) {
                    lo = mid + 1;
                    if (lo > hi) return lo;
                } else {
                    return mid;
                }
                mid = ((hi - lo) >> 1) + lo;
            }
        }
        
        public boolean add(Object arg0) { return this.add((Node)arg0); }
        public boolean add(Node arg0) {
            int i = whereDoesItGo(arg0);
            int s = this.size;
            if (i != s && elementData[i].equals(arg0)) {
                return false;
            }
            ensureCapacity(s+1);
            System.arraycopy(this.elementData, i, this.elementData, i + 1, s - i);
            elementData[i] = arg0;
            this.size++;
            return true;
        }
        
        public void ensureCapacity(int minCapacity) {
            int oldCapacity = elementData.length;
            if (minCapacity > oldCapacity) {
                Object oldData[] = elementData;
                int newCapacity = ((oldCapacity * 3) >> 1) + 1;
                if (newCapacity < minCapacity)
                    newCapacity = minCapacity;
                this.elementData = new Node[newCapacity];
                System.arraycopy(oldData, 0, this.elementData, 0, this.size);
            }
        }
        
        // Set this to true if allocations are more expensive than arraycopy.
        public static final boolean REDUCE_ALLOCATIONS = false;
    
        public boolean addAll(java.util.Collection that) {
            if (that instanceof NodeSet) {
                boolean result = addAll((NodeSet) that);
                return result;
            } else {
                boolean change = false;
                for (Iterator i=that.iterator(); i.hasNext(); )
                    if (this.add((Node)i.next())) change = true;
                return change;
            }
        }
    
        public boolean addAll(NodeSet that) {
            if (this == that) {
                return false;
            }
            int s2 = that.size();
            if (s2 == 0) {
                return false;
            }
            int s1 = this.size;
            Node[] e1 = this.elementData, e2 = that.elementData;
            int newSize = Math.max(e1.length, s1 + s2);
            int i1, new_i1=0, i2=0;
            Node[] new_e1;
            if (REDUCE_ALLOCATIONS && newSize <= e1.length) {
                System.arraycopy(e1, 0, e1, s2, s1);
                new_e1 = e1;
                i1 = s2; s1 += s2;
            } else {
                new_e1 = new Node[newSize];
                this.elementData = new_e1;
                i1 = 0;
            }
            boolean change = false;
            for (;;) {
                if (i2 == s2) {
                    int size2 = s1-i1;
                    if (size2 > 0)
                        System.arraycopy(e1, i1, new_e1, new_i1, size2);
                    this.size = new_i1 + size2;
                    return change;
                }
                Node o2 = e2[i2++];
                for (;;) {
                    if (i1 == s1) {
                        new_e1[new_i1++] = o2;
                        int size2 = s2-i2;
                        System.arraycopy(e2, i2, new_e1, new_i1, size2);
                        this.size = new_i1 + size2;
                        return true;
                    }
                    Node o1 = e1[i1];
                    int r = compare(o1, o2);
                    if (r <= 0) {
                        new_e1[new_i1++] = o1;
                        if (REDUCE_ALLOCATIONS && new_e1 == e1) e1[i1] = null;
                        i1++;
                        if (r == 0) break;
                    } else {
                        new_e1[new_i1++] = o2;
                        change = true;
                        break;
                    }
                }
            }
        }
        public int indexOf(Node arg0) {
            int i = whereDoesItGo(arg0);
            if (i == size || arg0.id != elementData[i].id) return -1;
            return i;
        }
        public boolean contains(Object arg0) { return contains((Node)arg0); }
        public boolean contains(Node arg0) {
            boolean result = this.indexOf(arg0) != -1;
            return result;
        }
        public boolean remove(Object arg0) { return remove((Node)arg0); }
        public boolean remove(Node arg0) {
            int i = whereDoesItGo(arg0);
            if (i == size) {
                return false;
            }
            Object oldValue = elementData[i];
            if (arg0 != oldValue) {
                return false;
            }
            int numMoved = this.size - i - 1;
            if (numMoved > 0)
                System.arraycopy(elementData, i+1, elementData, i, numMoved);
            elementData[--this.size] = null; // for gc
            return true;
        }
        public Object clone() {
            try {
                NodeSet s = (NodeSet) super.clone();
                int initialCapacity = this.elementData.length;
                s.elementData = new Node[initialCapacity];
                s.size = this.size;
                System.arraycopy(this.elementData, 0, s.elementData, 0, this.size);
                return s;
            } catch (CloneNotSupportedException _) { return null; }
        }
        
        public boolean equals(Object o) {
            if (o instanceof NodeSet) {
                boolean result = equals((NodeSet)o);
                return result;
            } else if (o instanceof Collection) {
                Collection that = (Collection) o;
                if (this.size() != that.size()) return false;
                for (Iterator i=that.iterator(); i.hasNext(); ) {
                    if (!this.contains(i.next())) return false;
                }
                return true;
            } else {
                return false;
            }
        }
        public boolean equals(NodeSet that) {
            if (this.size != that.size) {
                return false;
            }
            Node[] e1 = this.elementData; Node[] e2 = that.elementData;
            for (int i=0; i<this.size; ++i) {
                if (e1[i] != e2[i]) {
                    return false;
                }
            }
            return true;
        }
        
        public int hashCode() {
            int hash = 0;
            for (int i=0; i<this.size; ++i) {
                if (USE_IDENTITY_HASHCODE)
                    hash += System.identityHashCode(this.elementData[i]);
                else
                    hash += this.elementData[i].hashCode();
            }
            return hash;
        }
            
        public String toString() {
            StringBuffer sb = new StringBuffer();
            if (false) {
                sb.append(Integer.toHexString(System.identityHashCode(this)));
                sb.append(':');
            }
            sb.append('{');
            for (int i=0; i<size; ++i) {
                sb.append(elementData[i]);
                if (i+1 < size) sb.append(',');
            }
            sb.append('}');
            return sb.toString();
        }
        
        public void clear() { this.size = 0; }
        public boolean containsAll(Collection arg0) {
            if (arg0 instanceof NodeSet) return containsAll((NodeSet)arg0);
            else {
                for (Iterator i=arg0.iterator(); i.hasNext(); )
                    if (!this.contains((Node)i.next())) return false;
                return true;
            }
        }
        public boolean containsAll(NodeSet that) {
            if (this == that) {
                return true;
            }
            int s1 = this.size;
            int s2 = that.size;
            if (s2 > s1) {
                return false;
            }
            Node[] e1 = this.elementData, e2 = that.elementData;
            for (int i1 = 0, i2 = 0; i2 < s2; ++i2) {
                Node o2 = e2[i2];
                for (;;) {
                    Node o1 = e1[i1++];
                    if (o1 == o2) break;
                    if (o1.id > o2.id) {
                        return false;
                    }
                }
            }
            return true;
        }
        public boolean isEmpty() { return this.size == 0; }
        public Iterator iterator() {
            final Node[] e = this.elementData;
            final int s = this.size;
            return new Iterator() {
                int n = s;
                int i = 0;
                public Object next() {
                    return e[i++];
                }
                public boolean hasNext() {
                    return i < n;
                }
                public void remove() {
                    int numMoved = s - i;
                    if (numMoved > 0)
                        System.arraycopy(e, i, e, i-1, numMoved);
                    elementData[--size] = null; // for gc
                    --i; --n;
                }
            };
        }
        public boolean removeAll(Collection arg0) {
            if (arg0 instanceof NodeSet)
                return removeAll((NodeSet)arg0);
            else {
                boolean change = false;
                for (Iterator i=arg0.iterator(); i.hasNext(); )
                    if (this.remove((Node)i.next())) change = true;
                return change;
            }
        }
        public boolean removeAll(NodeSet that) {
            if (this.isEmpty()) return false;
            if (this == that) {
                this.clear(); return true;
            }
            int s1 = this.size;
            int s2 = that.size;
            Node[] e1 = this.elementData, e2 = that.elementData;
            int i1 = 0, i2 = 0, i3 = 0;
            Node o1 = e1[i1++];
            Node o2 = e2[i2++];
outer:
            for (;;) {
                while (o1.id < o2.id) {
                    e1[i3++] = o1;
                    if (i1 == s1) break outer;
                    o1 = e1[i1++];
                }
                while (o1.id > o2.id) {
                    if (i2 == s2) break outer;
                    o2 = e2[i2++];
                }
                while (o1 == o2) {
                    if (i1 == s1) break outer;
                    o1 = e1[i1++];
                    if (i2 == s2) {
                        System.arraycopy(e1, i1, e1, i3, s1-i1);
                        i3 += s1-i1;
                        break outer;
                    }
                    o2 = e2[i2++];
                }
            }
            this.size = i3;
            return true;
        }
        
        public boolean retainAll(Collection arg0) {
            if (arg0 instanceof NodeSet)
                return retainAll((NodeSet)arg0);
            else {
                boolean change = false;
                for (Iterator i=this.iterator(); i.hasNext(); )
                    if (!arg0.contains(i.next())) {
                        i.remove();
                        change = true;
                    }
                return change;
            }
        }
        public boolean retainAll(NodeSet that) {
            if (this == that) return false;
            int s1 = this.size;
            int s2 = that.size;
            Node[] e1 = this.elementData, e2 = that.elementData;
            int i1 = 0, i2 = 0, i3 = 0;
            Node o1 = e1[i1++];
            Node o2 = e2[i2++];
outer:
            for (;;) {
                while (o1.id < o2.id) {
                    if (i1 == s1) break outer;
                    o1 = e1[i1++];
                }
                while (o1.id > o2.id) {
                    if (i2 == s2) break outer;
                    o2 = e2[i2++];
                }
                while (o1 == o2) {
                    e1[i3++] = o1;
                    if (i1 == s1) break outer;
                    o1 = e1[i1++];
                    if (i2 == s2) break outer;
                    o2 = e2[i2++];
                }
            }
            this.size = i3;
            return true;
        }
        public Object[] toArray() {
            Node[] n = new Node[this.size];
            System.arraycopy(this.elementData, 0, n, 0, this.size);
            return n;
        }
        public Object[] toArray(Object[] arg0) {
            return this.toArray();
        }
    
        public static final boolean TEST = false;
        public static final boolean PROFILE = false;
    
        public static final SetFactory FACTORY = new SetFactory() {
            public final Set makeSet(Collection c) {
                if (TEST)
                    return new CollectionTestWrapper(new LinkedHashSet(c), new NodeSet(c));
                if (PROFILE)
                    return new InstrumentedSetWrapper(new NodeSet(c));
                return new NodeSet(c);
            }
        };
    
    }
    
    
    /** Encodes an access path.
     *  An access path is an NFA, where transitions are field names.
     *  Each node in the NFA is represented by an AccessPath object.
     *  We try to share AccessPath objects as much as possible.
     */
    public static class AccessPath {
        /** All incoming transitions have this field. */
        AndersenField _field;
        /** The incoming transitions are associated with this AccessPath object. */
        Node _n;
        /** Whether this is a valid end state. */
        boolean _last;
        
        /** The set of (wrapped) successor AccessPath objects. */
        Set succ;

        /** Adds the set of (wrapped) AccessPath objects that are reachable from this
         *  AccessPath object to the given set. */
        private void reachable(Set s) {
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper ap = (IdentityHashCodeWrapper)i.next();
                if (!s.contains(ap)) {
                    s.add(ap);
                    ((AccessPath)ap.getObject()).reachable(s);
                }
            }
        }
        /** Return an iteration of the AccessPath objects that are reachable from
         *  this AccessPath. */
        public Iterator reachable() {
            Set s = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
            s.add(IdentityHashCodeWrapper.create(this));
            this.reachable(s);
            return new FilterIterator(s.iterator(), filter);
        }
        
        /** Add the given AccessPath object as a successor to this AccessPath object. */
        private void addSuccessor(AccessPath ap) {
            succ.add(IdentityHashCodeWrapper.create(ap));
        }
        
        /** Return an access path that is equivalent to the given access path prepended
         *  with a transition on the given field and node.  The given access path can
         *  be null (empty). */
        public static AccessPath create(AndersenField f, Node n, AccessPath p) {
            if (p == null) return new AccessPath(f, n, true);
            AccessPath that = p.findNode(n);
            if (that == null) {
                that = new AccessPath(f, n);
            } else {
                p = p.copy();
                that = p.findNode(n);
            }
            that.addSuccessor(p);
            return that;
        }
        
        /** Return an access path that is equivalent to the given access path appended
         *  with a transition on the given field and node.  The given access path can
         *  be null (empty). */
        public static AccessPath create(AccessPath p, AndersenField f, Node n) {
            if (p == null) return new AccessPath(f, n, true);
            p = p.copy();
            AccessPath that = p.findNode(n);
            if (that == null) {
                that = new AccessPath(f, n);
            }
            that.setLast();
            for (Iterator i = p.findLast(); i.hasNext(); ) {
                AccessPath last = (AccessPath)i.next();
                last.unsetLast();
                last.addSuccessor(that);
            }
            return p;
        }
        
        /** Helper function for findLast(), below. */
        private void findLast(HashSet s, Set last) {
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper ap = (IdentityHashCodeWrapper)i.next();
                if (!s.contains(ap)) {
                    s.add(ap);
                    AccessPath that = (AccessPath)ap.getObject();
                    if (that._last) last.add(ap);
                    that.findLast(s, last);
                }
            }
        }
        
        /** Return an iteration of the AccessPath nodes that correspond to end states. */
        public Iterator findLast() {
            HashSet visited = new HashSet();
            Set last = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
            IdentityHashCodeWrapper ap = IdentityHashCodeWrapper.create(this);
            visited.add(ap);
            if (this._last) last.add(ap);
            this.findLast(visited, last);
            return new FilterIterator(last.iterator(), filter);
        }
        
        /** Helper function for findNode(Node n), below. */
        private AccessPath findNode(Node n, HashSet s) {
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper ap = (IdentityHashCodeWrapper)i.next();
                if (!s.contains(ap)) {
                    AccessPath p = (AccessPath)ap.getObject();
                    if (n == p._n) return p;
                    s.add(ap);
                    AccessPath q = p.findNode(n, s);
                    if (q != null) return q;
                }
            }
            return null;
        }
        
        /** Find the AccessPath object that corresponds to the given node. */
        public AccessPath findNode(Node n) {
            if (n == this._n) return this;
            HashSet visited = new HashSet();
            IdentityHashCodeWrapper ap = IdentityHashCodeWrapper.create(this);
            visited.add(ap);
            return findNode(n, visited);
        }
        
        /** Set this transition as a valid end transition. */
        private void setLast() { this._last = true; }
        /** Unset this transition as a valid end transition. */
        private void unsetLast() { this._last = false; }
        
        /** Helper function for copy(), below. */
        private void copy(HashMap m, AccessPath that) {
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper ap = (IdentityHashCodeWrapper)i.next();
                AccessPath p = (AccessPath)m.get(ap);
                if (p == null) {
                    AccessPath that2 = (AccessPath)ap.getObject();
                    p = new AccessPath(that2._field, that2._n, that2._last);
                    m.put(ap, p);
                    that2.copy(m, p);
                }
                that.addSuccessor(p);
            }
        }

        /** Return a copy of this (complete) access path. */
        public AccessPath copy() {
            HashMap m = new HashMap();
            IdentityHashCodeWrapper ap = IdentityHashCodeWrapper.create(this);
            AccessPath p = new AccessPath(this._field, this._n, this._last);
            m.put(ap, p);
            this.copy(m, p);
            return p;
        }
        
        /** Helper function for toString(), below. */
        private void toString(StringBuffer sb, HashSet set) {
            if (this._field == null) sb.append("[]");
            else sb.append(this._field.getName());
            if (this._last) sb.append("<e>");
            sb.append("->(");
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper ap = (IdentityHashCodeWrapper)i.next();
                if (set.contains(ap)) {
                    sb.append("<backedge>");
                } else {
                    set.add(ap);
                    ((AccessPath)ap.getObject()).toString(sb, set);
                }
            }
            sb.append(')');
        }
        /** Returns a string representation of this (complete) access path. */
        public String toString() {
            StringBuffer sb = new StringBuffer();
            HashSet visited = new HashSet();
            IdentityHashCodeWrapper ap = IdentityHashCodeWrapper.create(this);
            visited.add(ap);
            toString(sb, visited);
            return sb.toString();
        }
        
        /** Private constructor.  Use the create() methods above. */
        private AccessPath(AndersenField f, Node n, boolean last) {
            this._field = f; this._n = n; this._last = last;
            this.succ = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
        }
        /** Private constructor.  Use the create() methods above. */
        private AccessPath(AndersenField f, Node n) {
            this(f, n, false);
        }
        
        /** Helper function for equals(AccessPath), below. */
        private boolean oneEquals(AccessPath that) {
            //if (this._n != that._n) return false;
            if (this._field != that._field) return false;
            if (this._last != that._last) return false;
            if (this.succ.size() != that.succ.size()) return false;
            return true;
        }
        /** Helper function for equals(AccessPath), below. */
        private boolean equals(AccessPath that, HashSet s) {
            // Relies on the fact that the iterators are stable for equivalent sets.
            // Otherwise, it is an n^2 algorithm.
            for (Iterator i = this.succ.iterator(), j = that.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper a = (IdentityHashCodeWrapper)i.next();
                IdentityHashCodeWrapper b = (IdentityHashCodeWrapper)j.next();
                AccessPath p = (AccessPath)a.getObject();
                AccessPath q = (AccessPath)b.getObject();
                if (!p.oneEquals(q)) return false;
                if (s.contains(a)) continue;
                s.add(a);
                if (!p.equals(q, s)) return false;
            }
            return true;
        }
        /** Returns true if this access path is equal to the given access path. */
        public boolean equals(AccessPath that) {
            HashSet s = new HashSet();
            if (!oneEquals(that)) return false;
            s.add(IdentityHashCodeWrapper.create(this));
            return this.equals(that, s);
        }
        public boolean equals(Object o) {
            if (o instanceof AccessPath) return equals((AccessPath)o);
            return false;
        }
        /** Returns the hashcode for this access path. */
        public int hashCode() {
            int x = this.local_hashCode();
            for (Iterator i = this.succ.iterator(); i.hasNext(); ) {
                IdentityHashCodeWrapper a = (IdentityHashCodeWrapper)i.next();
                x ^= (((AccessPath)a.getObject()).local_hashCode() << 1);
            }
            return x;
        }
        /** Returns the hashcode for this individual AccessPath object. */
        private int local_hashCode() {
            return _field != null ? _field.hashCode() : 0x31337;
        }
        /** Returns the first field of this access path. */
        public AndersenField first() { return _field; }
        /** Returns an iteration of the next AccessPath objects. */
        public Iterator next() {
            return new FilterIterator(succ.iterator(), filter);
        }
        /** A filter to unwrap objects from their IdentityHashCodeWrapper. */
        public static final FilterIterator.Filter filter = new FilterIterator.Filter() {
            public Object map(Object o) { return ((IdentityHashCodeWrapper)o).getObject(); }
        };
    }
    
    /** vvvvv   Actual MethodSummary stuff is below.   vvvvv */
    
    /** The method that this is a summary for. */
    final AndersenMethod method;
    /** The parameter nodes. */
    final ParamNode[] params;
    /** All nodes in the summary graph. */
    final Map nodes;
    /** The returned nodes. */
    final Set returned;
    /** The thrown nodes. */
    final Set thrown;
    /** The global node. */
    /*final*/ GlobalNode global;
    /** The method calls that this method makes. */
    final Set calls;
    /** Map from a method call that this method makes, and its ReturnValueNode. */
    final Map callToRVN;
    /** Map from a method call that this method makes, and its ThrownExceptionNode. */
    final Map callToTEN;
    
    public static final boolean USE_PARAMETER_MAP = true;
    final Map passedParamToNodes;

    MethodSummary(ParamNode[] param_nodes) {
        this.method = null;
        this.params = param_nodes;
        this.calls = Collections.EMPTY_SET;
        this.callToRVN = Collections.EMPTY_MAP;
        this.callToTEN = Collections.EMPTY_MAP;
        this.nodes = Collections.EMPTY_MAP;
        this.returned = Collections.EMPTY_SET;
        this.thrown = Collections.EMPTY_SET;
        this.passedParamToNodes = Collections.EMPTY_MAP;
    }

    public MethodSummary(AndersenMethod method, ParamNode[] param_nodes, GlobalNode my_global, Set methodCalls, HashMap callToRVN, HashMap callToTEN, Set returned, Set thrown, Set passedAsParameters) {
        this.method = method;
        this.params = param_nodes;
        this.calls = methodCalls;
        this.callToRVN = callToRVN;
        this.callToTEN = callToTEN;
        this.passedParamToNodes = USE_PARAMETER_MAP?new HashMap():null;
        this.returned = returned;
        this.thrown = thrown;
        this.global = my_global;
        this.nodes = new LinkedHashMap();
        
        // build useful node set
        this.nodes.put(my_global, my_global);
        for (int i=0; i<params.length; ++i) {
            if (params[i] == null) continue;
            this.nodes.put(params[i], params[i]);
        }
        for (Iterator i=returned.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) continue;
            this.nodes.put(n, n);
        }
        for (Iterator i=thrown.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) continue;
            this.nodes.put(n, n);
        }
        for (Iterator i=passedAsParameters.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) continue;
            this.nodes.put(n, n);
            if (USE_PARAMETER_MAP) {
                if (n.passedParameters != null) {
                    for (Iterator j=n.passedParameters.iterator(); j.hasNext(); ) {
                        PassedParameter pp = (PassedParameter)j.next();
                        Set s2 = (Set)this.passedParamToNodes.get(pp);
                        if (s2 == null) this.passedParamToNodes.put(pp, s2 = NodeSet.FACTORY.makeSet());
                        s2.add(n);
                    }
                }
            }
        }
        
        HashSet visited = new HashSet();
        HashSet path = new HashSet();
        addAsUseful(visited, path, my_global);
        for (int i=0; i<params.length; ++i) {
            if (params[i] == null) continue;
            addAsUseful(visited, path, params[i]);
        }
        for (Iterator i=returned.iterator(); i.hasNext(); ) {
            addAsUseful(visited, path, (Node)i.next());
        }
        for (Iterator i=thrown.iterator(); i.hasNext(); ) {
            addAsUseful(visited, path, (Node)i.next());
        }
        for (Iterator i=passedAsParameters.iterator(); i.hasNext(); ) {
            addAsUseful(visited, path, (Node)i.next());
        }
        
        if (UNIFY_ACCESS_PATHS) {
            HashSet roots = new HashSet();
            for (int i=0; i<params.length; ++i) {
                if (params[i] == null) continue;
                roots.add(params[i]);
            }
            roots.addAll(returned); roots.addAll(thrown); roots.addAll(passedAsParameters);
            unifyAccessPaths(roots);
        }
        
        if (VERIFY_ASSERTIONS) {
            this.verify();
            for (Iterator i=returned.iterator(); i.hasNext(); ) {
                Object o = i.next();
                if (o instanceof UnknownTypeNode) continue;
                if (!nodes.containsKey(o)) {
                    Assert.UNREACHABLE("Returned node "+o+" not in set.");
                }
            }
            for (Iterator i=thrown.iterator(); i.hasNext(); ) {
                Object o = i.next();
                if (o instanceof UnknownTypeNode) continue;
                if (!nodes.containsKey(o)) {
                    Assert.UNREACHABLE("Returned node "+o+" not in set.");
                }
            }
            for (Iterator i=nodes.keySet().iterator(); i.hasNext(); ) {
                Node nod = (Node)i.next();
                if (nod.predecessors == null) continue;
                for (Iterator j=nod.predecessors.values().iterator(); j.hasNext(); ) {
                    Object o = j.next();
                    if (o instanceof Node) {
                        if (o instanceof UnknownTypeNode) continue;
                        if (!nodes.containsKey(o)) {
                            Assert.UNREACHABLE("Predecessor node "+o+" of "+nod+" not in set.");
                        }
                    } else {
                        for (Iterator k=((Set)o).iterator(); k.hasNext(); ) {
                            Node q = (Node)k.next();
                            if (q instanceof UnknownTypeNode) continue;
                            if (!nodes.containsKey(q)) {
                                Assert.UNREACHABLE("Predecessor node "+q+" of "+nod+" not in set.");
                            }
                        }
                    }
                }
            }
            //this.copy();
        }
    }

    public static final boolean UNIFY_ACCESS_PATHS = false;
    
    private MethodSummary(AndersenMethod method, ParamNode[] params, Set methodCalls, HashMap callToRVN, HashMap callToTEN, Map passedParamToNodes, Set returned, Set thrown, Map nodes) {
        this.method = method;
        this.params = params;
        this.calls = methodCalls;
        this.callToRVN = callToRVN;
        this.callToTEN = callToTEN;
        this.passedParamToNodes = passedParamToNodes;
        this.returned = returned;
        this.thrown = thrown;
        this.nodes = nodes;
    }

    public GlobalNode getGlobal() { return global; }

    public ParamNode getParamNode(int i) { return params[i]; }
    public int getNumOfParams() { return params.length; }
    public Set getCalls() { return calls; }

    /** Add all nodes that are passed as the given passed parameter to the given result set. */
    public void getNodesThatCall(PassedParameter pp, Set result) {
        if (USE_PARAMETER_MAP) {
            Set s = (Set)passedParamToNodes.get(pp);
            if (s == null) return;
            result.addAll(s);
            return;
        }
        for (Iterator i = this.nodeIterator(); i.hasNext(); ) {
            Node n = (Node)i.next();
            if ((n.passedParameters != null) && n.passedParameters.contains(pp))
                result.add(n);
        }
    }

    public Set getNodesThatCall(PassedParameter pp) {
        if (USE_PARAMETER_MAP) {
            Set s = (Set)passedParamToNodes.get(pp);
            if (s == null) return Collections.EMPTY_SET;
            return s;
        }
        Set s = NodeSet.FACTORY.makeSet();
        getNodesThatCall(pp, s);
        return s;
    }
    
    public void mergeGlobal() {
        if (global == null) return;
        // merge global nodes.
        Set set = Collections.singleton(GlobalNode.GLOBAL);
        global.replaceBy(set, true);
        nodes.remove(global);
        unifyAccessPaths(new LinkedHashSet(set));
        if (VERIFY_ASSERTIONS) {
            verifyNoReferences(global);
        }
        global = null;
    }
        
    /** Utility function to add to a multi map. */
    public static boolean addToMultiMap(HashMap mm, Object from, Object to) {
        Set s = (Set)mm.get(from);
        if (s == null) {
            mm.put(from, s = NodeSet.FACTORY.makeSet());
        }
        return s.add(to);
    }

    /** Utility function to add to a multi map. */
    public static boolean addToMultiMap(HashMap mm, Object from, Set to) {
        Set s = (Set)mm.get(from);
        if (s == null) {
            mm.put(from, s = NodeSet.FACTORY.makeSet());
        }
        return s.addAll(to);
    }

    /** Utility function to get the mapping for a callee node. */
    static Set get_mapping(HashMap callee_to_caller, Node callee_n) {
        Set s = (Set)callee_to_caller.get(callee_n);
        if (s != null) return s;
        s = NodeSet.FACTORY.makeSet(); s.add(callee_n);
        return s;
    }

    /** Return a deep copy of this analysis summary.
     *  Nodes, edges, everything is copied.
     */
    public MethodSummary copy() {
        if (TRACE_INTRA) out.println("Copying summary: "+this);
        if (VERIFY_ASSERTIONS) this.verify();
        HashMap m = new HashMap();
        //m.put(GlobalNode.GLOBAL, GlobalNode.GLOBAL);
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node a = (Node)i.next();
            Node b = a.copy();
            m.put(a, b);
        }
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node a = (Node)i.next();
            Node b = (Node)m.get(a);
            b.update(m);
        }
        Set calls = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
        calls.addAll(this.calls);
        Set returned = NodeSet.FACTORY.makeSet();
        for (Iterator i=this.returned.iterator(); i.hasNext(); ) {
            Node a = (Node)i.next();
            Node b = (Node)m.get(a);
            if (a instanceof UnknownTypeNode) b = a;
            Assert._assert(b != null);
            returned.add(b);
        }
        Set thrown = NodeSet.FACTORY.makeSet();
        for (Iterator i=this.thrown.iterator(); i.hasNext(); ) {
            Node a = (Node)i.next();
            Node b = (Node)m.get(a);
            if (a instanceof UnknownTypeNode) b = a;
            Assert._assert(b != null);
            thrown.add(b);
        }
        ParamNode[] params = new ParamNode[this.params.length];
        for (int i=0; i<params.length; ++i) {
            if (this.params[i] == null) continue;
            params[i] = (ParamNode)m.get(this.params[i]);
        }
        HashMap callToRVN = new HashMap();
        for (Iterator i=this.callToRVN.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry e = (java.util.Map.Entry)i.next();
            ProgramLocation mc = (ProgramLocation) e.getKey();
            Object o = e.getValue();
            if (o instanceof Set) {
                Set s2 = NodeSet.FACTORY.makeSet();
                for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                    Object o2 = m.get(j.next());
                    Assert._assert(o2 != null);
                    s2.add(o2);
                }
                o = s2;
            } else if (o != null) {
                o = m.get(o);
                Assert._assert(o != null, e.toString());
            }
            callToRVN.put(mc, o);
        }
        HashMap callToTEN = new HashMap();
        for (Iterator i=this.callToTEN.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry e = (java.util.Map.Entry)i.next();
            ProgramLocation mc = (ProgramLocation) e.getKey();
            Object o = e.getValue();
            if (o instanceof Set) {
                Set s2 = NodeSet.FACTORY.makeSet();
                for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                    Object o2 = m.get(j.next());
                    Assert._assert(o2 != null);
                    s2.add(o2);
                }
                o = s2;
            } else if (o != null) {
                o = m.get(o);
                Assert._assert(o != null, e.toString());
            }
            callToTEN.put(mc, o);
        }
        LinkedHashMap nodes = new LinkedHashMap();
        for (Iterator i=m.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry e = (java.util.Map.Entry)i.next();
            Assert._assert(e.getValue() != GlobalNode.GLOBAL);
            Assert._assert(!(e.getValue() instanceof UnknownTypeNode));
            nodes.put(e.getValue(), e.getValue());
        }
        Map passedParamToNodes = null;
        if (USE_PARAMETER_MAP) {
            passedParamToNodes = new HashMap(this.passedParamToNodes);
            Node.updateMap(m, passedParamToNodes.entrySet().iterator(), passedParamToNodes);
        }
        MethodSummary that = new MethodSummary(method, params, calls, callToRVN, callToTEN, passedParamToNodes, returned, thrown, nodes);
        if (VERIFY_ASSERTIONS) that.verify();
        return that;
    }

    /** Unify similar access paths from the given roots.
     *  The given set is consumed.
     */
    public void unifyAccessPaths(Set roots) {
        LinkedList worklist = new LinkedList();
        for (Iterator i=roots.iterator(); i.hasNext(); ) {
            worklist.add(i.next());
        }
        while (!worklist.isEmpty()) {
            Node n = (Node)worklist.removeFirst();
            if (n instanceof UnknownTypeNode) continue;
            unifyAccessPathEdges(n);
            if (n.accessPathEdges != null) {
                for (Iterator i=n.accessPathEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    FieldNode n2 = (FieldNode)e.getValue();
                    Assert._assert(n2 != null);
                    if (roots.contains(n2)) continue;
                    worklist.add(n2); roots.add(n2);
                }
            }
            if (n.addedEdges != null) {
                for (Iterator i=n.addedEdges.entrySet().iterator(); i.hasNext(); ) {
                    java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                    Object o = e.getValue();
                    if (o instanceof Node) {
                        Node n2 = (Node)o;
                        Assert._assert(n2 != null);
                        if (roots.contains(n2)) continue;
                        worklist.add(n2); roots.add(n2);
                    } else {
                        Set s = NodeSet.FACTORY.makeSet((Set)o);
                        for (Iterator j=s.iterator(); j.hasNext(); ) {
                            Object p = j.next();
                            Assert._assert(p != null);
                            if (roots.contains(p)) j.remove();
                        }
                        if (!s.isEmpty()) {
                            worklist.addAll(s); roots.addAll(s);
                        }
                    }
                }
            }
        }
    }

    /** Unify similar access path edges from the given node.
     */
    public void unifyAccessPathEdges(Node n) {
        if (n instanceof UnknownTypeNode) return;
        if (TRACE_INTRA) out.println("Unifying access path edges from: "+n);
        if (n.accessPathEdges != null) {
            for (Iterator i=n.accessPathEdges.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                Assert._assert(o != null);
                FieldNode n2;
                if (o instanceof FieldNode) {
                    n2 = (FieldNode)o;
                } else {
                    Set s = (Set)NodeSet.FACTORY.makeSet((Set)o);
                    if (s.size() == 0) {
                        i.remove();
                        continue;
                    }
                    if (s.size() == 1) {
                        n2 = (FieldNode)s.iterator().next();
                        e.setValue(n2);
                        continue;
                    }
                    if (TRACE_INTRA) out.println("Node "+n+" has duplicate access path edges on field "+f+": "+s);
                    n2 = FieldNode.unify(f, s);
                    for (Iterator j=s.iterator(); j.hasNext(); ) {
                        FieldNode n3 = (FieldNode)j.next();
                        if (returned.contains(n3)) {
                            returned.remove(n3); returned.add(n2);
                        }
                        if (thrown.contains(n3)) {
                            thrown.remove(n3); thrown.add(n2);
                        }
                        nodes.remove(n3);
                        if (VERIFY_ASSERTIONS)
                            this.verifyNoReferences(n3);
                    }
                    nodes.put(n2, n2);
                    e.setValue(n2);
                }
            }
        }
    }

    /** Instantiate a copy of the callee summary into the caller. */
    public static void instantiate(MethodSummary caller, ProgramLocation mc, MethodSummary callee, boolean removeCall) {
        callee = callee.copy();
        if (TRACE_INST) out.println("Instantiating "+callee+" into "+caller+", mc="+mc+" remove call="+removeCall);
        if (VERIFY_ASSERTIONS) {
            callee.verify();
            caller.verify();
        }
        Assert._assert(caller.calls.contains(mc));
        HashMap callee_to_caller = new HashMap();
        if (TRACE_INST) out.println("Adding global node to map: "+GlobalNode.GLOBAL.toString_long());
        callee_to_caller.put(GlobalNode.GLOBAL, GlobalNode.GLOBAL);
        if (TRACE_INST) out.println("Initializing map with "+callee.params.length+" parameters");
        // initialize map with parameters.
        for (int i=0; i<callee.params.length; ++i) {
            ParamNode pn = callee.params[i];
            if (pn == null) continue;
            PassedParameter pp = new PassedParameter(mc, i);
            Set s = caller.getNodesThatCall(pp);
            if (TRACE_INST) out.println("Adding param node to map: "+pn.toString_long()+" maps to "+s);
            callee_to_caller.put(pn, s);
            if (removeCall) {
                if (TRACE_INST) out.println("Removing "+pn+" from nodes "+s);
                for (Iterator jj=s.iterator(); jj.hasNext(); ) {
                    Node n = (Node)jj.next();
                    n.passedParameters.remove(pp);
                }
                if (USE_PARAMETER_MAP) caller.passedParamToNodes.remove(pp);
            }
        }
        
        if (TRACE_INST) out.println("Adding all callee calls: "+callee.calls);
        caller.calls.addAll(callee.calls);
        for (Iterator i=callee.callToRVN.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry e = (java.util.Map.Entry) i.next();
            ProgramLocation mc2 = (ProgramLocation) e.getKey();
            if (VERIFY_ASSERTIONS) {
                Assert._assert(caller.calls.contains(mc2));
                Assert._assert(!mc.equals(mc2));
            }
            Object rvn2 = e.getValue();
            if (TRACE_INST) out.println("Adding rvn for callee call: "+rvn2);
            Object o2 = caller.callToRVN.get(mc2);
            if (o2 != null) {
                Set set = NodeSet.FACTORY.makeSet();
                if (o2 instanceof Set) set.addAll((Set) o2);
                else set.add(o2);
                if (rvn2 instanceof Set) set.addAll((Set) rvn2);
                else set.add(rvn2);
                rvn2 = set;
                if (TRACE_INST) out.println("New rvn set: "+rvn2);
            }
            caller.callToRVN.put(mc2, rvn2);
        }
        for (Iterator i=callee.callToTEN.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry e = (java.util.Map.Entry) i.next();
            ProgramLocation mc2 = (ProgramLocation) e.getKey();
            if (VERIFY_ASSERTIONS) {
                Assert._assert(caller.calls.contains(mc2));
                Assert._assert(!mc.equals(mc2));
            }
            Object ten2 = e.getValue();
            if (TRACE_INST) out.println("Adding ten for callee call: "+ten2);
            Object o2 = caller.callToTEN.get(mc2);
            if (o2 != null) {
                Set set = NodeSet.FACTORY.makeSet();
                if (o2 instanceof Set) set.addAll((Set) o2);
                else set.add(o2);
                if (ten2 instanceof Set) set.addAll((Set) ten2);
                else set.add(ten2);
                ten2 = set;
                if (TRACE_INST) out.println("New ten set: "+ten2);
            }
            caller.callToTEN.put(mc2, ten2);
        }
        
        if (TRACE_INST) out.println("Replacing formal parameters with actuals");
        for (int ii=0; ii<callee.params.length; ++ii) {
            ParamNode pn = callee.params[ii];
            if (pn == null) continue;
            Set s = (Set)callee_to_caller.get(pn);
            if (TRACE_INST) out.println("Replacing "+pn+" by "+s);
            pn.replaceBy(s, removeCall);
            if (callee.returned.contains(pn)) {
                if (TRACE_INST) out.println(pn+" is returned, updating callee returned set");
                if (removeCall) {
                    callee.returned.remove(pn);
                }
                callee.returned.addAll(s);
            }
            if (callee.thrown.contains(pn)) {
                if (TRACE_INST) out.println(pn+" is thrown, updating callee thrown set");
                if (removeCall) {
                    callee.thrown.remove(pn);
                }
                callee.thrown.addAll(s);
            }
            if (removeCall) {
                callee.nodes.remove(pn);
                if (VERIFY_ASSERTIONS) callee.verifyNoReferences(pn);
            }
        }
        
        Set rvn;
        Object rv = caller.callToRVN.get(mc);
        if (rv instanceof Set) rvn = (Set) rv;
        else if (rv == null) rvn = Collections.EMPTY_SET;
        else rvn = Collections.singleton(rv);
        if (!rvn.isEmpty()) {
            if (TRACE_INST) out.println("Replacing return value "+rvn+" by "+callee.returned);
            for (Iterator j=rvn.iterator(); j.hasNext(); ) {
                ReturnValueNode rvnn = (ReturnValueNode) j.next();
                rvnn.replaceBy(callee.returned, removeCall);
                if (caller.returned.contains(rvnn)) {
                    if (TRACE_INST) out.println(rvnn+" is returned, updating returned set");
                    if (removeCall) caller.returned.remove(rvnn);
                    caller.returned.addAll(callee.returned);
                }
                if (caller.thrown.contains(rvnn)) {
                    if (TRACE_INST) out.println(rvnn+" is thrown, updating thrown set");
                    if (removeCall) caller.thrown.remove(rvnn);
                    caller.thrown.addAll(callee.returned);
                }
                if (removeCall) {
                    if (TRACE_INST) out.println("Removing old return value node "+rvnn+" from nodes list");
                    caller.nodes.remove(rvnn);
                }
                if (VERIFY_ASSERTIONS && removeCall) caller.verifyNoReferences(rvnn);
            }
        }
        
        Set ten;
        Object te = caller.callToTEN.get(mc);
        if (te instanceof Set) ten = (Set) te;
        else if (te == null) ten = Collections.EMPTY_SET;
        else ten = Collections.singleton(te);
        if (!ten.isEmpty()) {
            if (TRACE_INST) out.println("Replacing thrown exception "+ten+" by "+callee.thrown);
            for (Iterator j=ten.iterator(); j.hasNext(); ) {
                ThrownExceptionNode tenn = (ThrownExceptionNode) j.next();
                tenn.replaceBy(callee.thrown, removeCall);
                if (caller.returned.contains(tenn)) {
                    if (TRACE_INST) out.println(tenn+" is returned, updating caller returned set");
                    if (removeCall) caller.returned.remove(tenn);
                    caller.returned.addAll(callee.thrown);
                }
                if (caller.thrown.contains(tenn)) {
                    if (TRACE_INST) out.println(tenn+" is thrown, updating caller thrown set");
                    if (removeCall) caller.thrown.remove(tenn);
                    caller.thrown.addAll(callee.thrown);
                }
                if (removeCall) {
                    if (TRACE_INST) out.println("Removing old thrown exception node "+tenn+" from nodes list");
                    caller.nodes.remove(tenn);
                }
                if (VERIFY_ASSERTIONS && removeCall) caller.verifyNoReferences(tenn);
            }
        }
        
        if (TRACE_INST) out.println("Adding all callee nodes: "+callee.nodes);
        caller.nodes.putAll(callee.nodes);
        
        if (TRACE_INST) out.println("Building a root set for access path unification");
        Set s = NodeSet.FACTORY.makeSet();
        s.addAll(callee.returned);
        s.addAll(callee.thrown);
        for (int ii=0; ii<callee.params.length; ++ii) {
            ParamNode pn = callee.params[ii];
            if (pn == null) continue;
            Set t = (Set)callee_to_caller.get(pn);
            s.addAll(t);
        }
        if (TRACE_INST) out.println("Root set: "+s);
        caller.unifyAccessPaths(s);
        if (removeCall) {
            if (TRACE_INST) out.println("Removing instantiated call: "+mc);
            caller.calls.remove(mc);
            caller.callToRVN.remove(mc);
            caller.callToTEN.remove(mc);
        }
        
        if (VERIFY_ASSERTIONS) {
            caller.verify();
            //caller.copy();
        }
    }

    public AndersenMethod getMethod() { return method; }
    
    public int hashCode() {
        if (DETERMINISTIC)
            return method.hashCode();
        else
            return System.identityHashCode(this);
    }
    
    /** Return a string representation of this summary. */
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Summary for ");
        sb.append(method.toString());
        sb.append(':');
        sb.append(Strings.lineSep);
        for (Iterator i=nodes.keySet().iterator(); i.hasNext(); ) {
            Node n = (Node)i.next();
            sb.append(n.toString_long());
            sb.append(Strings.lineSep);
        }
        if (returned != null && !returned.isEmpty()) {
            sb.append("Returned: ");
            sb.append(returned);
            sb.append(Strings.lineSep);
        }
        if (thrown != null && !thrown.isEmpty()) {
            sb.append("Thrown: ");
            sb.append(thrown);
            sb.append(Strings.lineSep);
        }
        if (calls != null && !calls.isEmpty()) {
            sb.append("Calls: ");
            sb.append(calls);
            sb.append(Strings.lineSep);
        }
        return sb.toString();
    }

    /** Utility function to add the given node to the node set if it is useful,
     *  and transitively for other nodes. */
    private boolean addIfUseful(HashSet visited, HashSet path, Node n) {
        if (path.contains(n)) return true;
        path.add(n);
        if (visited.contains(n)) return nodes.containsKey(n);
        visited.add(n);
        boolean useful = false;
        if (nodes.containsKey(n)) {
            if (TRACE_INTER) out.println("Useful: "+n);
            useful = true;
        }
        if (n instanceof UnknownTypeNode) {
            path.remove(n);
            return true;
        }
        if (n.addedEdges != null) {
            if (TRACE_INTER) out.println("Useful because of added edge: "+n);
            useful = true;
            for (Iterator i=n.addedEdges.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    addAsUseful(visited, path, (Node)o);
                } else {
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        addAsUseful(visited, path, (Node)j.next());
                    }
                }
            }
        }
        if (n.accessPathEdges != null) {
            for (Iterator i=n.accessPathEdges.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    if (addIfUseful(visited, path, (Node)o)) {
                        if (TRACE_INTER && !useful) out.println("Useful because outside edge: "+n+"->"+o);
                        useful = true;
                    } else {
                        if (n != o) i.remove();
                    }
                } else {
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        Node n2 = (Node)j.next();
                        if (addIfUseful(visited, path, n2)) {
                            if (TRACE_INTER && !useful) out.println("Useful because outside edge: "+n+"->"+n2);
                            useful = true;
                        } else {
                            if (n != n2) j.remove();
                        }
                    }
                    if (!useful) i.remove();
                }
            }
        }
        if (n instanceof ReturnedNode) {
            if (TRACE_INTER && !useful) out.println("Useful because ReturnedNode: "+n);
            useful = true;
        }
        if (n.predecessors != null) {
            useful = true;
            if (TRACE_INTER && !useful) out.println("Useful because target of added edge: "+n);
            for (Iterator i=n.predecessors.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    addAsUseful(visited, path, (Node)o);
                } else {
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        addAsUseful(visited, path, (Node)j.next());
                    }
                }
            }
        }
        if (useful) {
            this.nodes.put(n, n);
            if (n instanceof FieldNode) {
                FieldNode fn = (FieldNode)n;
                for (Iterator i=fn.field_predecessors.iterator(); i.hasNext(); ) {
                    addAsUseful(visited, path, (Node)i.next());
                }
            }
        }
        if (TRACE_INTER && !useful) out.println("Not useful: "+n);
        path.remove(n);
        return useful;
    }
    /** Utility function to add the given node to the node set as useful,
     *  and transitively for other nodes. */
    private void addAsUseful(HashSet visited, HashSet path, Node n) {
        if (path.contains(n)) {
            return;
        }
        path.add(n);
        if (visited.contains(n)) {
            if (VERIFY_ASSERTIONS) Assert._assert(nodes.containsKey(n), n.toString());
            return;
        }
        if (n instanceof UnknownTypeNode) {
            path.remove(n);
            return;
        }
        visited.add(n); this.nodes.put(n, n);
        if (TRACE_INTER) out.println("Useful: "+n);
        if (n.addedEdges != null) {
            for (Iterator i=n.addedEdges.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
//                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    addAsUseful(visited, path, (Node)o);
                } else {
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        addAsUseful(visited, path, (Node)j.next());
                    }
                }
            }
        }
        if (n.accessPathEdges != null) {
            for (Iterator i=n.accessPathEdges.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
//                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    if (!addIfUseful(visited, path, (Node)o)) {
                        i.remove();
                    }
                } else {
                    boolean any = false;
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        Node j_n = (Node)j.next();
                        if (!addIfUseful(visited, path, j_n)) {
                            j.remove();
                        } else {
                            any = true;
                        }
                    }
                    if (!any) i.remove();
                }
            }
        }
        if (n.predecessors != null) {
            for (Iterator i=n.predecessors.entrySet().iterator(); i.hasNext(); ) {
                java.util.Map.Entry e = (java.util.Map.Entry)i.next();
//                jq_Field f = (jq_Field)e.getKey();
                Object o = e.getValue();
                if (o instanceof Node) {
                    addAsUseful(visited, path, (Node)o);
                } else {
                    for (Iterator j=((Set)o).iterator(); j.hasNext(); ) {
                        addAsUseful(visited, path, (Node)j.next());
                    }
                }
            }
        }
        if (n instanceof FieldNode) {
            FieldNode fn = (FieldNode)n;
            for (Iterator i=fn.field_predecessors.iterator(); i.hasNext(); ) {
                addAsUseful(visited, path, (Node)i.next());
            }
        }
        path.remove(n);
    }

    /** Returns an iteration of all nodes in this summary. */
    public Iterator nodeIterator() { return nodes.keySet().iterator(); }

    public Set getReturned() {
        return returned;
    }
    public Set getThrown() {
        return thrown;
    }
    public ReturnValueNode getRVN(ProgramLocation mc) {
        return (ReturnValueNode) callToRVN.get(mc);
    }
    public ThrownExceptionNode getTEN(ProgramLocation mc) {
        return (ThrownExceptionNode) callToTEN.get(mc);
    }

    void verify() {
        for (int i=0; i<this.params.length; ++i) {
            if (this.params[i] == null) continue;
            if (!nodes.containsKey(this.params[i])) {
                Assert.UNREACHABLE(this.params[i].toString_long());
            }
        }
        for (Iterator i=returned.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) continue;
            if (!nodes.containsKey(n)) {
                Assert.UNREACHABLE(n.toString_long());
            }
        }
        for (Iterator i=thrown.iterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) continue;
            if (!nodes.containsKey(n)) {
                Assert.UNREACHABLE(n.toString_long());
            }
        }
        for (Iterator i=callToRVN.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry e = (Map.Entry) i.next();
            if (!calls.contains(e.getKey())) {
                Assert.UNREACHABLE(e.toString());
            }
            Object o = e.getValue();
            if (o instanceof Set) {
                for (Iterator j=((Set) o).iterator(); j.hasNext(); ) {
                    Object o2 = j.next();
                    if (!nodes.containsKey(o2)) {
                        Assert.UNREACHABLE(this.toString()+" ::: "+e.toString()+" ::: "+o2);
                    }
                }
            } else if (o != null) {
                if (!nodes.containsKey(o)) {
                    Assert.UNREACHABLE(e.toString());
                }
            }
        }
        for (Iterator i=callToTEN.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry e = (Map.Entry) i.next();
            if (!calls.contains(e.getKey())) {
                Assert.UNREACHABLE(e.toString());
            }
            Object o = e.getValue();
            if (o instanceof Set) {
                for (Iterator j=((Set) o).iterator(); j.hasNext(); ) {
                    Object o2 = j.next();
                    if (!nodes.containsKey(o2)) {
                        Assert.UNREACHABLE(e.toString());
                    }
                }
            } else if (o != null) {
                if (!nodes.containsKey(o)) {
                    Assert.UNREACHABLE(e.toString());
                }
            }
        }
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            if (n instanceof UnknownTypeNode) {
                Assert.UNREACHABLE(n.toString_long());
            }
            if (n.addedEdges != null) {
                for (Iterator j=n.addedEdges.entrySet().iterator(); j.hasNext(); ) {
                    Map.Entry e = (Map.Entry) j.next();
                    jq_Field f = (jq_Field) e.getKey();
                    Object o = e.getValue();
                    if (o instanceof Node) {
                        Node n2 = (Node) o;
                        if (!(n2 instanceof UnknownTypeNode) && !nodes.containsKey(n2)) {
                            Assert.UNREACHABLE(n2.toString_long());
                        }
                        if (!n2.hasPredecessor(f, n)) {
                            Assert.UNREACHABLE(n2.toString_long()+" has no predecessor "+n.toString_long());
                        }
                    } else if (o == null) {
                        
                    } else {
                        Set s = (Set) o;
                        for (Iterator k=s.iterator(); k.hasNext(); ) {
                            Node n2 = (Node) k.next();
                            if (!(n2 instanceof UnknownTypeNode) && !nodes.containsKey(n2)) {
                                Assert.UNREACHABLE(n2.toString_long());
                            }
                            if (!n2.hasPredecessor(f, n)) {
                                Assert.UNREACHABLE(n2.toString_long()+" has no predecessor "+n.toString_long());
                            }
                        }
                    }
                }
            }
            if (n.predecessors != null) {
                for (Iterator j=n.predecessors.entrySet().iterator(); j.hasNext(); ) {
                    Map.Entry e = (Map.Entry) j.next();
                    jq_Field f = (jq_Field) e.getKey();
                    Object o = e.getValue();
                    if (o instanceof Node) {
                        Node n2 = (Node) o;
                        if (n2 != GlobalNode.GLOBAL && !(n2 instanceof UnknownTypeNode) && !nodes.containsKey(n2)) {
                            Assert.UNREACHABLE(this.toString()+" ::: "+n2);
                        }
                        if (!n2.hasEdge(f, n)) {
                            Assert.UNREACHABLE(this.toString()+" ::: "+n2+" -> "+n);
                        }
                    } else if (o == null) {
                        
                    } else {
                        Set s = (Set) o;
                        for (Iterator k=s.iterator(); k.hasNext(); ) {
                            Node n2 = (Node) k.next();
                            if (n2 != GlobalNode.GLOBAL && !(n2 instanceof UnknownTypeNode) && !nodes.containsKey(n2)) {
                                Assert.UNREACHABLE(n2.toString_long());
                            }
                            if (!n2.hasEdge(f, n)) {
                                Assert.UNREACHABLE(n2.toString_long()+" has no edge "+n.toString_long());
                            }
                        }
                    }
                }
            }
            if (n.accessPathEdges != null) {
                for (Iterator j=n.accessPathEdges.entrySet().iterator(); j.hasNext(); ) {
                    Map.Entry e = (Map.Entry) j.next();
                    jq_Field f = (jq_Field) e.getKey();
                    Object o = e.getValue();
                    if (o instanceof FieldNode) {
                        FieldNode n2 = (FieldNode) o;
                        if (!nodes.containsKey(n2)) {
                            Assert.UNREACHABLE(n2.toString_long());
                        }
                        if (!n2.field_predecessors.contains(n)) {
                            Assert.UNREACHABLE(n2.toString_long()+" has no field pred "+n.toString_long());
                        }
                    } else if (o == null) {
                        
                    } else {
                        Set s = (Set) o;
                        for (Iterator k=s.iterator(); k.hasNext(); ) {
                            FieldNode n2 = (FieldNode) k.next();
                            if (!nodes.containsKey(n2)) {
                                Assert.UNREACHABLE(n2.toString_long());
                            }
                            if (!n2.field_predecessors.contains(n)) {
                                Assert.UNREACHABLE(n2.toString_long()+" has no field pred "+n.toString_long());
                            }
                        }
                    }
                }
            }
            if (n instanceof FieldNode) {
                FieldNode fn = (FieldNode) n;
                if (fn.field_predecessors != null) {
                    jq_Field f = (jq_Field) fn.f;
                    for (Iterator j=fn.field_predecessors.iterator(); j.hasNext(); ) {
                        Node n2 = (Node) j.next();
                        if (n2 != GlobalNode.GLOBAL && !(n2 instanceof UnknownTypeNode) && !nodes.containsKey(n2)) {
                            Assert.UNREACHABLE(this.toString()+" ::: "+n2.toString_long());
                        }
                        if (!n2.hasAccessPathEdge(f, fn)) {
                            Assert.UNREACHABLE(this.toString()+" ::: "+n2.toString_long()+" => "+fn.toString_long());
                        }
                    }
                }
            }
            if (n instanceof ReturnValueNode) {
                if (!multiset_contains(callToRVN, n)) {
                    Assert.UNREACHABLE(n.toString_long());
                }
            }
            if (n instanceof ThrownExceptionNode) {
                if (!multiset_contains(callToTEN, n)) {
                    System.out.println(callToTEN);
                    Assert.UNREACHABLE(this.toString()+" ::: "+n.toString_long());
                }
            }
        }
    }

    static boolean multiset_contains(Map m, Object o) {
        for (Iterator i=m.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry e = (Map.Entry)i.next();
            Object p = e.getValue();
            if (p == o) return true;
            if (p instanceof Set)
                if (((Set)p).contains(o)) return true;
        }
        return false;
    }

    void verifyNoReferences(Node n) {
        if (returned.contains(n))
            Assert.UNREACHABLE("ERROR: returned set contains "+n);
        if (thrown.contains(n))
            Assert.UNREACHABLE("ERROR: thrown set contains "+n);
        if (false) {
            for (int i=0; i<this.params.length; ++i) {
                if (this.params[i] == n)
                    Assert.UNREACHABLE("ERROR: param #"+i+" "+n);
            }
        }
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node n2 = (Node) i.next();
            if (n2 instanceof UnknownTypeNode) continue;
            if (n2.addedEdges != null) {
                if (n2.addedEdges.containsValue(n)) {
                    Assert.UNREACHABLE("ERROR: "+n2+" contains an edge to "+n);
                }
            }
            if (n2.predecessors != null) {
                if (n2.predecessors.containsValue(n)) {
                    Assert.UNREACHABLE("ERROR: "+n2+" contains predecessor "+n);
                }
            }
            if (n2.accessPathEdges != null) {
                if (n2.accessPathEdges.containsValue(n)) {
                    Assert.UNREACHABLE("ERROR: "+n2+" contains an edge to "+n);
                }
            }
            if (n2 instanceof FieldNode) {
                FieldNode fn = (FieldNode) n2;
                if (fn.field_predecessors != null) {
                    if (fn.field_predecessors.contains(n)) {
                        Assert.UNREACHABLE("ERROR: "+fn+" contains a field predecessor "+n);
                    }
                }
            }
        }
    }

    ProgramLocation getLocationOf(ConcreteTypeNode n) {
        return new ProgramLocation.QuadProgramLocation(method, n.q);
    }
    
    Collection/*<ProgramLocation>*/ getLocationOf(FieldNode n) {
        ArrayList list = new ArrayList();
        for (Iterator i=n.quads.iterator(); i.hasNext(); ) {
            Quad q = (Quad) i.next();
            list.add(new ProgramLocation.QuadProgramLocation(method, q));
        }
        return list;
    }

    void dotGraph(DataOutput out) throws IOException {
        out.writeBytes("digraph \""+this.method+"\" {\n");
        IndexMap m = new IndexMap("MethodCallMap");
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            out.writeBytes("n"+n.id+" [label=\""+n.toString_short()+"\"];\n");
        }
        for (Iterator i=getCalls().iterator(); i.hasNext(); ) {
            ProgramLocation mc = (ProgramLocation) i.next();
            int k = m.get(mc);
            out.writeBytes("mc"+k+" [label=\""+mc+"\"];\n");
        }
        for (Iterator i=nodeIterator(); i.hasNext(); ) {
            Node n = (Node) i.next();
            for (Iterator j=n.getEdges().iterator(); j.hasNext(); ) {
                Map.Entry e = (Map.Entry) j.next();
                String fieldName = ""+e.getKey();
                Iterator k;
                if (e.getValue() instanceof Set) k = ((Set)e.getValue()).iterator();
                else k = Collections.singleton(e.getValue()).iterator();
                while (k.hasNext()) {
                    Node n2 = (Node) k.next();
                    out.writeBytes("n"+n.id+" -> "+n2.id+" [label=\""+fieldName+"\"];\n");
                }
            }
            for (Iterator j=n.getAccessPathEdges().iterator(); j.hasNext(); ) {
                Map.Entry e = (Map.Entry) j.next();
                String fieldName = ""+e.getKey();
                Iterator k;
                if (e.getValue() instanceof Set) k = ((Set)e.getValue()).iterator();
                else k = Collections.singleton(e.getValue()).iterator();
                while (k.hasNext()) {
                    Node n2 = (Node) k.next();
                    out.writeBytes("n"+n.id+" -> n"+n2.id+" [label=\""+fieldName+"\",style=dashed];\n");
                }
            }
            if (n.getPassedParameters() != null) {
                for (Iterator j=n.getPassedParameters().iterator(); j.hasNext(); ) {
                    PassedParameter pp = (PassedParameter) j.next();
                    int k = m.get(pp.m);
                    out.writeBytes("n"+n.id+" -> mc"+k+" [label=\"p"+pp.paramNum+"\",style=dotted];\n");
                }
            }
            if (n instanceof ReturnedNode) {
                ReturnedNode rn = (ReturnedNode) n;
                int k = m.get(rn.m);
                out.writeBytes("mc"+k+" -> n"+n.id+" [label=\"r\",style=dotted];\n");
            }
        }
        out.writeBytes("}\n");
    }

}
