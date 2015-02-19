// RootedCHACallGraph.java, created Sat Mar 29  0:56:01 2003 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import Clazz.jq_Class;
import Clazz.jq_Type;
import Main.HostedVM;
import Util.Graphs.Navigator;
import Util.Graphs.SCCTopSortedGraph;
import Util.Graphs.SCComponent;

/**
 * @author John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: RootedCHACallGraph.java,v 1.6 2003/07/22 22:42:10 joewhaley Exp $
 */
public class RootedCHACallGraph extends CHACallGraph {
    
    Collection roots;
    
    public RootedCHACallGraph() { }
    public RootedCHACallGraph(Set classes) {
        super(classes);
    }
    
    /* (non-Javadoc)
     * @see Compil3r.Quad.CallGraph#getRoots()
     */
    public Collection getRoots() {
        return roots;
    }

    /* (non-Javadoc)
     * @see Compil3r.Quad.CallGraph#setRoots(java.util.Collection)
     */
    public void setRoots(Collection roots) {
        this.roots = roots;
    }

    public static void main(String[] args) {
        long time;
        
        HostedVM.initialize();
        
        jq_Class c = (jq_Class) jq_Type.parseType(args[0]);
        c.prepare();
        
        System.out.print("Building call graph...");
        time = System.currentTimeMillis();
        CallGraph cg = new RootedCHACallGraph();
        cg = new CachedCallGraph(cg);
        Collection roots = Arrays.asList(c.getDeclaredStaticMethods());
        cg.setRoots(roots);
        time = System.currentTimeMillis() - time;
        System.out.println("done. ("+(time/1000.)+" seconds)");
        
        test(cg);
    }
    
    public static void test(CallGraph cg) {
        long time;
        if (true) {
            System.out.print("Building navigator...");
            time = System.currentTimeMillis();
            Navigator n = cg.getNavigator();
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+(time/1000.)+" seconds)");
            
            System.out.print("Building strongly-connected components...");
            time = System.currentTimeMillis();
            Set s = SCComponent.buildSCC(cg.getRoots(), n);
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+(time/1000.)+" seconds)");
            
            System.out.print("Topologically sorting strongly-connected components...");
            time = System.currentTimeMillis();
            SCCTopSortedGraph g = SCCTopSortedGraph.topSort(s);
            time = System.currentTimeMillis() - time;
            System.out.println("done. ("+(time/1000.)+" seconds)");
            
            /*
            for (Iterator j=g.getFirst().listTopSort().iterator(); j.hasNext(); ) {
                SCComponent d = (SCComponent) j.next();
                System.out.println(d);
            }
            */
            
            /*
            long paths = 0L;
            for (int i=1; ; ++i) {
                long paths2 = Util.Graphs.CountPaths.countPaths(g.getNavigator(), s, i);
                if (paths2 == paths) break;
                paths = paths2;
                System.out.println("Number of paths (k="+i+") = "+paths);
            }
            */
            System.out.println("Number of paths (k=infinity) = "+Util.Graphs.CountPaths.countPaths(g.getNavigator(), s));
        }
        
        if (false) {
            Collection[] depths = INSTANCE.findDepths();
            for (int i=0; i<depths.length; ++i) {
                System.out.println(">>>>> Depth "+i);
                System.out.println(depths[i]);
            }
        }
    }

}