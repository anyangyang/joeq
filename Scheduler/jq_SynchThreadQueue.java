// jq_SynchThreadQueue.java, created Mon Apr  9  1:52:50 2001 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Scheduler;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: jq_SynchThreadQueue.java,v 1.3 2003/05/12 10:05:20 joewhaley Exp $
 */
public class jq_SynchThreadQueue extends jq_ThreadQueue {

    //public synchronized boolean isEmpty() { return super.isEmpty(); }
    public synchronized void enqueue(jq_Thread t) { super.enqueue(t); }
    public synchronized jq_Thread dequeue() { return super.dequeue(); }

}
