// DefaultCodeAllocator.java, created Mon Apr  9  1:01:21 2001 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Allocator;

import Allocator.CodeAllocator.x86CodeBuffer;
import Bootstrap.PrimordialClassLoader;
import Clazz.jq_Class;
import Clazz.jq_StaticField;
import Memory.Address;
import Memory.CodeAddress;
import Run_Time.Unsafe;

/**
 * DefaultCodeAllocator
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: DefaultCodeAllocator.java,v 1.8 2003/05/12 10:04:52 joewhaley Exp $
 */
public abstract class DefaultCodeAllocator {

    public static CodeAllocator default_allocator;

    public static final CodeAllocator def() {
        if (default_allocator != null) return default_allocator;
        return Unsafe.getThreadBlock().getNativeThread().getCodeAllocator();
    }
    
    public static final void init() {
        def().init();
    }
    public static final x86CodeBuffer getCodeBuffer(int estimatedSize, int offset, int alignment) {
        x86CodeBuffer o = def().getCodeBuffer(estimatedSize, offset, alignment);
        return o;
    }
    public static final void patchAbsolute(Address code, Address heap) {
        def().patchAbsolute(code, heap);
    }
    public static final void patchRelativeOffset(CodeAddress code, CodeAddress target) {
        def().patchRelativeOffset(code, target);
    }
    
    public static final jq_StaticField _default_allocator;
    static {
        jq_Class k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("LAllocator/DefaultCodeAllocator;");
        _default_allocator = k.getOrCreateStaticField("default_allocator", "LAllocator/CodeAllocator;");
    }
}
