// ProgramLocation.java, created Sun Sep  1 17:38:25 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import Clazz.jq_Class;
import Clazz.jq_ClassFileConstants;
import Clazz.jq_InstanceMethod;
import Clazz.jq_Method;
import Clazz.jq_Reference;
import Compil3r.BytecodeAnalysis.BytecodeVisitor;
import Compil3r.BytecodeAnalysis.CallTargets;
import Compil3r.Quad.AndersenInterface.AndersenMethod;
import Compil3r.Quad.AndersenInterface.AndersenReference;
import Compil3r.Quad.AndersenInterface.AndersenType;
import Compil3r.Quad.MethodSummary.ConcreteTypeNode;
import Compil3r.Quad.MethodSummary.Node;
import Compil3r.Quad.Operator.Invoke;
import Compil3r.Quad.SSAReader.SSAClass;
import Compil3r.Quad.SSAReader.SSAMethod;
import Compil3r.Quad.SSAReader.SSAType;
import UTF.Utf8;
import Util.Assert;
import Util.Collections.HashCodeComparator;
import Util.Collections.SortedArraySet;

/**
 * This class combines a jq_Method with a Quad to represent a location in the code.
 * This is useful for interprocedural analysis.
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: ProgramLocation.java,v 1.23 2003/08/03 12:32:34 joewhaley Exp $
 */
public abstract class ProgramLocation {
    protected final AndersenMethod m;
    public ProgramLocation(AndersenMethod m) {
        this.m = m;
    }
    public AndersenMethod getMethod() { return m; }
    public abstract int getNumParams();
    public abstract AndersenType getParamType(int i);
    
    public abstract Utf8 getSourceFile();
    public abstract int getLineNumber();
    
    public abstract int getID();
    public abstract int getBytecodeIndex();
    
    public abstract boolean isSingleTarget();
    public abstract boolean isInterfaceCall();
    
    public abstract AndersenMethod getTargetMethod();
    
    public abstract CallTargets getCallTargets();

    public abstract CallTargets getCallTargets(AndersenReference klass, boolean exact);
    public abstract CallTargets getCallTargets(java.util.Set receiverTypes, boolean exact);
    
    public CallTargets getCallTargets(AndersenMethod target, Node n) {
        return getCallTargets((AndersenReference)n.getDeclaredType(), n instanceof ConcreteTypeNode);
    }
    
    public CallTargets getCallTargets(java.util.Set nodes) {
        Set exact_types = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);
        Set notexact_types = SortedArraySet.FACTORY.makeSet(HashCodeComparator.INSTANCE);

