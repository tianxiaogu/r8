// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexAnnotationSetRefList;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;

public class AnnotationRemover {

  private final AppInfoWithLiveness appInfo;
  private final ProguardKeepAttributes keep;
  private final InternalOptions options;
  private final ProguardConfiguration.Builder compatibility;

  public AnnotationRemover(AppInfoWithLiveness appInfo,
      ProguardConfiguration.Builder compatibility, InternalOptions options) {
    this.appInfo = appInfo;
    this.keep = options.proguardConfiguration.getKeepAttributes();
    this.compatibility = compatibility;
    this.options = options;
  }

  /**
   * Used to filter annotations on classes, methods and fields.
   */
  private boolean filterAnnotations(DexAnnotation annotation) {
    switch (annotation.visibility) {
      case DexAnnotation.VISIBILITY_SYSTEM:
        DexItemFactory factory = appInfo.dexItemFactory;
        // InnerClass and EnclosingMember are represented in class attributes, not annotations.
        assert !DexAnnotation.isInnerClassAnnotation(annotation, factory);
        assert !DexAnnotation.isMemberClassesAnnotation(annotation, factory);
        assert !DexAnnotation.isEnclosingMethodAnnotation(annotation, factory);
        assert !DexAnnotation.isEnclosingClassAnnotation(annotation, factory);
        if (keep.exceptions && DexAnnotation.isThrowingAnnotation(annotation, factory)) {
          return true;
        }
        if (keep.signature && DexAnnotation.isSignatureAnnotation(annotation, factory)) {
          return true;
        }
        if (keep.sourceDebugExtension
            && DexAnnotation.isSourceDebugExtension(annotation, factory)) {
          return true;
        }
        if (options.canUseParameterNameAnnotations()
            && DexAnnotation.isParameterNameAnnotation(annotation, factory)) {
          return true;
        }
        if (DexAnnotation.isAnnotationDefaultAnnotation(annotation, factory)) {
          // These have to be kept if the corresponding annotation class is kept to retain default
          // values.
          return true;
        }
        return false;
      case DexAnnotation.VISIBILITY_RUNTIME:
        if (!keep.runtimeVisibleAnnotations) {
          return false;
        }
        break;
      case DexAnnotation.VISIBILITY_BUILD:
        if (DexAnnotation.isSynthesizedClassMapAnnotation(annotation, appInfo.dexItemFactory)) {
          // TODO(sgjesse) When should these be removed?
          return true;
        }
        if (!keep.runtimeInvisibleAnnotations) {
          return false;
        }
        break;
      default:
        throw new Unreachable("Unexpected annotation visibility.");
    }
    return isAnnotationTypeLive(annotation);
  }

  private boolean isAnnotationTypeLive(DexAnnotation annotation) {
    DexType annotationType = annotation.annotation.type;
    if (annotationType.isArrayType()) {
      annotationType = annotationType.toBaseType(appInfo.dexItemFactory);
    }
    DexClass definition = appInfo.definitionFor(annotationType);
    return definition == null || definition.isLibraryClass()
        || appInfo.liveTypes.contains(annotationType);
  }

  /**
   * Used to filter annotations on parameters.
   */
  private boolean filterParameterAnnotations(DexAnnotation annotation) {
    switch (annotation.visibility) {
      case DexAnnotation.VISIBILITY_SYSTEM:
        return false;
      case DexAnnotation.VISIBILITY_RUNTIME:
        if (!keep.runtimeVisibleParameterAnnotations) {
          return false;
        }
        break;
      case DexAnnotation.VISIBILITY_BUILD:
        if (!keep.runtimeInvisibleParameterAnnotations) {
          return false;
        }
        break;
      default:
        throw new Unreachable("Unexpected annotation visibility.");
    }
    return isAnnotationTypeLive(annotation);
  }

  public void run() {
    keep.ensureValid(options.forceProguardCompatibility, compatibility);
    for (DexProgramClass clazz : appInfo.classes()) {
      stripAttributes(clazz);
      clazz.annotations = stripAnnotations(clazz.annotations, this::filterAnnotations);
      clazz.forEachMethod(this::processMethod);
      clazz.forEachField(this::processField);
    }
  }

  private void processMethod(DexEncodedMethod method) {
    method.annotations = stripAnnotations(method.annotations, this::filterAnnotations);
    method.parameterAnnotations = stripAnnotations(method.parameterAnnotations,
        this::filterParameterAnnotations);
  }

  private void processField(DexEncodedField field) {
      field.annotations = stripAnnotations(field.annotations, this::filterAnnotations);
  }

  private void stripAttributes(DexProgramClass clazz) {
    if (!keep.enclosingMethod && clazz.getEnclosingMethod() != null) {
      clazz.clearEnclosingMethod();
    }
    if (!keep.innerClasses && !clazz.getInnerClasses().isEmpty()) {
      clazz.clearInnerClasses();
    }
  }

  private DexAnnotationSetRefList stripAnnotations(DexAnnotationSetRefList annotations,
      Predicate<DexAnnotation> filter) {
    DexAnnotationSet[] filtered = null;
    for (int i = 0; i < annotations.values.length; i++) {
      DexAnnotationSet updated = stripAnnotations(annotations.values[i], filter);
      if (updated != annotations.values[i]) {
        if (filtered == null) {
          filtered = annotations.values.clone();
          filtered[i] = updated;
        }
      }
    }
    if (filtered == null) {
      return annotations;
    } else {
      if (Arrays.stream(filtered).allMatch(DexAnnotationSet::isEmpty)) {
        return DexAnnotationSetRefList.empty();
      }
      return new DexAnnotationSetRefList(filtered);
    }
  }

  private DexAnnotationSet stripAnnotations(DexAnnotationSet annotations,
      Predicate<DexAnnotation> filter) {
    ArrayList<DexAnnotation> filtered = null;
    for (int i = 0; i < annotations.annotations.length; i++) {
      DexAnnotation annotation = annotations.annotations[i];
      if (filter.test(annotation)) {
        if (filtered != null) {
          filtered.add(annotation);
        }
      } else {
        if (filtered == null) {
          filtered = new ArrayList<>(annotations.annotations.length);
          for (int j = 0; j < i; j++) {
            filtered.add(annotations.annotations[j]);
          }
        }
      }
    }
    if (filtered == null) {
      return annotations;
    } else if (filtered.isEmpty()) {
      return DexAnnotationSet.empty();
    } else {
      return new DexAnnotationSet(filtered.toArray(new DexAnnotation[filtered.size()]));
    }
  }
}
