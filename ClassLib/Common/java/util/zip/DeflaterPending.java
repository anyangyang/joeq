// DeflaterPending.java, created Mon Jul  8  4:06:18 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.Common.java.util.zip;

/**
 * DeflaterPending
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: DeflaterPending.java,v 1.4 2003/05/12 10:04:53 joewhaley Exp $
 */
class DeflaterPending extends PendingBuffer
{
  public DeflaterPending()
  {
    super(DeflaterConstants.PENDING_BUF_SIZE);
  }
}
