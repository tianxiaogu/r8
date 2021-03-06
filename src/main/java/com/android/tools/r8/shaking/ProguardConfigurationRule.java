// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.utils.StringUtils;
import java.util.Set;

public abstract class ProguardConfigurationRule extends ProguardClassSpecification {
  ProguardConfigurationRule(
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      Set<ProguardMemberRule> memberRules) {
    super(classAnnotation, classAccessFlags, negatedClassAccessFlags, classTypeNegated, classType,
        classNames, inheritanceAnnotation, inheritanceClassName, inheritanceIsExtends, memberRules);
  }

  abstract String typeString();

  String modifierString() {
    return null;
  }

  public boolean applyToLibraryClasses() {
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProguardConfigurationRule)) {
      return false;
    }
    ProguardKeepRule that = (ProguardKeepRule) o;
    return super.equals(that);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  protected StringBuilder append(StringBuilder builder) {
    builder.append("-");
    builder.append(typeString());
    StringUtils.appendNonEmpty(builder, ",", modifierString(), null);
    builder.append(' ');
    super.append(builder);
    return builder;
  }

  @Override
  public String toString() {
    return append(new StringBuilder()).toString();
  }
}
