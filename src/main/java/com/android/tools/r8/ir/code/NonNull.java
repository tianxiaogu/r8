// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.Constraint;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.function.Function;

public class NonNull extends Instruction {
  private final static String ERROR_MESSAGE = "This fake IR should be removed after inlining.";

  public NonNull(Value dest, Value src) {
    super(dest, src);
    assert !src.isNeverNull();
    dest.markNeverNull();
  }

  public Value dest() {
    return outValue;
  }

  public Value src() {
    return inValues.get(0);
  }

  @Override
  public boolean isNonNull() {
    return true;
  }

  @Override
  public NonNull asNonNull() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public boolean isOutConstant() {
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    assert other instanceof NonNull;
    return true;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other instanceof NonNull;
    return 0;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    TypeLatticeElement l = getLatticeElement.apply(src());
    if (l.isClassTypeLatticeElement() || l.isArrayTypeLatticeElement()) {
      return l.asNonNullable();
    }
    return l;
  }
}
