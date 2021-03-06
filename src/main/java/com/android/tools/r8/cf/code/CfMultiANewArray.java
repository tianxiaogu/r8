// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.cf.CfPrinter;
import com.android.tools.r8.graph.DexType;
import org.objectweb.asm.MethodVisitor;

public class CfMultiANewArray extends CfInstruction {

  private final DexType type;
  private final int dimensions;

  public CfMultiANewArray(DexType type, int dimensions) {
    this.type = type;
    this.dimensions = dimensions;
  }

  public DexType getType() {
    return type;
  }

  public int getDimensions() {
    return dimensions;
  }

  @Override
  public void write(MethodVisitor visitor) {
    visitor.visitMultiANewArrayInsn(type.getInternalName(), dimensions);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }
}
