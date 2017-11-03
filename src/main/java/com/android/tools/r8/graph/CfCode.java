// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.ApiLevelException;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import java.util.List;
import org.objectweb.asm.MethodVisitor;

public class CfCode extends Code {

  private final int maxStack;
  private final int maxLocals;
  private final List<CfInstruction> instructions;

  public CfCode(int maxStack, int maxLocals, List<CfInstruction> instructions) {
    this.maxStack = maxStack;
    this.maxLocals = maxLocals;
    this.instructions = instructions;
  }

  @Override
  public boolean isCfCode() {
    return true;
  }

  @Override
  public CfCode asCfCode() {
    return this;
  }

  public void write(MethodVisitor visitor) {
    for (CfInstruction instruction : instructions) {
      instruction.write(visitor);
    }
    visitor.visitEnd();
    visitor.visitMaxs(maxStack, maxLocals);
  }

  @Override
  protected int computeHashCode() {
    throw new Unimplemented();
  }

  @Override
  protected boolean computeEquals(Object other) {
    throw new Unimplemented();
  }

  @Override
  public IRCode buildIR(DexEncodedMethod encodedMethod, InternalOptions options)
      throws ApiLevelException {
    throw new Unimplemented("Converting Java class- file bytecode to IR not yet supported");
  }

  @Override
  public void registerReachableDefinitions(UseRegistry registry) {
    throw new Unimplemented("Inspecting Java class-file bytecode not yet supported");
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (CfInstruction instruction : instructions) {
      builder.append(instruction.toString()).append('\n');
    }
    return builder.toString();
  }

  @Override
  public String toString(DexEncodedMethod method, ClassNameMapper naming) {
    return null;
  }
}