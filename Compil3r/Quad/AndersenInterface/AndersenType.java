// AndersenType.java, created Wed Dec 11 11:45:50 2002 by dmwright
// Copyright (C) 2001-3 Daniel Wright <dmwright@stanford.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Compil3r.Quad.AndersenInterface;

/**
 *
 * @author  Daniel Wright <dmwright@stanford.edu>
 * @version $Id: AndersenType.java,v 1.2 2003/05/12 10:05:16 joewhaley Exp $
 */
public interface AndersenType {
    public void load();
    public void verify();
    public void prepare();
        
    public boolean isArrayType();
}
