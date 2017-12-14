// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.resolution.shadowing1;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generated by {@link jdk.internal.org.objectweb.asm.util.ASMifier} from the following source: <p>
 * <pre>
 *   public class Main {
 *
 *     public static void main(String... args) {
 *       SubInterface instance = new AClass();
 *       instance.aMethod();
 *     }
 *   }
 * </pre>
 */
public class MainDump implements Opcodes {

  public static byte[] dump() throws Exception {

    ClassWriter cw = new ClassWriter(0);
    FieldVisitor fv;
    MethodVisitor mv;
    AnnotationVisitor av0;

    cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, "Main", null, "java/lang/Object", null);

    {
      mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      mv.visitInsn(RETURN);
      mv.visitMaxs(1, 1);
      mv.visitEnd();
    }
    {
      mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC + ACC_VARARGS, "main", "([Ljava/lang/String;)V",
          null, null);
      mv.visitCode();
      mv.visitTypeInsn(NEW, "AClass");
      mv.visitInsn(DUP);
      mv.visitMethodInsn(INVOKESPECIAL, "AClass", "<init>", "()V", false);
      mv.visitVarInsn(ASTORE, 1);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEINTERFACE, "SubInterface", "aMethod", "()V", true);
      mv.visitInsn(RETURN);
      mv.visitMaxs(2, 2);
      mv.visitEnd();
    }
    cw.visitEnd();

    return cw.toByteArray();
  }
}
