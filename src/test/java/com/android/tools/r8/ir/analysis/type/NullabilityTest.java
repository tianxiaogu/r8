// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.analysis.type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.AsmTestBase;
import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.Argument;
import com.android.tools.r8.ir.code.ArrayGet;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.NonNull;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.nonnull.NonNullAfterArrayAccess;
import com.android.tools.r8.ir.nonnull.NonNullAfterFieldAccess;
import com.android.tools.r8.ir.nonnull.NonNullAfterInvoke;
import com.android.tools.r8.ir.optimize.NonNullMarker;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DexInspector;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.Test;

public class NullabilityTest extends AsmTestBase {
  private static final InternalOptions TEST_OPTIONS = new InternalOptions();

  private void buildAndTest(
      Class<?> mainClass,
      MethodSignature signature,
      boolean npeCaught,
      BiConsumer<AppInfo, TypeAnalysis> inspector)
      throws Exception {
    AndroidApp app = buildAndroidApp(asBytes(mainClass));
    DexApplication dexApplication =
        new ApplicationReader(app, TEST_OPTIONS, new Timing("NullabilityTest.appReader"))
            .read().toDirect();
    AppInfo appInfo = new AppInfo(dexApplication);
    DexInspector dexInspector = new DexInspector(appInfo.app);
    DexEncodedMethod foo = dexInspector.clazz(mainClass.getName()).method(signature).getMethod();
    IRCode irCode = foo.buildIR(TEST_OPTIONS);
    NonNullMarker nonNullMarker = new NonNullMarker();
    nonNullMarker.addNonNull(irCode);
    TypeAnalysis analysis = new TypeAnalysis(appInfo, foo, irCode);
    inspector.accept(appInfo, analysis);
    verifyLastInvoke(irCode, analysis, npeCaught);
  }

  private static void verifyClassTypeLattice(
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices,
      DexType receiverType,
      Value v,
      TypeLatticeElement l) {
    // Due to the last invocation that will check nullability of the argument,
    // there is one exceptional mapping to PRIMITIVE.
    if (l.isPrimitive()) {
      return;
    }
    assertTrue(l.isClassTypeLatticeElement());
    ClassTypeLatticeElement lattice = l.asClassTypeLatticeElement();
    // Receiver
    if (lattice.getClassType().equals(receiverType)) {
      assertFalse(l.isNullable());
    } else {
      Instruction definition = v.definition;
      if (definition != null) {
        TypeLatticeElement expected = expectedLattices.get(v.definition.getClass());
        if (expected != null) {
          assertEquals(expected, l);
        }
      }
    }
  }

  private void verifyLastInvoke(IRCode code, TypeAnalysis analysis, boolean npeCaught) {
    InstructionIterator it = code.instructionIterator();
    boolean metInvokeVirtual = false;
    while (it.hasNext()) {
      Instruction instruction = it.next();
      if (instruction.isInvokeMethodWithReceiver()) {
        InvokeMethodWithReceiver invoke = instruction.asInvokeMethodWithReceiver();
        if (invoke.getInvokedMethod().name.toString().contains("hash")) {
          metInvokeVirtual = true;
          TypeLatticeElement l = analysis.getLatticeElement(invoke.getReceiver());
          assertEquals(npeCaught, l.isNullable());
        }
      }
    }
    assertTrue(metInvokeVirtual);
  }

  @Test
  public void nonNullAfterSafeInvokes() throws Exception {
    MethodSignature signature =
        new MethodSignature("foo", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterInvoke.class, signature, false, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterInvoke;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          InvokeVirtual.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NonNull.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, false),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> verifyClassTypeLattice(expectedLattices, mainClass, v, l));
    });
  }

  @Test
  public void stillNullAfterExceptionCatch_invoke() throws Exception {
    MethodSignature signature =
        new MethodSignature("bar", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterInvoke.class, signature, true, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterInvoke;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          InvokeVirtual.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NonNull.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, false),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> verifyClassTypeLattice(expectedLattices, mainClass, v, l));
    });
  }

  @Test
  public void nonNullAfterSafeArrayAccess() throws Exception {
    MethodSignature signature =
        new MethodSignature("foo", "int", new String[]{"java.lang.String[]"});
    buildAndTest(NonNullAfterArrayAccess.class, signature, false, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterArrayAccess;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          // An element inside a non-null array could be null.
          ArrayGet.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> {
        if (l.isArrayTypeLatticeElement()) {
          ArrayTypeLatticeElement lattice = l.asArrayTypeLatticeElement();
          assertEquals(
              appInfo.dexItemFactory.stringType,
              lattice.getArrayElementType(appInfo.dexItemFactory));
          assertEquals(v.definition.isArgument(), l.isNullable());
        } else if (l.isClassTypeLatticeElement()) {
          verifyClassTypeLattice(expectedLattices, mainClass, v, l);
        }
      });
    });
  }

  @Test
  public void stillNullAfterExceptionCatch_aget() throws Exception {
    MethodSignature signature =
        new MethodSignature("bar", "int", new String[]{"java.lang.String[]"});
    buildAndTest(NonNullAfterArrayAccess.class, signature, true, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterArrayAccess;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          // An element inside a non-null array could be null.
          ArrayGet.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> {
        if (l.isArrayTypeLatticeElement()) {
          ArrayTypeLatticeElement lattice = l.asArrayTypeLatticeElement();
          assertEquals(
              appInfo.dexItemFactory.stringType,
              lattice.getArrayElementType(appInfo.dexItemFactory));
          assertEquals(v.definition.isArgument(), l.isNullable());
        } else if (l.isClassTypeLatticeElement()) {
          verifyClassTypeLattice(expectedLattices, mainClass, v, l);
        }
      });
    });
  }

  @Test
  public void nonNullAfterSafeFieldAccess() throws Exception {
    MethodSignature signature = new MethodSignature("foo", "int",
        new String[]{"com.android.tools.r8.ir.nonnull.FieldAccessTest"});
    buildAndTest(NonNullAfterFieldAccess.class, signature, false, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterFieldAccess;");
      DexType testClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/FieldAccessTest;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          Argument.class, new ClassTypeLatticeElement(testClass, true),
          NonNull.class, new ClassTypeLatticeElement(testClass, false),
          // instance may not be initialized.
          InstanceGet.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> verifyClassTypeLattice(expectedLattices, mainClass, v, l));
    });
  }

  @Test
  public void stillNullAfterExceptionCatch_iget() throws Exception {
    MethodSignature signature = new MethodSignature("bar", "int",
        new String[]{"com.android.tools.r8.ir.nonnull.FieldAccessTest"});
    buildAndTest(NonNullAfterFieldAccess.class, signature, true, (appInfo, typeAnalysis) -> {
      DexType assertionErrorType = appInfo.dexItemFactory.createType("Ljava/lang/AssertionError;");
      DexType mainClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/NonNullAfterFieldAccess;");
      DexType testClass = appInfo.dexItemFactory.createType(
          "Lcom/android/tools/r8/ir/nonnull/FieldAccessTest;");
      Map<Class<? extends Instruction>, TypeLatticeElement> expectedLattices = ImmutableMap.of(
          Argument.class, new ClassTypeLatticeElement(testClass, true),
          NonNull.class, new ClassTypeLatticeElement(testClass, false),
          // instance may not be initialized.
          InstanceGet.class, new ClassTypeLatticeElement(appInfo.dexItemFactory.stringType, true),
          NewInstance.class, new ClassTypeLatticeElement(assertionErrorType, false));
      typeAnalysis.forEach((v, l) -> verifyClassTypeLattice(expectedLattices, mainClass, v, l));
    });
  }
}
