// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.ClassFileConsumer;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.ProgramConsumer;
import com.android.tools.r8.StringConsumer;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.errors.InvalidDebugInfoException;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

public class InternalOptions {

  public enum LineNumberOptimization {
    OFF,
    ON,
    IDENTITY_MAPPING
  }

  public final DexItemFactory itemFactory;
  public final ProguardConfiguration proguardConfiguration;
  public final Reporter reporter;

  // TODO(zerny): Make this private-final once we have full program-consumer support.
  public ProgramConsumer programConsumer = null;

  // Constructor for testing and/or other utilities.
  public InternalOptions() {
    reporter = new Reporter(new DefaultDiagnosticsHandler());
    itemFactory = new DexItemFactory();
    proguardConfiguration = ProguardConfiguration.defaultConfiguration(itemFactory, reporter);
  }

  // Constructor for D8.
  public InternalOptions(DexItemFactory factory, Reporter reporter) {
    assert reporter != null;
    assert factory != null;
    this.reporter = reporter;
    itemFactory = factory;
    proguardConfiguration = ProguardConfiguration.defaultConfiguration(itemFactory, reporter);
  }

  // Constructor for R8.
  public InternalOptions(ProguardConfiguration proguardConfiguration, Reporter reporter) {
    assert reporter != null;
    assert proguardConfiguration != null;
    this.reporter = reporter;
    this.proguardConfiguration = proguardConfiguration;
    itemFactory = proguardConfiguration.getDexItemFactory();
  }

  public boolean printTimes = false;

  // Flag to toggle if DEX code objects should pass-through without IR processing.
  public boolean passthroughDexCode = false;

  // Optimization-related flags. These should conform to -dontoptimize.
  public boolean skipClassMerging = true;
  public boolean addNonNull = true;
  public boolean inlineAccessors = true;
  public boolean removeSwitchMaps = true;
  public final OutlineOptions outline = new OutlineOptions();
  public boolean propagateMemberValue = true;

  // Number of threads to use while processing the dex files.
  public int numberOfThreads = ThreadUtils.NOT_SPECIFIED;
  // Print smali disassembly.
  public boolean useSmaliSyntax = false;
  // Verbose output.
  public boolean verbose = false;
  // Silencing output.
  public boolean quiet = false;

  // Hidden marker for classes.dex
  private boolean hasMarker = false;
  private Marker marker;

  public boolean hasMarker() {
    return hasMarker;
  }

  public void setMarker(Marker marker) {
    this.hasMarker = true;
    this.marker = marker;
  }

  public Marker getMarker() {
    assert hasMarker();
    return marker;
  }

  public boolean isGeneratingDex() {
    return isGeneratingDexIndexed() || isGeneratingDexFilePerClassFile();
  }

  public boolean isGeneratingDexIndexed() {
    return programConsumer instanceof DexIndexedConsumer;
  }

  public boolean isGeneratingDexFilePerClassFile() {
    return programConsumer instanceof DexFilePerClassFileConsumer;
  }

  public boolean isGeneratingClassFiles() {
    return programConsumer instanceof ClassFileConsumer;
  }

  public DexIndexedConsumer getDexIndexedConsumer() {
    return (DexIndexedConsumer) programConsumer;
  }

  public DexFilePerClassFileConsumer getDexFilePerClassFileConsumer() {
    return (DexFilePerClassFileConsumer) programConsumer;
  }

  public ClassFileConsumer getClassFileConsumer() {
    return (ClassFileConsumer) programConsumer;
  }

  public void signalFinishedToProgramConsumer() {
    if (programConsumer != null) {
      programConsumer.finished(reporter);
    }
  }

  public List<String> methodsFilter = ImmutableList.of();
  public int minApiLevel = AndroidApiLevel.getDefault().getLevel();
  // Skipping min_api check and compiling an intermediate result intended for later merging.
  // Intermediate builds also emits or update synthesized classes mapping.
  public boolean intermediate = false;
  public List<String> logArgumentsFilter = ImmutableList.of();

  // Flag to turn on/off desugaring in D8/R8.
  public boolean enableDesugaring = true;
  // Defines interface method rewriter behavior.
  public OffOrAuto interfaceMethodDesugaring = OffOrAuto.Auto;
  // Defines try-with-resources rewriter behavior.
  public OffOrAuto tryWithResourcesDesugaring = OffOrAuto.Auto;

  // Whether or not to check for valid multi-dex builds.
  //
  // For min-api levels that did not support native multi-dex the user should provide a main dex
  // list. However, DX, didn't check that this was the case. Therefore, for CompatDX we have a flag
  // to disable the check that the build makes sense for multi-dexing.
  public boolean enableMainDexListCheck = true;

  public boolean useTreeShaking = true;

  public boolean printCfg = false;
  public String printCfgFile;
  public boolean ignoreMissingClasses = false;
  // EXPERIMENTAL flag to get behaviour as close to Proguard as possible.
  public boolean forceProguardCompatibility = false;
  public boolean skipMinification = false;
  public boolean disableAssertions = true;
  public boolean debugKeepRules = false;

