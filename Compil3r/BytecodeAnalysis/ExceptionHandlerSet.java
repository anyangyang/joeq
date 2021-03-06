// ExceptionHandlerSet.java, created Fri Jan 11 17:28:36 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.BytecodeAnalysis;

import Util.Collections.ListFactory;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: ExceptionHandlerSet.java,v 1.5 2003/05/12 10:05:14 joewhaley Exp $
 */
public class ExceptionHandlerSet {

    final ExceptionHandler exception_handler;
    ExceptionHandlerSet parent;
    
    ExceptionHandlerSet(ExceptionHandler exception_handler, ExceptionHandlerSet parent) {
        this.exception_handler = exception_handler;
        this.parent = parent;
    }
    
    public ExceptionHandler getHandler() { return exception_handler; }
    public ExceptionHandlerSet getParent() { return parent; }

    public ExceptionHandlerIterator iterator() {
        return new ExceptionHandlerIterator(ListFactory.singleton(exception_handler), parent);
    }
}