        for (Iterator i=nodes.iterator(); i.hasNext(); ) {
            Node n = (Node)i.next();
            Set s = (n instanceof ConcreteTypeNode)?exact_types:notexact_types;
            if (n.getDeclaredType() != null)
                s.add(n.getDeclaredType());
        }
        if (notexact_types.isEmpty()) return getCallTargets(exact_types, true);
        if (exact_types.isEmpty()) return getCallTargets(notexact_types, false);
        CallTargets ct = getCallTargets(exact_types, true);
        if (ct==null) return null;
        // bugfix - added 'ct=' (Daniel Wright)
        ct = ct.union(getCallTargets(notexact_types, false));
        return ct;
    }
    
    public static class QuadProgramLocation extends ProgramLocation {
        private final Quad q;
        public QuadProgramLocation(AndersenMethod m, Quad q) {
            super(m);
            this.q = q;
        }
        
        public AndersenMethod getTargetMethod() {
            if (!(q.getOperator() instanceof Invoke)) return null;
            return Invoke.getMethod(q).getMethod();
        }
        
        public int getID() { return q.getID(); }
        public int getBytecodeIndex() {
            Map map = CodeCache.getBCMap((jq_Method) super.m);
            Integer i = (Integer) map.get(q);
            return i.intValue();
        }
        
        public Utf8 getSourceFile() {
            jq_Method method = (jq_Method) m;
            return method.getDeclaringClass().getSourceFile();
        }
        public int getLineNumber() {
            jq_Method method = (jq_Method) m;
            int bci = getBytecodeIndex();
            return method.getLineNumber(bci);
        }
        
        public int getNumParams() { return Invoke.getParamList(q).length(); }
        public AndersenType getParamType(int i) { return Invoke.getParamList(q).get(i).getType(); }
        
        public int hashCode() {
            return (q==null)?-1:q.hashCode();
        }
        public boolean equals(QuadProgramLocation that) { return this.q == that.q; }
        public boolean equals(Object o) { if (o instanceof QuadProgramLocation) return equals((QuadProgramLocation)o); return false; }
        
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(super.m.getName());
            sb.append("() quad ");
            sb.append((q==null)?-1:q.getID());
            if (q.getOperator() instanceof Invoke) {
                sb.append(" => ");
                sb.append(Invoke.getMethod(q).getMethod().getName());
                sb.append("()");
            }
            return sb.toString();
        }
        
        public boolean isSingleTarget() {
            if (isInterfaceCall()) return false;
            if (!((Invoke) q.getOperator()).isVirtual()) return true;
            jq_InstanceMethod target = (jq_InstanceMethod) Invoke.getMethod(q).getMethod();
            target.getDeclaringClass().load();
            if (target.getDeclaringClass().isFinal()) return true;
            target.getDeclaringClass().prepare();
            if (!target.isLoaded()) {
                target = target.resolve1();
                if (!target.isLoaded()) {
                    // bad target method!
                    return false;
                }
                Invoke.getMethod(q).setMethod(target);
            }
            if (!target.isVirtual()) return true;
            return false;
        }
        
        public boolean isInterfaceCall() {
            return q.getOperator() instanceof Invoke.InvokeInterface;
        }
        
        private byte getInvocationType() {
            if (q.getOperator() instanceof Invoke.InvokeVirtual) {
                return BytecodeVisitor.INVOKE_VIRTUAL;
            } else if (q.getOperator() instanceof Invoke.InvokeStatic) {
                jq_Method target = Invoke.getMethod(q).getMethod();
                if (target instanceof jq_InstanceMethod)
                    return BytecodeVisitor.INVOKE_SPECIAL;
                else
                    return BytecodeVisitor.INVOKE_STATIC;
            } else {
                Assert._assert(q.getOperator() instanceof Invoke.InvokeInterface);
                return BytecodeVisitor.INVOKE_INTERFACE;
            }
        }
        
        public CallTargets getCallTargets() {
            if (!(q.getOperator() instanceof Invoke)) return null;
            jq_Method target = Invoke.getMethod(q).getMethod();
            byte type = getInvocationType();
            return CallTargets.getTargets(target.getDeclaringClass(), target, type, true);
        }
        
        public CallTargets getCallTargets(AndersenReference klass, boolean exact) {
            if (!(q.getOperator() instanceof Invoke)) return null;
            jq_Method target = Invoke.getMethod(q).getMethod();
            byte type = getInvocationType();
            return CallTargets.getTargets(target.getDeclaringClass(), target, type, (jq_Reference)klass, exact, true);
        }
        
        public CallTargets getCallTargets(java.util.Set receiverTypes, boolean exact) {
            if (!(q.getOperator() instanceof Invoke)) return null;
            jq_Method target = Invoke.getMethod(q).getMethod();
            byte type = getInvocationType();
            return CallTargets.getTargets(target.getDeclaringClass(), target, type, receiverTypes, exact, true);
        }
        
        public Quad getQuad() { return q; }
    }
    
    public static class BCProgramLocation extends ProgramLocation {
        final int bcIndex;
        
        public BCProgramLocation(jq_Method m, int bcIndex) {
            super(m);
            this.bcIndex = bcIndex;
        }
        
        public int getID() { return bcIndex; }
        public int getBytecodeIndex() { return bcIndex; }
        
        public Utf8 getSourceFile() {
            jq_Method method = (jq_Method) m;
            return method.getDeclaringClass().getSourceFile();
        }
        public int getLineNumber() {
            jq_Method method = (jq_Method) m;
            return method.getLineNumber(bcIndex);
        }
        
        public AndersenMethod getTargetMethod() {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return null;
            char cpi = Util.Convert.twoBytesToChar(bc, bcIndex+1);
            switch (bc[bcIndex]) {
                case (byte) jq_ClassFileConstants.jbc_INVOKEVIRTUAL:
                case (byte) jq_ClassFileConstants.jbc_INVOKESPECIAL:
                case (byte) jq_ClassFileConstants.jbc_INVOKEINTERFACE:
                    return clazz.getCPasInstanceMethod(cpi);
                case (byte) jq_ClassFileConstants.jbc_INVOKESTATIC:
                    return clazz.getCPasStaticMethod(cpi);
                case (byte) jq_ClassFileConstants.jbc_MULTIANEWARRAY:
                    return Run_Time.Arrays._multinewarray;
                default:
                    return null;
            }
        }
        public int getNumParams() {
            return ((jq_Method) getTargetMethod()).getParamTypes().length;
        }
        public AndersenType getParamType(int i) {
            return ((jq_Method) getTargetMethod()).getParamTypes()[i];
        }

        public boolean isSingleTarget() {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return false;
            switch (bc[bcIndex]) {
                case (byte) jq_ClassFileConstants.jbc_INVOKESPECIAL:
                case (byte) jq_ClassFileConstants.jbc_INVOKESTATIC:
                case (byte) jq_ClassFileConstants.jbc_MULTIANEWARRAY:
                    return true;
                case (byte) jq_ClassFileConstants.jbc_INVOKEVIRTUAL:
                case (byte) jq_ClassFileConstants.jbc_INVOKEINTERFACE:
                default:
                    return false;
            }
        }
        public boolean isInterfaceCall() {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return false;
            return bc[bcIndex] == jq_ClassFileConstants.jbc_INVOKEINTERFACE;
        }

        public int hashCode() {
            return super.m.hashCode() ^ bcIndex;
        }
        public boolean equals(BCProgramLocation that) {
            return this.bcIndex == that.bcIndex && super.m == that.m;
        }
        public boolean equals(Object o) {
            if (o instanceof BCProgramLocation) return equals((BCProgramLocation) o);
            return false;
        }
        public String toString() {
            String s = super.m.getName()+"() @ "+bcIndex;
            return s;
        }
        
        public CallTargets getCallTargets() {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return null;
            char cpi = Util.Convert.twoBytesToChar(bc, bcIndex+1);
            byte type;
            jq_Method method;
            switch (bc[bcIndex]) {
                case (byte) jq_ClassFileConstants.jbc_INVOKEVIRTUAL:
                    type = BytecodeVisitor.INVOKE_VIRTUAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKESPECIAL:
                    type = BytecodeVisitor.INVOKE_SPECIAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKEINTERFACE:
                    method = clazz.getCPasInstanceMethod(cpi);
                    type = BytecodeVisitor.INVOKE_INTERFACE;
                    break;
                case (byte) jq_ClassFileConstants.jbc_INVOKESTATIC:
                    method = clazz.getCPasStaticMethod(cpi);
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                case (byte) jq_ClassFileConstants.jbc_MULTIANEWARRAY:
                    method = Run_Time.Arrays._multinewarray;
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                default:
                    return null;
            }
            return CallTargets.getTargets(clazz, method, type, true);
        }
        public CallTargets getCallTargets(AndersenReference klass, boolean exact) {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return null;
            char cpi = Util.Convert.twoBytesToChar(bc, bcIndex+1);
            byte type;
            jq_Method method;
            switch (bc[bcIndex]) {
                case (byte) jq_ClassFileConstants.jbc_INVOKEVIRTUAL:
                    type = BytecodeVisitor.INVOKE_VIRTUAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKESPECIAL:
                    type = BytecodeVisitor.INVOKE_SPECIAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKEINTERFACE:
                    method = clazz.getCPasInstanceMethod(cpi);
                    type = BytecodeVisitor.INVOKE_INTERFACE;
                    break;
                case (byte) jq_ClassFileConstants.jbc_INVOKESTATIC:
                    method = clazz.getCPasStaticMethod(cpi);
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                case (byte) jq_ClassFileConstants.jbc_MULTIANEWARRAY:
                    method = Run_Time.Arrays._multinewarray;
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                default:
                    return null;
            }
            return CallTargets.getTargets(clazz, method, type, (jq_Reference) klass, exact, true);
        }
        public CallTargets getCallTargets(java.util.Set receiverTypes, boolean exact) {
            jq_Class clazz = ((jq_Method) super.m).getDeclaringClass();
            byte[] bc = ((jq_Method) super.m).getBytecode();
            if (bc == null || bcIndex < 0 || bcIndex+2 >= bc.length) return null;
            char cpi = Util.Convert.twoBytesToChar(bc, bcIndex+1);
            byte type;
            jq_Method method;
            switch (bc[bcIndex]) {
                case (byte) jq_ClassFileConstants.jbc_INVOKEVIRTUAL:
                    type = BytecodeVisitor.INVOKE_VIRTUAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKESPECIAL:
                    type = BytecodeVisitor.INVOKE_SPECIAL;
                    // fallthrough
                case (byte) jq_ClassFileConstants.jbc_INVOKEINTERFACE:
                    method = clazz.getCPasInstanceMethod(cpi);
                    type = BytecodeVisitor.INVOKE_INTERFACE;
                    break;
                case (byte) jq_ClassFileConstants.jbc_INVOKESTATIC:
                    method = clazz.getCPasStaticMethod(cpi);
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                case (byte) jq_ClassFileConstants.jbc_MULTIANEWARRAY:
                    method = Run_Time.Arrays._multinewarray;
                    type = BytecodeVisitor.INVOKE_STATIC;
                    break;
                default:
                    return null;
            }
            return CallTargets.getTargets(clazz, method, type, receiverTypes, exact, true);
        }
    }
    
    public static class SSAProgramLocation extends ProgramLocation {
        final int identifier; // for .equals() comparison - should identify program location
        SSAMethod targetMethod;
        
        public SSAProgramLocation(int identifier, AndersenMethod m, SSAMethod targetMethod) {
            super(m);
            this.identifier = identifier;
            this.targetMethod = targetMethod;
        }
        
        public int getID() { return identifier; }
        public int getBytecodeIndex() {
            Assert.UNREACHABLE();
            return 0;
        }
        
        public Utf8 getSourceFile() {
            Assert.UNREACHABLE();
            return null;
        }
        public int getLineNumber() {
            Assert.UNREACHABLE();
            return 0;
        }
        
        public AndersenMethod getTargetMethod() { return targetMethod; }
        public int getNumParams() { return targetMethod.getNumParams(); }
        public AndersenType getParamType(int i) { return targetMethod.getParamType(i); }

        public boolean isSingleTarget() { return false; } // todo.
        public boolean isInterfaceCall() { return false; }

        public int hashCode() { return identifier; }
        public boolean equals(SSAProgramLocation that) { return this.identifier == that.identifier; }
        public boolean equals(Object o) { if (o instanceof SSAProgramLocation) return equals((SSAProgramLocation)o); return false; }
        public String toString() {
            String s = super.m.getName()+"() invocation "+identifier;
            if (targetMethod != null)
                s += " => "+targetMethod.getName()+"()";
            return s;
        }
        
        
        public CallTargets getCallTargets() {
            return targetMethod.getCallTargets(targetMethod.getDeclaringClass(), false);
        }
        public CallTargets getCallTargets(AndersenReference klass, boolean exact) {
	    
            SSAType ssaType = (SSAType)klass;
	    SSAClass clazz = null;
	    if (ssaType.getTargetType()!=null)
		clazz = ssaType.getTargetType().getSSAClass();
	    // else this is not a pointer to a class

            return targetMethod.getCallTargets(clazz, exact);
        }
        public CallTargets getCallTargets(java.util.Set receiverTypes, boolean exact) {
            CallTargets ct = CallTargets.NoCallTarget.INSTANCE;
            
            for (Iterator i = receiverTypes.iterator(); i.hasNext(); ) {
                ct = ct.union(getCallTargets((AndersenReference)i.next(), exact));
            }
            
            return ct;
        }
    }

}
