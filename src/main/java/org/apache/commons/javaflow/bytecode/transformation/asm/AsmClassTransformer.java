/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.commons.javaflow.bytecode.transformation.asm;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

import java.lang.reflect.Field;

import org.apache.commons.javaflow.bytecode.Continuable;
import org.apache.commons.javaflow.bytecode.transformation.ResourceTransformer;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.EmptyVisitor;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

public final class AsmClassTransformer implements ResourceTransformer {
    private ClassLoader classLoader = AsmClassTransformer.class.getClassLoader();

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public byte[] transform(final InputStream is) throws IOException {
        return transform(new ClassReader(is));
    }

    @Override
    public byte[] transform(final byte[] original) {
        return transform(new ClassReader(original));
    }

    public boolean isTransformed(byte[] bytecode) {
        ClassReader reader = new ClassReader(bytecode);
        AlreadyTransformedChecker checker = new AlreadyTransformedChecker(new EmptyVisitor());
        reader.accept(checker, 0);
        return checker.transformed;
    }

    private static class AlreadyTransformedChecker extends ClassAdapter {
        private static String continuableClassName = Continuable.class.getName().replace('.', '/');
        private boolean transformed;

        public AlreadyTransformedChecker(ClassVisitor cv) {
            super(cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (iface.equals(continuableClassName)) {
                        transformed = true;
                        break;
                    }
                }
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }
    }

    private byte[] transform(final ClassReader cr) {
        ExtendedClassWriter cw = new ExtendedClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        // print bytecode before transformation
        // cr.accept(new TraceClassVisitor(new ContinuationClassAdapter(this, cw), new PrintWriter(System.out)), false);

        cr.accept(new ContinuationClassAdapter(decorateClassVisitor(
                cw, true, null/* System.err */), classLoader), 0);

        final byte[] bytecode = cw.toByteArray();

        // CheckClassAdapter.verify(new ClassReader(bytecode), true);
        // new ClassReader(bytecode).accept(new ASMifierClassVisitor(new PrintWriter(System.err)), false);
        return bytecode;
    }

    private ClassVisitor decorateClassVisitor(ClassVisitor visitor, final boolean check, final PrintStream dumpStream) {
        if (check) {
            visitor = new CheckClassAdapter(visitor);
            if (null != CHECK_DATA_FLOW) {
                try {
                    // Currently CheckMethodAdapter throws error, so suppress flow checks
                    CHECK_DATA_FLOW.set(visitor, Boolean.FALSE);
                } catch (final IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

        if (null != dumpStream) {
            visitor = new TraceClassVisitor(visitor, new PrintWriter(dumpStream));
        }

        return visitor;
    }

    final private static Field CHECK_DATA_FLOW;

    static {
        Field checkDataFlow = null;
        try {
            checkDataFlow = CheckClassAdapter.class.getDeclaredField("checkDataFlow");
            checkDataFlow.setAccessible(true);
        } catch (final NoSuchFieldException ex) {
            // Normal, the field is available only since ASM 3.2
        }

        CHECK_DATA_FLOW = checkDataFlow;
    }

    private class ExtendedClassWriter extends ClassWriter {
        public ExtendedClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            Class<?> c, d;
            try {
                c = Class.forName(type1.replace('/', '.'), true, classLoader);
                d = Class.forName(type2.replace('/', '.'), true, classLoader);
            } catch (Exception e) {
                throw new RuntimeException(e.toString());
            }
            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            } else {
                do {
                    c = c.getSuperclass();
                } while (!c.isAssignableFrom(d));
                return c.getName().replace('.', '/');
            }
        }
    }
}

