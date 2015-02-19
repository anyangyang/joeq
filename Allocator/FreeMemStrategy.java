// FreeMemStrategy.java, created Mon Nov 25  6:52:59 2002 by laudney
// Copyright (C) 2001-3 laudney <laudney@acm.org>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Allocator;

import java.util.Collection;

/**
 * FreeMemStrategy
 *
 * @author laudney <laudney@acm.org>
 * @version $Id: FreeMemStrategy.java,v 1.4 2003/05/12 10:04:52 joewhaley Exp $
 */
public interface FreeMemStrategy {
    public void addCollection(Collection c);
    public void addFreeMem(MemUnit unit);
    public MemUnit getFreeMem(int size);
}
