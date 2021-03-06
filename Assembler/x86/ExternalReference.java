// ExternalReference.java, created Tue Feb 27  2:59:43 2001 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package Assembler.x86;

import java.io.DataOutput;
import java.io.IOException;

import Memory.HeapAddress;
import Util.Assert;

/**
 * ExternalReference
 *
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: ExternalReference.java,v 1.12 2003/05/12 10:04:52 joewhaley Exp $
 */
public class ExternalReference extends Reloc {

    private HeapAddress heap_from;
    private int symbol_ndx;
    private String external_name;
    
    /** Creates new ExternalReference */
    public ExternalReference(HeapAddress heap_from, String external_name) {
        this.heap_from = heap_from;
        this.external_name = external_name;
    }

    public void setSymbolIndex(int ndx) { Assert._assert(ndx != 0); this.symbol_ndx = ndx; }
    
    public void dumpCOFF(DataOutput out) throws IOException {
        Assert._assert(symbol_ndx != 0);
        out.writeInt(heap_from.to32BitValue()); // r_vaddr
        out.writeInt(symbol_ndx);               // r_symndx
        out.writeChar(Reloc.RELOC_ADDR32);      // r_type
    }
    
    public HeapAddress getAddress() { return heap_from; }
    public int getSymbolIndex() { return symbol_ndx; }
    public String getName() { return external_name; }
    
    public void patch() { Assert.UNREACHABLE(); }
    
    public String toString() {
        return "from heap:"+heap_from.stringRep()+" to external:"+external_name+" (symndx "+symbol_ndx+")";
    }
    
}
