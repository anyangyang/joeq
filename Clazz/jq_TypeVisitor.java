// jq_TypeVisitor.java, created Fri Jan 11 17:28:36 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Clazz;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: jq_TypeVisitor.java,v 1.4 2003/05/12 10:05:13 joewhaley Exp $
 */
public interface jq_TypeVisitor {

    void visitClass(jq_Class m);
    void visitArray(jq_Array m);
    void visitPrimitive(jq_Primitive m);
    void visitType(jq_Type m);
    
    class EmptyVisitor implements jq_TypeVisitor {
        public void visitClass(jq_Class m) {}
        public void visitArray(jq_Array m) {}
        public void visitPrimitive(jq_Primitive m) {}
        public void visitType(jq_Type m) {}
    }
    
}
