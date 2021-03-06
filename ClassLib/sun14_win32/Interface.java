// Interface.java, created Fri Apr  5 18:36:41 2002 by joewhaley
// Copyright (C) 2001-3 John Whaley <jwhaley@alum.mit.edu>
// Licensed under the terms of the GNU LGPL; see COPYING for details.
package ClassLib.sun14_win32;

import java.util.Iterator;

import Bootstrap.ObjectTraverser;
import Bootstrap.PrimordialClassLoader;
import ClassLib.ClassLibInterface;
import Clazz.jq_Class;
import Scheduler.jq_NativeThread;

/*
 * @author  John Whaley <jwhaley@alum.mit.edu>
 * @version $Id: Interface.java,v 1.24 2003/07/05 11:03:46 joewhaley Exp $
 */
public class Interface extends ClassLib.Common.InterfaceImpl {

    /** Creates new Interface */
    public Interface() {}

    public Iterator getImplementationClassDescs(UTF.Utf8 desc) {
        if (ClassLibInterface.USE_JOEQ_CLASSLIB && desc.toString().startsWith("Ljava/")) {
            UTF.Utf8 u = UTF.Utf8.get("LClassLib/sun14_win32/"+desc.toString().substring(1));
            return new Util.Collections.AppendIterator(super.getImplementationClassDescs(desc),
                                            java.util.Collections.singleton(u).iterator());
        }
        return super.getImplementationClassDescs(desc);
    }
    
    public ObjectTraverser getObjectTraverser() {
        return sun14_win32ObjectTraverser.INSTANCE;
    }
    
    public static class sun14_win32ObjectTraverser extends CommonObjectTraverser {
        public static sun14_win32ObjectTraverser INSTANCE = new sun14_win32ObjectTraverser();
        protected sun14_win32ObjectTraverser() {}
        public void initialize() {
            super.initialize();
            
            jq_Class k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Lsun/misc/Unsafe;");
            nullStaticFields.add(k.getOrCreateStaticField("theUnsafe", "Lsun/misc/Unsafe;"));
            k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Lsun/reflect/UnsafeFieldAccessorImpl;");
            nullStaticFields.add(k.getOrCreateStaticField("unsafe", "Lsun/misc/Unsafe;"));
            k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Ljava/nio/charset/Charset;");
            nullStaticFields.add(k.getOrCreateStaticField("cache", "[Ljava/lang/Object;"));
            
            k = PrimordialClassLoader.getJavaLangClass();
            nullInstanceFields.add(k.getOrCreateInstanceField("declaredFields", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("publicFields", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("declaredMethods", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("publicMethods", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("declaredConstructors", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("publicConstructors", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("declaredPublicFields", "Ljava/lang/ref/SoftReference;"));
            nullInstanceFields.add(k.getOrCreateInstanceField("declaredPublicMethods", "Ljava/lang/ref/SoftReference;"));
            k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Ljava/lang/reflect/Field;");
            nullInstanceFields.add(k.getOrCreateInstanceField("fieldAccessor", "Lsun/reflect/FieldAccessor;"));
            k = (jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Ljava/lang/reflect/Method;");
            nullInstanceFields.add(k.getOrCreateInstanceField("methodAccessor", "Lsun/reflect/MethodAccessor;"));
            k = (Clazz.jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Ljava/lang/reflect/Constructor;");
            nullInstanceFields.add(k.getOrCreateInstanceField("constructorAccessor", "Lsun/reflect/ConstructorAccessor;"));
            
            if (IGNORE_THREAD_LOCALS) {
                k = (Clazz.jq_Class)PrimordialClassLoader.loader.getOrCreateBSType("Ljava/lang/Thread;");
                nullInstanceFields.add(k.getOrCreateInstanceField("threadLocals", "Ljava/lang/ThreadLocal$ThreadLocalMap;"));
                nullInstanceFields.add(k.getOrCreateInstanceField("inheritableThreadLocals", "Ljava/lang/ThreadLocal$ThreadLocalMap;"));
            }
            
            jq_NativeThread.USE_INTERRUPTER_THREAD = true;
        }
    }
}
