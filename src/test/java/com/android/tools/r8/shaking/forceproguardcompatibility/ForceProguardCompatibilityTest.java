// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking.forceproguardcompatibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.CompatProguardCommandBuilder;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.invokesuper.Consumer;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.ProguardClassNameList;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ProguardConfigurationParser;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.android.tools.r8.shaking.ProguardKeepAttributes;
import com.android.tools.r8.shaking.ProguardMemberRule;
import com.android.tools.r8.shaking.ProguardMemberType;
import com.android.tools.r8.shaking.forceproguardcompatibility.defaultmethods.ClassImplementingInterface;
import com.android.tools.r8.shaking.forceproguardcompatibility.defaultmethods.InterfaceWithDefaultMethods;
import com.android.tools.r8.shaking.forceproguardcompatibility.defaultmethods.TestClass;
import com.android.tools.r8.shaking.forceproguardcompatibility.keepattributes.TestKeepAttributes;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DefaultDiagnosticsHandler;
import com.android.tools.r8.utils.DexInspector;
import com.android.tools.r8.utils.DexInspector.ClassSubject;
import com.android.tools.r8.utils.DexInspector.MethodSubject;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ForceProguardCompatibilityTest extends TestBase {
  // Actually running Proguard should only be during development.
  private final boolean RUN_PROGUARD = false;

  private void test(Class mainClass, Class mentionedClass, boolean forceProguardCompatibility)
      throws Exception {
    String proguardConfig = keepMainProguardConfiguration(mainClass, true, false);
    DexInspector inspector = new DexInspector(
        compileWithR8(
            ImmutableList.of(mainClass, mentionedClass),
            proguardConfig,
            options -> options.forceProguardCompatibility = forceProguardCompatibility));
    assertTrue(inspector.clazz(mainClass.getCanonicalName()).isPresent());
    ClassSubject clazz = inspector.clazz(getJavacGeneratedClassName(mentionedClass));
    assertTrue(clazz.isPresent());
    MethodSubject defaultInitializer = clazz.method(MethodSignature.initializer(new String[]{}));
    assertEquals(forceProguardCompatibility, defaultInitializer.isPresent());
  }

  @Test
  public void testKeepDefaultInitializer() throws Exception {
    test(TestMain.class, TestMain.MentionedClass.class, true);
    test(TestMain.class, TestMain.MentionedClass.class, false);
  }

  @Test
  public void testKeepDefaultInitializerArrayType() throws Exception {
    test(TestMainArrayType.class, TestMainArrayType.MentionedClass.class, true);
    test(TestMainArrayType.class, TestMainArrayType.MentionedClass.class, false);
  }

  private void runAnnotationsTest(boolean forceProguardCompatibility, boolean keepAnnotations)
      throws Exception {
    R8Command.Builder builder = new CompatProguardCommandBuilder(forceProguardCompatibility);
    // Add application classes including the annotation class.
    Class mainClass = TestMain.class;
    Class mentionedClassWithAnnotations = TestMain.MentionedClassWithAnnotation.class;
    Class annotationClass = TestAnnotation.class;
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(mainClass));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(TestMain.MentionedClass.class));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(mentionedClassWithAnnotations));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(annotationClass));
    // Keep main class and the annotation class.
    builder.addProguardConfiguration(
        ImmutableList.of(keepMainProguardConfiguration(mainClass, true, false)), Origin.unknown());
    builder.addProguardConfiguration(
        ImmutableList.of("-keep class " + annotationClass.getCanonicalName() + " { }"),
        Origin.unknown());
    if (keepAnnotations) {
      builder.addProguardConfiguration(ImmutableList.of("-keepattributes *Annotation*"),
          Origin.unknown());
    }

    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    DexInspector inspector = new DexInspector(ToolHelper.runR8(builder.build()));
    assertTrue(inspector.clazz(mainClass.getCanonicalName()).isPresent());
    ClassSubject clazz = inspector.clazz(getJavacGeneratedClassName(mentionedClassWithAnnotations));
    assertTrue(clazz.isPresent());

    // The test contains only a member class so the enclosing-method attribute will be null.
    assertEquals(
        !keepAnnotations && forceProguardCompatibility,
        !clazz.getDexClass().getInnerClasses().isEmpty());
    assertEquals(forceProguardCompatibility || keepAnnotations,
        clazz.annotation(annotationClass.getCanonicalName()).isPresent());
  }

  @Test
  public void testAnnotations() throws Exception {
    runAnnotationsTest(true, true);
    runAnnotationsTest(true, false);
    runAnnotationsTest(false, true);
    runAnnotationsTest(false, false);
  }

  private void runDefaultConstructorTest(boolean forceProguardCompatibility,
      Class<?> testClass, boolean hasDefaultConstructor) throws Exception {
    CompatProguardCommandBuilder builder =
        new CompatProguardCommandBuilder(forceProguardCompatibility);
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(testClass));
    List<String> proguardConfig = ImmutableList.of(
        "-keep class " + testClass.getCanonicalName() + " {",
        "  public void method();",
        "}");
    builder.addProguardConfiguration(proguardConfig, Origin.unknown());
    Path proguardCompatibilityRules = temp.newFile().toPath();
    builder.setProguardCompatibilityRulesOutput(proguardCompatibilityRules);

    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    DexInspector inspector = new DexInspector(ToolHelper.runR8(builder.build()));
    ClassSubject clazz = inspector.clazz(getJavacGeneratedClassName(testClass));
    assertTrue(clazz.isPresent());
    assertEquals(forceProguardCompatibility && hasDefaultConstructor,
        clazz.init(ImmutableList.of()).isPresent());

    // Check the Proguard compatibility rules generated.
    ProguardConfigurationParser parser =
        new ProguardConfigurationParser(new DexItemFactory(),
            new Reporter(new DefaultDiagnosticsHandler()));
    parser.parse(proguardCompatibilityRules);
    ProguardConfiguration configuration = parser.getConfigRawForTesting();
    if (forceProguardCompatibility && hasDefaultConstructor) {
      assertEquals(1, configuration.getRules().size());
      ProguardClassNameList classNames = configuration.getRules().get(0).getClassNames();
      assertEquals(1, classNames.size());
      assertEquals(testClass.getCanonicalName(),
          classNames.asSpecificDexTypes().get(0).toSourceString());
      Set<ProguardMemberRule> memberRules = configuration.getRules().get(0).getMemberRules();
      assertEquals(1, memberRules.size());
      assertEquals(ProguardMemberType.INIT, memberRules.iterator().next().getRuleType());
    } else {
      assertEquals(0, configuration.getRules().size());
    }

    if (RUN_PROGUARD) {
      Path proguardedJar = File.createTempFile("proguarded", ".jar", temp.getRoot()).toPath();
      Path proguardConfigFile = File.createTempFile("proguard", ".config", temp.getRoot()).toPath();
      FileUtils.writeTextFile(proguardConfigFile, proguardConfig);
      ToolHelper.runProguard(jarTestClasses(testClass), proguardedJar, proguardConfigFile);
    }
  }

  @Test
  public void testDefaultConstructor() throws Exception {
    runDefaultConstructorTest(true, TestClassWithDefaultConstructor.class, true);
    runDefaultConstructorTest(true, TestClassWithoutDefaultConstructor.class, false);
    runDefaultConstructorTest(false, TestClassWithDefaultConstructor.class, true);
    runDefaultConstructorTest(false, TestClassWithoutDefaultConstructor.class, false);
  }

  public void testCheckCast(boolean forceProguardCompatibility, Class mainClass,
      Class instantiatedClass, boolean containsCheckCast)
      throws Exception {
    R8Command.Builder builder = new CompatProguardCommandBuilder(forceProguardCompatibility);
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(mainClass));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(instantiatedClass));
    List<String> proguardConfig = ImmutableList.of(
        "-keep class " + mainClass.getCanonicalName() + " {",
        "  public static void main(java.lang.String[]);",
        "}",
        "-dontobfuscate");
    builder.addProguardConfiguration(proguardConfig, Origin.unknown());

    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    DexInspector inspector = new DexInspector(ToolHelper.runR8(builder.build()));
    assertTrue(inspector.clazz(getJavacGeneratedClassName(mainClass)).isPresent());
    ClassSubject clazz = inspector.clazz(getJavacGeneratedClassName(instantiatedClass));
    assertEquals(containsCheckCast, clazz.isPresent());
    assertEquals(containsCheckCast, clazz.isPresent());
    if (clazz.isPresent()) {
      assertEquals(forceProguardCompatibility && containsCheckCast, !clazz.isAbstract());
    }

    if (RUN_PROGUARD) {
      Path proguardedJar = File.createTempFile("proguarded", ".jar", temp.getRoot()).toPath();
      Path proguardConfigFile = File.createTempFile("proguard", ".config", temp.getRoot()).toPath();
      FileUtils.writeTextFile(proguardConfigFile, proguardConfig);
      ToolHelper.runProguard(jarTestClasses(ImmutableList.of(mainClass, instantiatedClass)),
          proguardedJar, proguardConfigFile);
      Set<String> classesAfterProguard = readClassesInJar(proguardedJar);
      assertTrue(classesAfterProguard.contains(mainClass.getCanonicalName()));
      assertEquals(
          containsCheckCast, classesAfterProguard.contains(instantiatedClass.getCanonicalName()));
    }
  }

  @Test
  public void checkCastTest() throws Exception {
    testCheckCast(true, TestMainWithCheckCast.class, TestClassWithDefaultConstructor.class, true);
    testCheckCast(
        true, TestMainWithoutCheckCast.class, TestClassWithDefaultConstructor.class, false);
    testCheckCast(
        false, TestMainWithCheckCast.class, TestClassWithDefaultConstructor.class, true);
    testCheckCast(
        false, TestMainWithoutCheckCast.class, TestClassWithDefaultConstructor.class, false);
  }

  public void testClassForName(
      boolean forceProguardCompatibility, boolean allowObfuscation) throws Exception {
    CompatProguardCommandBuilder builder =
        new CompatProguardCommandBuilder(forceProguardCompatibility);
    Class mainClass = TestMainWithClassForName.class;
    Class forNameClass1 = TestClassWithDefaultConstructor.class;
    Class forNameClass2 = TestClassWithoutDefaultConstructor.class;
    List<Class> forNameClasses = ImmutableList.of(forNameClass1, forNameClass2);
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(mainClass));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(forNameClass1));
    builder.addProgramFiles(ToolHelper.getClassFileForTestClass(forNameClass2));
    ImmutableList.Builder<String> proguardConfigurationBuilder = ImmutableList.builder();
    proguardConfigurationBuilder.add(
        "-keep class " + mainClass.getCanonicalName() + " {",
        "  <init>();",  // Add <init>() so it does not become a compatibility rule below.
        "  public static void main(java.lang.String[]);",
        "}");
    if (!allowObfuscation) {
      proguardConfigurationBuilder.add("-dontobfuscate");
    }
    List<String> proguardConfig = proguardConfigurationBuilder.build();
    builder.addProguardConfiguration(proguardConfig, Origin.unknown());
    Path proguardCompatibilityRules = temp.newFile().toPath();
    builder.setProguardCompatibilityRulesOutput(proguardCompatibilityRules);
    if (allowObfuscation) {
      builder.setProguardMapOutputPath(temp.newFile().toPath());
    }

    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    DexInspector inspector = new DexInspector(ToolHelper.runR8(builder.build()));
    assertTrue(inspector.clazz(getJavacGeneratedClassName(mainClass)).isPresent());
    forNameClasses.forEach(clazz -> {
      ClassSubject subject = inspector.clazz(getJavacGeneratedClassName(clazz));
      assertEquals(forceProguardCompatibility, subject.isPresent());
      assertEquals(subject.isPresent() && allowObfuscation, subject.isRenamed());
    });

    // Check the Proguard compatibility rules generated.
    ProguardConfigurationParser parser =
        new ProguardConfigurationParser(new DexItemFactory(),
            new Reporter(new DefaultDiagnosticsHandler()));
    parser.parse(proguardCompatibilityRules);
    ProguardConfiguration configuration = parser.getConfigRawForTesting();
    if (forceProguardCompatibility) {
      assertEquals(2, configuration.getRules().size());
      configuration.getRules().forEach(rule -> {
        Set<ProguardMemberRule> memberRules = rule.getMemberRules();
        ProguardClassNameList classNames = rule.getClassNames();
        assertEquals(1, classNames.size());
        DexType type = classNames.asSpecificDexTypes().get(0);
        if (type.toSourceString().equals(forNameClass1.getCanonicalName())) {
          assertEquals(1, memberRules.size());
          assertEquals(ProguardMemberType.INIT, memberRules.iterator().next().getRuleType());
        } else {
          assertTrue(type.toSourceString().equals(forNameClass2.getCanonicalName()));
          // During parsing we add in the default constructor if there are otherwise no single
          // member rule.
          assertEquals(1, memberRules.size());
          assertEquals(ProguardMemberType.INIT, memberRules.iterator().next().getRuleType());
        }
      });
    } else {
      assertEquals(0, configuration.getRules().size());
    }

    if (RUN_PROGUARD) {
      Path proguardedJar = File.createTempFile("proguarded", ".jar", temp.getRoot()).toPath();
      Path proguardConfigFile = File.createTempFile("proguard", ".config", temp.getRoot()).toPath();
      FileUtils.writeTextFile(proguardConfigFile, proguardConfig);
      ToolHelper.runProguard(jarTestClasses(
          ImmutableList.of(mainClass, forNameClass1, forNameClass2)),
          proguardedJar, proguardConfigFile);
      Set<String> classesAfterProguard = readClassesInJar(proguardedJar);
      assertTrue(classesAfterProguard.contains(mainClass.getCanonicalName()));
      assertTrue(classesAfterProguard.contains(forNameClass1.getCanonicalName()));
      assertTrue(classesAfterProguard.contains(forNameClass2.getCanonicalName()));
    }
  }

  @Test
  public void classForNameTest() throws Exception {
    testClassForName(true, false);
    testClassForName(false, false);
    testClassForName(true, true);
    testClassForName(false, true);
  }

  public void testKeepAttributes(boolean forceProguardCompatibility,
      boolean innerClasses, boolean enclosingMethod) throws Exception {
    CompatProguardCommandBuilder builder =
        new CompatProguardCommandBuilder(forceProguardCompatibility);
    Class mainClass = TestKeepAttributes.class;
    builder.addProgramFiles(ToolHelper.getClassFilesForTestPackage(mainClass.getPackage()));
    ImmutableList.Builder<String> proguardConfigurationBuilder = ImmutableList.builder();
    String keepAttributes = "";
    if (innerClasses || enclosingMethod) {
      List<String> attributes = new ArrayList<>();
      if (innerClasses) {
        attributes.add(ProguardKeepAttributes.INNER_CLASSES);
      }
      if (enclosingMethod) {
        attributes.add(ProguardKeepAttributes.ENCLOSING_METHOD);
      }
      keepAttributes = "-keepattributes " + String.join(",", attributes);
    }
    proguardConfigurationBuilder.add(
        "-keep class " + mainClass.getCanonicalName() + " {",
        "  <init>();",  // Add <init>() so it does not become a compatibility rule below.
        "  public static void main(java.lang.String[]);",
        "}",
        keepAttributes);
    List<String> proguardConfig = proguardConfigurationBuilder.build();
    builder.addProguardConfiguration(proguardConfig, Origin.unknown());
    Path proguardCompatibilityRules = temp.newFile().toPath();
    builder.setProguardCompatibilityRulesOutput(proguardCompatibilityRules);

    AndroidApp app;
    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    try {
      app = ToolHelper.runR8(builder.build());
    } catch (CompilationError e) {
      assertTrue(!forceProguardCompatibility && (!innerClasses || !enclosingMethod));
      return;
    }
    DexInspector inspector = new DexInspector(app);
    assertTrue(inspector.clazz(getJavacGeneratedClassName(mainClass)).isPresent());
    assertEquals(innerClasses || enclosingMethod ? "1" : "0", runOnArt(app, mainClass));

    // Check the Proguard compatibility configuration generated.
    ProguardConfigurationParser parser =
        new ProguardConfigurationParser(new DexItemFactory(),
            new Reporter(new DefaultDiagnosticsHandler()));
    parser.parse(proguardCompatibilityRules);
    System.out.println(proguardCompatibilityRules);
    ProguardConfiguration configuration = parser.getConfigRawForTesting();
    assertEquals(0, configuration.getRules().size());
    if (innerClasses ^ enclosingMethod) {
      assertTrue(configuration.getKeepAttributes().innerClasses);
      assertTrue(configuration.getKeepAttributes().enclosingMethod);
    } else {
      assertFalse(configuration.getKeepAttributes().innerClasses);
      assertFalse(configuration.getKeepAttributes().enclosingMethod);
    }
  }

  @Test
  public void keepAttributesTest() throws Exception {
    testKeepAttributes(true, false, false);
    testKeepAttributes(true, true, false);
    testKeepAttributes(true, false, true);
    testKeepAttributes(true, true, true);
    testKeepAttributes(false, false, false);
    testKeepAttributes(false, true, false);
    testKeepAttributes(false, false, true);
    testKeepAttributes(false, true, true);
  }

  private void runKeepDefaultMethodsTest(
      List<String> additionalKeepRules,
      Consumer<DexInspector> inspection,
      Consumer<ProguardConfiguration> compatInspection) throws Exception {
    Class mainClass = TestClass.class;
    CompatProguardCommandBuilder builder = new CompatProguardCommandBuilder();
    builder.addProgramFiles(ToolHelper.getClassFilesForTestPackage(mainClass.getPackage()));
    builder.addProguardConfiguration(ImmutableList.of(
        "-keep class " + mainClass.getCanonicalName() + "{",
        "  public <init>();",
        "  public static void main(java.lang.String[]);",
        "}",
        "-dontobfuscate"),
        Origin.unknown());
    builder.addProguardConfiguration(additionalKeepRules, Origin.unknown());
    builder.setProgramConsumer(DexIndexedConsumer.emptyConsumer());
    builder.setMinApiLevel(AndroidApiLevel.O.getLevel());
    Path proguardCompatibilityRules = temp.newFile().toPath();
    builder.setProguardCompatibilityRulesOutput(proguardCompatibilityRules);
    AndroidApp app = ToolHelper.runR8(builder.build());
    inspection.accept(new DexInspector(app));
    // Check the Proguard compatibility configuration generated.
    ProguardConfigurationParser parser =
        new ProguardConfigurationParser(new DexItemFactory(),
            new Reporter(new DefaultDiagnosticsHandler()));
    parser.parse(proguardCompatibilityRules);
    ProguardConfiguration configuration = parser.getConfigRawForTesting();
    compatInspection.accept(configuration);
  }

  private void noCompatibilityRules(ProguardConfiguration configuration) {
    assertEquals(0, configuration.getRules().size());
  }

  private void defaultMethodKept(DexInspector inspector) {
    ClassSubject clazz = inspector.clazz(InterfaceWithDefaultMethods.class);
    assertTrue(clazz.isPresent());
    MethodSubject method = clazz.method("int", "method", ImmutableList.of());
    assertTrue(method.isPresent());
    assertFalse(method.isAbstract());
  }

  private void defaultMethodCompatibilityRules(ProguardConfiguration configuration) {
    assertEquals(1, configuration.getRules().size());
    ProguardConfigurationRule rule = configuration.getRules().get(0);
    Set<ProguardMemberRule> memberRules = rule.getMemberRules();
    ProguardClassNameList classNames = rule.getClassNames();
    assertEquals(1, classNames.size());
    DexType type = classNames.asSpecificDexTypes().get(0);
    assertEquals(type.toSourceString(), InterfaceWithDefaultMethods.class.getCanonicalName());
    assertEquals(1, memberRules.size());
    ProguardMemberRule memberRule = memberRules.iterator().next();
    assertEquals(ProguardMemberType.METHOD, memberRule.getRuleType());
    assertTrue(memberRule.getName().matches("method"));
    assertTrue(memberRule.getType().matches(configuration.getDexItemFactory().intType));
    assertEquals(0, memberRule.getArguments().size());
  }

  private void defaultMethod2Kept(DexInspector inspector) {
    ClassSubject clazz = inspector.clazz(InterfaceWithDefaultMethods.class);
    assertTrue(clazz.isPresent());
    MethodSubject method =
        clazz.method("void", "method2", ImmutableList.of("java.lang.String", "int"));
    assertTrue(method.isPresent());
    assertFalse(method.isAbstract());
  }

  private void defaultMethod2CompatibilityRules(ProguardConfiguration configuration) {
    assertEquals(1, configuration.getRules().size());
    ProguardConfigurationRule rule = configuration.getRules().get(0);
    Set<ProguardMemberRule> memberRules = rule.getMemberRules();
    ProguardClassNameList classNames = rule.getClassNames();
    assertEquals(1, classNames.size());
    DexType type = classNames.asSpecificDexTypes().get(0);
    assertEquals(type.toSourceString(), InterfaceWithDefaultMethods.class.getCanonicalName());
    assertEquals(1, memberRules.size());
    ProguardMemberRule memberRule = memberRules.iterator().next();
    assertEquals(ProguardMemberType.METHOD, memberRule.getRuleType());
    assertTrue(memberRule.getName().matches("method2"));
    assertTrue(memberRule.getType().matches(configuration.getDexItemFactory().voidType));
    assertEquals(2, memberRule.getArguments().size());
    assertTrue(
        memberRule.getArguments().get(0).matches(configuration.getDexItemFactory().stringType));
    assertTrue(memberRule.getArguments().get(1).matches(configuration.getDexItemFactory().intType));
  }

  @Test
  public void keepDefaultMethodsTest() throws Exception {
    runKeepDefaultMethodsTest(ImmutableList.of(
        "-keep interface " + InterfaceWithDefaultMethods.class.getCanonicalName() + "{",
        "  public int method();",
        "}"
    ), this::defaultMethodKept, this::noCompatibilityRules);
    runKeepDefaultMethodsTest(ImmutableList.of(
        "-keep class " + ClassImplementingInterface.class.getCanonicalName() + "{",
        "  <methods>;",
        "}",
        "-keep class " + TestClass.class.getCanonicalName() + "{",
        "  public void useInterfaceMethod();",
        "}"
    ), this::defaultMethodKept, this::defaultMethodCompatibilityRules);
    runKeepDefaultMethodsTest(ImmutableList.of(
        "-keep class " + ClassImplementingInterface.class.getCanonicalName() + "{",
        "  <methods>;",
        "}",
        "-keep class " + TestClass.class.getCanonicalName() + "{",
        "  public void useInterfaceMethod2();",
        "}"
    ), this::defaultMethod2Kept, this::defaultMethod2CompatibilityRules);
  }
}
