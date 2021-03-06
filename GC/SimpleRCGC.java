// SimpleRCGC.java, created Wed Sep 25  7:09:24 2002 by laudney
// Copyright (C) 2001-3 laudney <laudney@acm.org>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package GC;

import Scheduler.jq_NativeThread;
import Scheduler.jq_RegisterState;

/**
 * Simple Reference Counting GC
 *
 * @author laudney <laudney@acm.org>
 * @version $Id: SimpleRCGC.java,v 1.8 2003/05/12 10:05:17 joewhaley Exp $
 */
public class SimpleRCGC implements Runnable, GCVisitor {

    public static /*final*/ boolean TRACE = false;

    public void run() {
    }

    public void visit(jq_RegisterState state) {
    }

    public void farewell(jq_NativeThread[] nt) {
    }
}
