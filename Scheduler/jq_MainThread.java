// jq_MainThread.java, created Mon Apr  9  1:52:50 2001 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Scheduler;

import Clazz.jq_StaticMethod;
import Run_Time.Reflection;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: jq_MainThread.java,v 1.3 2003/05/12 10:05:20 joewhaley Exp $
 */
public class jq_MainThread extends java.lang.Thread {

    jq_StaticMethod m;
    Object arg;
    
    /** Creates new MainThread */
    public jq_MainThread(jq_StaticMethod m, Object arg) {
        this.m = m; this.arg = arg;
    }
    
    public void run() {
        try {
            Reflection.invokestatic_V(m, arg);
        } catch (Throwable t) {
            System.err.println("Exception occurred! "+t);
            t.printStackTrace(System.err);
            System.exit(-1);
        }
    }

}
