// Runtime.java, created Fri Apr  5 18:36:41 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.sun14_win32.java.lang;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: Runtime.java,v 1.5 2003/05/12 10:04:56 joewhaley Exp $
 */
public class Runtime {

    public static native Runtime getRuntime();
    synchronized native void loadLibrary0(Class fromClass, String libname);
}
