// FilterIterator.java, created Tue Feb 23 06:17:30 1999 by cananian
// Copyright (C) 1998 C. Scott Ananian <cananian@alumni.princeton.edu>
// Licensed under the terms of the GNU GPL; see COPYING for details.

package Util.Collections;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A <code>FilterIterator</code> filters and maps a source
 * <code>Iterator</code> to generate a new one.
 *
 * Note that this implementation reads one element ahead, so if the
 * Filter changes for an object 'o' between the time that is read
 * (when next() is called, returning the object preceding 'o', and
 * checking that 'o' satisfies the current Filter) and the time when
 * hasNext() is called, 'o' will still be returned, regardless of what
 * Filter.isElement(o) returns.  Thus, it is recommended that only
 * Filters which remain consistent throughout the iteration be used. 
 * 
 * @author  C. Scott Ananian <cananian@alumni.princeton.edu>
 * @version $Id: FilterIterator.java,v 1.1 2003/03/05 08:26:27 joewhaley Exp $
 */
public class FilterIterator extends UnmodifiableIterator implements Iterator {
    /*final*/ Iterator i;
    /*final*/ Filter f;
    /** Creates a <code>FilterIterator</code>. */
    public FilterIterator(Iterator i, Filter f) {
        this.i = i; this.f = f; advance();
    }

    private Object next = null;
    private boolean done = false;

    private void advance() {
        while (i.hasNext()) {
            next = i.next();
            if (f.isElement(next))
                return; // found next element.
        }
        done = true; // found end of enumeration.
    }

    public Object next() {
        if (done) throw new NoSuchElementException();
        Object o = next; advance(); return f.map(o);
    }
    public boolean hasNext() {
        return !done;
    }

    public static class Filter { // default is an identity mapping.
        /** Return <code>true</code> if the specified element should be
            included in the filtered enumeration. 
         
            <BR> Default implementation returns true for all
            <code>Object</code>s (no filter).   
         */
        public boolean isElement(Object o) { return true; }

        /** Perform a mapping on elements from the source enumeration. 

            <BR> Default implementation returns <code>o</code>
            (identity mapping). 
         */
        public Object map(Object o) { return o; }
    }
}
