// CodeCache.java, created Wed Jan 30 22:33:28 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad;

import Clazz.jq_Method;

/**
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: CodeCache.java,v 1.8 2003/05/12 10:05:15 joewhaley Exp $
 */
public class CodeCache {

    public static final CodeCache cache = new CodeCache();

    protected java.util.HashMap map = new java.util.HashMap();
    protected java.util.HashMap bcmap = new java.util.HashMap();

    public static java.util.List/*<ControlFlowGraphVisitor>*/ passes = new java.util.LinkedList();
    
    
    /** Creates new CodeCache */
    public CodeCache() { }

    public static ControlFlowGraph getCode(jq_Method m) { return cache._get(m); }
    public static java.util.Map getBCMap(jq_Method m) { return cache._getmap(m); }
    public static void free(ControlFlowGraph cfg) {
        if (getCode(cfg.getMethod()) == cfg)
            cache._delete(cfg.getMethod());
    }

    public static boolean TRACE = false;
    public static boolean AlwaysMap = false;
    
    protected ControlFlowGraph _get(jq_Method m) {
        ControlFlowGraph cfg = (ControlFlowGraph)map.get(m);
        if (cfg == null) {
            if (TRACE) System.out.println("Generating quads for "+m);
            BytecodeToQuad b2q = new BytecodeToQuad(m);
            cfg = b2q.convert();
            map.put(m, cfg);
            if (AlwaysMap) {
                bcmap.put(m, b2q.getQuadToBytecodeMap());
            }
            for (java.util.Iterator i = passes.iterator(); i.hasNext(); ) {
                ControlFlowGraphVisitor v = (ControlFlowGraphVisitor)i.next();
                v.visitCFG(cfg);
            }
        }
        return cfg;
    }

    protected java.util.Map _getmap(jq_Method m) {
        java.util.Map result = (java.util.Map)bcmap.get(m);
        if (result == null) {
            if (TRACE) System.out.println("Generating quads for "+m);
            BytecodeToQuad b2q = new BytecodeToQuad(m);
            ControlFlowGraph cfg = b2q.convert();
            map.put(m, cfg);
            result = b2q.getQuadToBytecodeMap();
            bcmap.put(m, result);
        }
        return result;
    }
    
    protected void _delete(jq_Method m) {
        map.remove(m);
        bcmap.remove(m);
    }
}
