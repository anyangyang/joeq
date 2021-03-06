// BestFitStrategy.java, created Mon Mar 17  2:03:41 2003 by laudney
// Copyright (C) 2001-3 laudney <laudney@acm.org>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Allocator;

import java.util.Collection;
import java.util.TreeSet;

/**
 * Best Fit Strategy
 *
 * @author laudney <laudney@acm.org>
 * @version $Id: BestFitStrategy.java,v 1.3 2003/05/12 10:04:52 joewhaley Exp $
 */
public class BestFitStrategy implements FreeMemStrategy {
    private TreeSet freePool;

    public BestFitStrategy() {
        freePool = new TreeSet(new MemUnitComparator());
    }

    public void addFreeMem(MemUnit unit) {
        freePool.add(unit);
    }

    public void addCollection(Collection c) {
        freePool.addAll(c);
    }

    public MemUnit getFreeMem(int size) {
        if (false) {
            MemUnit target = new MemUnit(null, size);
            return (MemUnit) (freePool.tailSet(target).first());
        } else {
            // FIXME: circular dependency. the allocation of MemUnit above
            // calls the allocator which calls getFreeMem.
            return null;
        }
    }
}