  // TODO(72312389): android.jar contains parts of JUnit and most developers include JUnit in
  // their programs, which can lead to library classes extending program classes.
  public boolean allowLibraryClassesToExtendProgramClasses = false;

  public boolean debug = false;
  public final TestingOptions testing = new TestingOptions();

  public ImmutableList<ProguardConfigurationRule> mainDexKeepRules = ImmutableList.of();
  public boolean minimalMainDex;

  public LineNumberOptimization lineNumberOptimization = LineNumberOptimization.ON;

  public static class InvalidParameterAnnotationInfo {

    final DexMethod method;
    final int expectedParameterCount;
    final int actualParameterCount;

    public InvalidParameterAnnotationInfo(
        DexMethod method, int expectedParameterCount, int actualParameterCount) {
      this.method = method;
      this.expectedParameterCount = expectedParameterCount;
      this.actualParameterCount = actualParameterCount;
    }
  }

  private static class TypeVersionPair {

    final int version;
    final DexType type;

    public TypeVersionPair(int version, DexType type) {
      this.version = version;
      this.type = type;
    }
  }

  private final Map<Origin, List<TypeVersionPair>> missingEnclosingMembers = new HashMap<>();

  private final Map<Origin, List<InvalidParameterAnnotationInfo>> warningInvalidParameterAnnotations
      = new HashMap<>();

  private final Map<Origin, List<DexEncodedMethod>> warningInvalidDebugInfo = new HashMap<>();

  // Don't read code from dex files. Used to extract non-code information from vdex files where
  // the code contains unsupported byte codes.
  public boolean skipReadingDexCode = false;

  // If null, no main-dex list needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer mainDexListConsumer = null;

  // If null, no proguad map needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer proguardMapConsumer = null;

  // If null, no proguad seeds info needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer proguardSeedsConsumer = null;

  // If null, no usage information needs to be computed.
  // If non-null, it must be and is passed to the consumer.
  public StringConsumer usageInformationConsumer = null;

  public Path proguardCompatibilityRulesOutput = null;

  public void warningMissingEnclosingMember(DexType clazz, Origin origin, int version) {
    TypeVersionPair pair = new TypeVersionPair(version, clazz);
    synchronized (missingEnclosingMembers) {
      missingEnclosingMembers.computeIfAbsent(origin, k -> new ArrayList<>()).add(pair);
    }
  }

  public void warningInvalidParameterAnnotations(
      DexMethod method, Origin origin, int expected, int actual) {
    InvalidParameterAnnotationInfo info =
        new InvalidParameterAnnotationInfo(method, expected, actual);
    synchronized (warningInvalidParameterAnnotations) {
      warningInvalidParameterAnnotations.computeIfAbsent(origin, k -> new ArrayList<>()).add(info);
    }
  }

  public void warningInvalidDebugInfo(
      DexEncodedMethod method, Origin origin, InvalidDebugInfoException e) {
    synchronized (warningInvalidDebugInfo) {
      warningInvalidDebugInfo.computeIfAbsent(origin, k -> new ArrayList<>()).add(method);
    }
  }

