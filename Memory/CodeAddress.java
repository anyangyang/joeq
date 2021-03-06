// CodeAddress.java, created Wed Sep 18  1:22:46 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Memory;

import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_StaticField;

/**
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: CodeAddress.java,v 1.4 2003/03/05 08:35:33 joewhaley Exp $
 */
public class CodeAddress extends Address {

    public static CodeAddressFactory FACTORY;
    
    public abstract static class CodeAddressFactory {
        public abstract int size();
        
        public abstract CodeAddress getNull();
    }
    
    public static final int size() {
        return FACTORY.size();
    }

    public static final CodeAddress getNull() {
        return FACTORY.getNull();
    }

    public native Address peek();
    public native byte    peek1();
    public native short   peek2();
    public native int     peek4();
    public native long    peek8();
    
    public native void poke(Address v);
    public native void poke1(byte v);
    public native void poke2(short v);
    public native void poke4(int v);
    public native void poke8(long v);
    
    public native Address offset(int offset);
    public native Address align(int shift);
    public native int difference(Address v);
    public native boolean isNull();
    
    public native int to32BitValue();
    public native String stringRep();
    
    public static final jq_Class _class;
    public static final jq_StaticField _FACTORY;
    static {
        _class = (jq_Class) PrimordialClassLoader.loader.getOrCreateBSType("LMemory/CodeAddress;");
        _FACTORY = _class.getOrCreateStaticField("FACTORY", "LMemory/CodeAddress$CodeAddressFactory;");
    }
}
