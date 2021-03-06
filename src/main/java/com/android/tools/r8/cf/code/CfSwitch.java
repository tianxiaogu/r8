// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import com.android.tools.r8.cf.CfPrinter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class CfSwitch extends CfInstruction {

  public enum Kind { LOOKUP, TABLE }

  private final Kind kind;
  private final CfLabel defaultTarget;
  private final int[] keys;
  private final List<CfLabel> targets;

  public CfSwitch(CfLabel defaultTarget, int[] keys, List<CfLabel> targets) {
    // TODO(zerny): Support emitting table switches.
    this.kind = Kind.LOOKUP;
    this.defaultTarget = defaultTarget;
    this.keys = keys;
    this.targets = targets;
  }

  public Kind getKind() {
    return kind;
  }

  public CfLabel getDefaultTarget() {
    return defaultTarget;
  }

  public IntList getKeys() {
    return new IntArrayList(keys);
  }

  public List<CfLabel> getTargets() {
    return targets;
  }

  @Override
  public void write(MethodVisitor visitor) {
    Label[] labels = new Label[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      labels[i] = targets.get(i).getLabel();
    }
    visitor.visitLookupSwitchInsn(defaultTarget.getLabel(), keys, labels);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }
}
