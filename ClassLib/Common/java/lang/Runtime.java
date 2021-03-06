// Runtime.java, created Thu Jul  4  4:50:03 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.Common.java.lang;

import Util.Assert;

/**
 * Runtime
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: Runtime.java,v 1.6 2003/05/12 10:04:53 joewhaley Exp $
 */
public abstract class Runtime {

    // native method implementations.
    private Process execInternal(java.lang.String cmdarray[],
                                 java.lang.String envp[],
                                 java.lang.String path) 
        throws java.io.IOException {
        Assert.TODO();
        return null;
    }

    public long freeMemory() {
        // TODO
        return 0L;
    }
    public long totalMemory() {
        // TODO
        return 0L;
    }
    public void gc() {
        // TODO
    }
    private static void runFinalization0() {
        try {
            ClassLib.Common.java.lang.ref.Finalizer.runFinalization();
        } catch (java.lang.Throwable t) {
        }
    }
    public void traceInstructions(boolean on) {
        // TODO
    }
    public void traceMethodCalls(boolean on) {
        // TODO
    }
    
}
