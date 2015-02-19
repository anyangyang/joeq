// Solver.java, created Jun 15, 2003 1:02:13 AM by joewhaley
// Copyright (C) 2003 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Dataflow;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import Util.Collections.MapFactory;
import Util.Graphs.Graph;
import Util.Graphs.Traversals;

/**
 * SortedSetSolver
 * 
 * @author John Whaley
 * @version $Id: SortedSetSolver.java,v 1.1 2003/06/17 02:37:51 joewhaley Exp $
 */
public class SortedSetSolver extends WorklistSolver {
    
    /** All locations in the graph, stored in a list. */
    protected List allNodes;
    /** Worklist of locations, sorted by priority. */
    protected SortedSet worklist;
    /** Location ordering function. */
    protected Comparator ordering;

    public SortedSetSolver(MapFactory f) {
        super(f);
    }
    public SortedSetSolver() {
        super();
    }
    public SortedSetSolver(Comparator c) {
        super();
        setOrder(c);
    }

    /** Set the default ordering for this solver.
     * 
     * @param c comparator object that implements the ordering
     */
    public void setOrder(Comparator c) {
        this.ordering = c;
    }
    
    /* (non-Javadoc)
     * @see Compil3r.Dataflow.Solver#initialize(Compil3r.Dataflow.Problem, Util.Graphs.Graph)
     */
    public void initialize(Problem p, Graph graph) {
        super.initialize(p, graph);
        allNodes = Traversals.reversePostOrder(graphNavigator, boundaries);
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.Solver#allLocations()
     */
    public Iterator allLocations() {
        return allNodes.iterator();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#initializeWorklist()
     */
    protected void initializeWorklist() {
        worklist = new TreeSet(ordering);
        worklist.addAll(allNodes);
        worklist.removeAll(boundaries);
    }
    
    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#hasNext()
     */
    protected boolean hasNext() {
        return !worklist.isEmpty();
    }

    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#pull()
     */
    protected Object pull() {
        Iterator i = worklist.iterator();
        Object o = i.next();
        i.remove();
        return o;
    }
    
    /* (non-Javadoc)
     * @see Compil3r.Dataflow.WorklistSolver#pushAll(java.util.Collection)
     */
    protected void pushAll(Collection c) {
        worklist.addAll(c);
    }
}
