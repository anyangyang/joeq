// AtomicLong.java, created Aug 9, 2003 3:47:06 AM by John Whaley
// Copyright (C) 2003 John Whaley
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.Common.sun.misc;

/**
 * AtomicLong
 * 
 * @author John Whaley
 * @version $Id: AtomicLong.java,v 1.1 2003/08/09 11:26:15 joewhaley Exp $
 */
abstract class AtomicLong {
    private static boolean VMSupportsCS8() {
        return false;
    }
}