  public boolean printWarnings() {
    boolean printed = false;
    boolean printOutdatedToolchain = false;
    if (warningInvalidParameterAnnotations.size() > 0) {
      // TODO(b/67626202): Add a regression test with a program that hits this issue.
      reporter.warning(
          new StringDiagnostic(
              "Invalid parameter counts in MethodParameter attributes. "
                  + "This is likely due to Proguard having removed a parameter."));
      for (Origin origin : new TreeSet<>(warningInvalidParameterAnnotations.keySet())) {
        StringBuilder builder =
            new StringBuilder("Methods with invalid MethodParameter attributes:");
        for (InvalidParameterAnnotationInfo info : warningInvalidParameterAnnotations.get(origin)) {
          builder
              .append("\n  ")
              .append(info.method)
              .append(" expected count: ")
              .append(info.expectedParameterCount)
              .append(" actual count: ")
              .append(info.actualParameterCount);
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
    }
    if (warningInvalidDebugInfo.size() > 0) {
      int count = 0;
      for (List<DexEncodedMethod> methods : warningInvalidDebugInfo.values()) {
        count += methods.size();
      }
      reporter.warning(
          new StringDiagnostic(
              "Stripped invalid locals information from "
                  + count
                  + (count == 1 ? " method." : " methods.")));
      for (Origin origin : new TreeSet<>(warningInvalidDebugInfo.keySet())) {
        StringBuilder builder = new StringBuilder("Methods with invalid locals information:");
        for (DexEncodedMethod method : warningInvalidDebugInfo.get(origin)) {
          builder.append("\n  ").append(method.toSourceString());
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
      printOutdatedToolchain = true;
    }
    if (missingEnclosingMembers.size() > 0) {
      reporter.warning(
          new StringDiagnostic(
              "InnerClass annotations are missing corresponding EnclosingMember annotations."
                  + " Such InnerClass annotations are ignored."));
      for (Origin origin : new TreeSet<>(missingEnclosingMembers.keySet())) {
        StringBuilder builder = new StringBuilder("Classes with missing enclosing members: ");
        boolean first = true;
        for (TypeVersionPair pair : missingEnclosingMembers.get(origin)) {
          if (first) {
            first = false;
          } else {
            builder.append(", ");
          }
          builder.append(pair.type);
          printOutdatedToolchain |= pair.version < 49;
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
    }
    if (printOutdatedToolchain) {
      reporter.info(
          new StringDiagnostic(
              "Some warnings are typically a sign of using an outdated Java toolchain."
                  + " To fix, recompile the source with an updated toolchain."));
    }
    return printed;
  }

  public boolean hasMethodsFilter() {
    return methodsFilter.size() > 0;
  }

  public boolean methodMatchesFilter(DexEncodedMethod method) {
    // Not specifying a filter matches all methods.
    if (!hasMethodsFilter()) {
      return true;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return methodsFilter.indexOf(qualifiedName) >= 0;
  }

  public boolean methodMatchesLogArgumentsFilter(DexEncodedMethod method) {
    // Not specifying a filter matches no methods.
    if (logArgumentsFilter.size() == 0) {
      return false;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return logArgumentsFilter.indexOf(qualifiedName) >= 0;
  }

  public enum PackageObfuscationMode {
    // General package obfuscation.
    NONE,
    // Repackaging all classes into the single user-given (or top-level) package.
    REPACKAGE,
    // Repackaging all packages into the single user-given (or top-level) package.
    FLATTEN
  }

  public static class OutlineOptions {

    public static final String CLASS_NAME = "r8.GeneratedOutlineSupport";
    public static final String METHOD_PREFIX = "outline";

    public boolean enabled = true;
    public int minSize = 3;
    public int maxSize = 99;
    public int threshold = 20;
  }

  public static class TestingOptions {

    public Function<Set<DexEncodedMethod>, Set<DexEncodedMethod>> irOrdering =
        Function.identity();

    public boolean invertConditionals = false;
  }

  public boolean canUseInvokePolymorphicOnVarHandle() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseInvokePolymorphic() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  public boolean canUseConstantMethodHandle() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseConstantMethodType() {
    return minApiLevel >= AndroidApiLevel.P.getLevel();
  }

  public boolean canUseInvokeCustom() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  public boolean canUseDefaultAndStaticInterfaceMethods() {
    return minApiLevel >= AndroidApiLevel.N.getLevel();
  }

  public boolean canUsePrivateInterfaceMethods() {
    return minApiLevel >= AndroidApiLevel.N.getLevel();
  }

  public boolean canUseMultidex() {
    return intermediate || minApiLevel >= AndroidApiLevel.L.getLevel();
  }

  public boolean canUseLongCompareAndObjectsNonNull() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }

  public boolean canUseSuppressedExceptions() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }

  // APIs for accessing parameter names annotations are not available before Android O, thus does
  // not emit them to avoid wasting space in Dex files because runtimes before Android O will ignore
  // them.
  public boolean canUseParameterNameAnnotations() {
    return minApiLevel >= AndroidApiLevel.O.getLevel();
  }

  // Dalvik x86-atom backend had a bug that made it crash on filled-new-array instructions for
  // arrays of objects. This is unfortunate, since this never hits arm devices, but we have
  // to disallow filled-new-array of objects for dalvik until kitkat. The buggy code was
  // removed during the jelly-bean release cycle and is not there from kitkat.
  //
  // Buggy code that accidentally call code that only works on primitives arrays.
  //
  // https://android.googlesource.com/platform/dalvik/+/ics-mr0/vm/mterp/out/InterpAsm-x86-atom.S#25106
  public boolean canUseFilledNewArrayOfObjects() {
    return minApiLevel >= AndroidApiLevel.K.getLevel();
  }

  // Art had a bug (b/68761724) for Android N and O in the arm32 interpreter
  // where an aget-wide instruction using the same register for the array
  // and the first register of the result could lead to the wrong exception
  // being thrown on out of bounds.
  public boolean canUseSameArrayAndResultRegisterInArrayGetWide() {
    return minApiLevel > AndroidApiLevel.O_MR1.getLevel();
  }

  // Some Lollipop versions of Art found in the wild perform invalid bounds
  // check elimination. There is a fast path of loops and a slow path.
  // The bailout to the slow path is performed too early and therefore
  // the loop variable might not be defined in the slow path code leading
  // to use of undefined registers as indices into arrays. The result
  // is ArrayIndexOutOfBounds exceptions.
  //
  // In an attempt to help these Art VMs get the loop variable initialized
  // early, we do not lower constants past array-length instructions when
  // building for Lollipop or below.
  //
  // There is no guarantee that this works, but it does make the problem
  // disappear on the one known instance of this problem.
  //
  // See b/69364976.
  public boolean canHaveBoundsCheckEliminationBug() {
    return minApiLevel <= AndroidApiLevel.L.getLevel();
  }
}
