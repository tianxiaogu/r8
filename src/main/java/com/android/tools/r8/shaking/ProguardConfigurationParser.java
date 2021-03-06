// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.position.TextPosition;
import com.android.tools.r8.position.TextRange;
import com.android.tools.r8.shaking.ProguardConfiguration.Builder;
import com.android.tools.r8.shaking.ProguardTypeMatcher.ClassOrType;
import com.android.tools.r8.shaking.ProguardTypeMatcher.MatchSpecificType;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.IdentifierUtils;
import com.android.tools.r8.utils.InternalOptions.PackageObfuscationMode;
import com.android.tools.r8.utils.LongInterval;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public class ProguardConfigurationParser {

  private final Builder configurationBuilder;

  private final DexItemFactory dexItemFactory;

  private final Reporter reporter;

  private static final List<String> IGNORED_SINGLE_ARG_OPTIONS = ImmutableList
      .of("protomapping",
          "target");
  private static final List<String> IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS = ImmutableList
      .of("keepdirectories", "runtype", "laststageoutput");
  private static final List<String> IGNORED_FLAG_OPTIONS = ImmutableList
      .of("forceprocessing", "dontusemixedcaseclassnames",
          "dontpreverify", "experimentalshrinkunusedprotofields",
          "filterlibraryjarswithorginalprogramjars",
          "dontskipnonpubliclibraryclasses",
          "dontskipnonpubliclibraryclassmembers",
          "invokebasemethod");
  private static final List<String> IGNORED_CLASS_DESCRIPTOR_OPTIONS = ImmutableList
      .of("isclassnamestring",
          "whyarenotsimple");

  private static final List<String> WARNED_SINGLE_ARG_OPTIONS = ImmutableList
      .of("dontnote",
          "printconfiguration",
          // TODO -outjars (http://b/37137994) and -adaptresourcefilecontents (http://b/37139570)
          // should be reported as errors, not just as warnings!
          "outjars",
          "adaptresourcefilecontents");
  private static final List<String> WARNED_FLAG_OPTIONS = ImmutableList
      .of();

  // Those options are unsupported and are treated as compilation errors.
  // Just ignoring them would produce outputs incompatible with user expectations.
  private static final List<String> UNSUPPORTED_FLAG_OPTIONS = ImmutableList
      .of("skipnonpubliclibraryclasses");

  public ProguardConfigurationParser(
      DexItemFactory dexItemFactory, Reporter reporter) {
    this.dexItemFactory = dexItemFactory;
    configurationBuilder = ProguardConfiguration.builder(dexItemFactory, reporter);

    this.reporter = reporter;
  }

  public ProguardConfiguration.Builder getConfigurationBuilder() {
    return configurationBuilder;
  }

  private void validate() {
    if (configurationBuilder.isKeepParameterNames() && configurationBuilder.isObfuscating()) {
      // The flag -keepparameternames has only effect when minifying, so ignore it if we
      // are not.
      reporter.fatalError(new StringDiagnostic(
          "-keepparameternames is not supported",
          configurationBuilder.getKeepParameterNamesOptionOrigin(),
          configurationBuilder.getKeepParameterNamesOptionPosition()));
    }
  }

  /**
   * Returns the Proguard configuration with default rules derived from empty rules added.
   */
  public ProguardConfiguration getConfig() {
    validate();
    return configurationBuilder.build();
  }

  /**
   * Returns the Proguard configuration from exactly the rules parsed, without any
   * defaults derived from empty rules.
   */
  public ProguardConfiguration getConfigRawForTesting() {
    validate();
    return configurationBuilder.buildRaw();
  }

  public void parse(Path path) {
    parse(ImmutableList.of(new ProguardConfigurationSourceFile(path)));
  }

  // package visible for testing
  void parse(ProguardConfigurationSource source) {
    parse(ImmutableList.of(source));
  }

  public void parse(List<ProguardConfigurationSource> sources) {
    for (ProguardConfigurationSource source : sources) {
      try {
        new ProguardConfigurationSourceParser(source).parse();
      } catch (IOException e) {
        reporter.error(new StringDiagnostic("Failed to read file: " + e.getMessage(),
            source.getOrigin()));
      } catch (ProguardRuleParserException e) {
        reporter.error(e, MoreObjects.firstNonNull(e.getCause(), e));
      }
    }
    reporter.failIfPendingErrors();
  }

  private class ProguardConfigurationSourceParser {
    private final String name;
    private final String contents;
    private int position = 0;
    private int line = 1;
    private int lineStartPosition = 0;
    private Path baseDirectory;
    private final Origin origin;

    ProguardConfigurationSourceParser(ProguardConfigurationSource source) throws IOException {
      contents = source.get();
      baseDirectory = source.getBaseDirectory();
      name = source.getName();
      this.origin = source.getOrigin();
    }

    public void parse() throws ProguardRuleParserException {
      do {
        skipWhitespace();
      } while (parseOption());
    }

    private boolean parseOption()
        throws ProguardRuleParserException {
      if (eof()) {
        return false;
      }
      if (acceptArobaseInclude()) {
        return true;
      }
      TextPosition optionStart = getPosition();
      expectChar('-');
      String option;
      if (Iterables.any(IGNORED_SINGLE_ARG_OPTIONS, this::skipOptionWithSingleArg)
          || Iterables.any(
              IGNORED_OPTIONAL_SINGLE_ARG_OPTIONS, this::skipOptionWithOptionalSingleArg)
          || Iterables.any(IGNORED_FLAG_OPTIONS, this::skipFlag)
          || Iterables.any(IGNORED_CLASS_DESCRIPTOR_OPTIONS, this::skipOptionWithClassSpec)
          || parseOptimizationOption()) {
        // Intentionally left empty.
      } else if (
          (option = Iterables.find(WARNED_SINGLE_ARG_OPTIONS,
              this::skipOptionWithSingleArg, null)) != null
              || (option = Iterables.find(WARNED_FLAG_OPTIONS, this::skipFlag, null)) != null) {
        warnIgnoringOptions(option, optionStart);
      } else if (
          (option = Iterables.find(UNSUPPORTED_FLAG_OPTIONS, this::skipFlag, null)) != null) {
        reporter.error(new StringDiagnostic(
            "Unsupported option: -" + option,
            origin,
            getPostion(optionStart)));
      } else if (acceptString("renamesourcefileattribute")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setRenameSourceFileAttribute(acceptString());
        } else {
          configurationBuilder.setRenameSourceFileAttribute("");
        }
      } else if (acceptString("keepattributes")) {
        parseKeepAttributes();
      } else if (acceptString("keeppackagenames")) {
        ProguardKeepPackageNamesRule rule = parseKeepPackageNamesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keepparameternames")) {
        configurationBuilder.setKeepParameterNames(true,
            origin, getPostion(optionStart));
      } else if (acceptString("checkdiscard")) {
        ProguardCheckDiscardRule rule = parseCheckDiscardRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("keep")) {
        ProguardKeepRule rule = parseKeepRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("whyareyoukeeping")) {
        ProguardWhyAreYouKeepingRule rule = parseWhyAreYouKeepingRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("dontoptimize")) {
        configurationBuilder.disableOptimization();
      } else if (acceptString("optimizationpasses")) {
        skipWhitespace();
        Integer expectedOptimizationPasses = acceptInteger();
        if (expectedOptimizationPasses == null) {
          throw reporter.fatalError(new StringDiagnostic(
              "Missing n of \"-optimizationpasses n\"",
              origin,
              getPostion(optionStart)));
        }
        warnIgnoringOptions("optimizationpasses", optionStart);
      } else if (acceptString("dontobfuscate")) {
        configurationBuilder.disableObfuscation();
      } else if (acceptString("dontshrink")) {
        configurationBuilder.disableShrinking();
      } else if (acceptString("printusage")) {
        configurationBuilder.setPrintUsage(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintUsageFile(parseFileName());
        }
      } else if (acceptString("verbose")) {
        configurationBuilder.setVerbose(true);
      } else if (acceptString("ignorewarnings")) {
        configurationBuilder.setIgnoreWarnings(true);
      } else if (acceptString("dontwarn")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.addDontWarnPattern(parseClassNames());
        } else {
          configurationBuilder.addDontWarnPattern(
              ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
        }
      } else if (acceptString("repackageclasses")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.FLATTEN) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy",
              optionStart);
        }
        skipWhitespace();
        if (acceptChar('\'')) {
          configurationBuilder.setPackagePrefix(parsePackageNameOrEmptyString());
          expectChar('\'');
        } else {
          configurationBuilder.setPackagePrefix("");
        }
      } else if (acceptString("flattenpackagehierarchy")) {
        if (configurationBuilder.getPackageObfuscationMode() == PackageObfuscationMode.REPACKAGE) {
          warnOverridingOptions("repackageclasses", "flattenpackagehierarchy",
              optionStart);
          skipWhitespace();
          if (isOptionalArgumentGiven()) {
            skipSingleArgument();
          }
        } else {
          skipWhitespace();
          if (acceptChar('\'')) {
            configurationBuilder.setFlattenPackagePrefix(parsePackageNameOrEmptyString());
            expectChar('\'');
          } else {
            configurationBuilder.setFlattenPackagePrefix("");
          }
        }
      } else if (acceptString("overloadaggressively")) {
        configurationBuilder.setOverloadAggressively(true);
      } else if (acceptString("allowaccessmodification")) {
        configurationBuilder.setAllowAccessModification(true);
      } else if (acceptString("printmapping")) {
        configurationBuilder.setPrintMapping(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setPrintMappingFile(parseFileName());
        }
      } else if (acceptString("applymapping")) {
        configurationBuilder.setApplyMappingFile(parseFileName());
      } else if (acceptString("assumenosideeffects")) {
        ProguardAssumeNoSideEffectRule rule = parseAssumeNoSideEffectsRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("assumevalues")) {
        ProguardAssumeValuesRule rule = parseAssumeValuesRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("include")) {
        skipWhitespace();
        parseInclude();
      } else if (acceptString("basedirectory")) {
        skipWhitespace();
        baseDirectory = parseFileName();
      } else if (acceptString("injars")) {
        configurationBuilder.addInjars(parseClassPath());
      } else if (acceptString("libraryjars")) {
        configurationBuilder.addLibraryJars(parseClassPath());
      } else if (acceptString("printseeds")) {
        configurationBuilder.setPrintSeeds(true);
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.setSeedFile(parseFileName());
        }
      } else if (acceptString("obfuscationdictionary")) {
        configurationBuilder.setObfuscationDictionary(parseFileName());
      } else if (acceptString("classobfuscationdictionary")) {
        configurationBuilder.setClassObfuscationDictionary(parseFileName());
      } else if (acceptString("packageobfuscationdictionary")) {
        configurationBuilder.setPackageObfuscationDictionary(parseFileName());
      } else if (acceptString("alwaysinline")) {
        ProguardAlwaysInlineRule rule = parseAlwaysInlineRule();
        configurationBuilder.addRule(rule);
      } else if (acceptString("useuniqueclassmembernames")) {
        configurationBuilder.setUseUniqueClassMemberNames(true);
      } else if (acceptString("adaptclassstrings")) {
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          configurationBuilder.addAdaptClassStringsPattern(parseClassNames());
        } else {
          configurationBuilder.addAdaptClassStringsPattern(
              ProguardClassNameList.singletonList(ProguardTypeMatcher.defaultAllMatcher()));
        }
      } else if (acceptString("identifiernamestring")) {
        configurationBuilder.addRule(parseIdentifierNameStringRule());
      } else {
        String unknownOption = acceptString();
        reporter.error(new StringDiagnostic("Unknown option \"-" + unknownOption + "\"",
            origin, getPostion(optionStart)));
      }
      return true;
    }


    private void parseInclude() throws ProguardRuleParserException {
      TextPosition start = getPosition();
      Path included = parseFileName();
      try {
        new ProguardConfigurationSourceParser(new ProguardConfigurationSourceFile(included))
            .parse();
      } catch (FileNotFoundException | NoSuchFileException e) {
        throw parseError("Included file '" + included.toString() + "' not found",
            start, e);
      } catch (IOException e) {
        throw parseError("Failed to read included file '" + included.toString() + "'",
            start, e);
      }
    }

    private boolean acceptArobaseInclude() throws ProguardRuleParserException {
      if (remainingChars() < 2) {
        return false;
      }
      if (!acceptChar('@')) {
        return false;
      }
      parseInclude();
      return true;
    }

    private void parseKeepAttributes() throws ProguardRuleParserException {
      List<String> attributesPatterns = acceptPatternList();
      if (attributesPatterns.isEmpty()) {
        throw parseError("Expected attribute pattern list");
      }
      configurationBuilder.addKeepAttributePatterns(attributesPatterns);
    }

    private boolean skipFlag(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` flag", name);
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipSingleArgument();
        return true;
      }
      return false;
    }

    private boolean skipOptionWithOptionalSingleArg(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        skipWhitespace();
        if (isOptionalArgumentGiven()) {
          skipSingleArgument();
        }
        return true;
      }
      return false;
    }

    private boolean skipOptionWithClassSpec(String name) {
      if (acceptString(name)) {
        if (Log.ENABLED) {
          Log.debug(ProguardConfigurationParser.class, "Skipping '-%s` option", name);
        }
        try {
          ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
          parseClassSpec(keepRuleBuilder, true);
          return true;
        } catch (ProguardRuleParserException e) {
          throw reporter.fatalError(e, MoreObjects.firstNonNull(e.getCause(), e));
        }
      }
      return false;

    }

    private boolean parseOptimizationOption() {
      if (!acceptString("optimizations")) {
        return false;
      }
      skipWhitespace();
      do {
        skipOptimizationName();
        skipWhitespace();
      } while (acceptChar(','));
      return true;
    }

    private void skipOptimizationName() {
      if (acceptChar('!')) {
        skipWhitespace();
      }
      for (char next = peekChar();
          Character.isAlphabetic(next) || next == '/' || next == '*';
          next = peekChar()) {
        readChar();
      }
    }

    private void skipSingleArgument() {
      skipWhitespace();
      while (!eof() && !Character.isWhitespace(peekChar())) {
        readChar();
      }
    }

    private ProguardKeepRule parseKeepRule()
        throws ProguardRuleParserException {
      ProguardKeepRule.Builder keepRuleBuilder = ProguardKeepRule.builder();
      parseRuleTypeAndModifiers(keepRuleBuilder);
      parseClassSpec(keepRuleBuilder, false);
      if (keepRuleBuilder.getMemberRules().isEmpty()) {
        // If there are no member rules, a default rule for the parameterless constructor
        // applies. So we add that here.
        ProguardMemberRule.Builder defaultRuleBuilder = ProguardMemberRule.builder();
        defaultRuleBuilder.setName(Constants.INSTANCE_INITIALIZER_NAME);
        defaultRuleBuilder.setRuleType(ProguardMemberType.INIT);
        defaultRuleBuilder.setArguments(Collections.emptyList());
        keepRuleBuilder.getMemberRules().add(defaultRuleBuilder.build());
      }
      return keepRuleBuilder.build();
    }

    private ProguardWhyAreYouKeepingRule parseWhyAreYouKeepingRule()
        throws ProguardRuleParserException {
      ProguardWhyAreYouKeepingRule.Builder keepRuleBuilder = ProguardWhyAreYouKeepingRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardKeepPackageNamesRule parseKeepPackageNamesRule()
        throws ProguardRuleParserException {
      ProguardKeepPackageNamesRule.Builder keepRuleBuilder = ProguardKeepPackageNamesRule.builder();
      keepRuleBuilder.setClassNames(parseClassNames());
      return keepRuleBuilder.build();
    }

    private ProguardCheckDiscardRule parseCheckDiscardRule()
        throws ProguardRuleParserException {
      ProguardCheckDiscardRule.Builder keepRuleBuilder = ProguardCheckDiscardRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardAlwaysInlineRule parseAlwaysInlineRule()
        throws ProguardRuleParserException {
      ProguardAlwaysInlineRule.Builder keepRuleBuilder = ProguardAlwaysInlineRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private ProguardIdentifierNameStringRule parseIdentifierNameStringRule()
        throws ProguardRuleParserException {
      ProguardIdentifierNameStringRule.Builder keepRuleBuilder =
          ProguardIdentifierNameStringRule.builder();
      parseClassSpec(keepRuleBuilder, false);
      return keepRuleBuilder.build();
    }

    private void parseClassSpec(
        ProguardConfigurationRule.Builder builder, boolean allowValueSpecification)
        throws ProguardRuleParserException {
      parseClassFlagsAndAnnotations(builder);
      parseClassType(builder);
      builder.setClassNames(parseClassNames());
      parseInheritance(builder);
      parseMemberRules(builder, allowValueSpecification);
    }

    private void parseRuleTypeAndModifiers(ProguardKeepRule.Builder builder)
        throws ProguardRuleParserException {
      if (acceptString("names")) {
        builder.setType(ProguardKeepRuleType.KEEP);
        builder.getModifiersBuilder().setAllowsShrinking(true);
      } else if (acceptString("class")) {
        if (acceptString("members")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
        } else if (acceptString("eswithmembers")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
        } else if (acceptString("membernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASS_MEMBERS);
          builder.getModifiersBuilder().setAllowsShrinking(true);
        } else if (acceptString("eswithmembernames")) {
          builder.setType(ProguardKeepRuleType.KEEP_CLASSES_WITH_MEMBERS);
          builder.getModifiersBuilder().setAllowsShrinking(true);
        } else {
          // The only path to here is through "-keep" followed by "class".
          unacceptString("-keepclass");
          TextPosition start = getPosition();
          acceptString("-");
          String unknownOption = acceptString();
          throw reporter.fatalError(new StringDiagnostic(
              "Unknown option \"-" + unknownOption + "\"",
              origin,
              start));
        }
      } else {
        builder.setType(ProguardKeepRuleType.KEEP);
      }
      parseRuleModifiers(builder);
    }

    private void parseRuleModifiers(ProguardKeepRule.Builder builder) {
      while (acceptChar(',')) {
        if (acceptString("allow")) {
          if (acceptString("shrinking")) {
            builder.getModifiersBuilder().setAllowsShrinking(true);
          } else if (acceptString("optimization")) {
            builder.getModifiersBuilder().setAllowsOptimization(true);
          } else if (acceptString("obfuscation")) {
            builder.getModifiersBuilder().setAllowsObfuscation(true);
          }
        } else if (acceptString("includedescriptorclasses")) {
          builder.getModifiersBuilder().setIncludeDescriptorClasses(true);
        }
      }
    }

    private ProguardTypeMatcher parseAnnotation()
      throws ProguardRuleParserException {

      skipWhitespace();
      int startPosition = position;
      if (acceptChar('@')) {
        String className = parseClassName();
        if (className.equals("interface")) {
          // Not an annotation after all but a class type. Move position back to start
          // so this can be dealt with as a class type instead.
          position = startPosition;
          return null;
        }
        return ProguardTypeMatcher.create(className, ClassOrType.CLASS, dexItemFactory);
      }
      return null;
    }

    private boolean parseNegation() {
      skipWhitespace();
      return acceptChar('!');
    }

    private void parseClassFlagsAndAnnotations(ProguardClassSpecification.Builder builder)
        throws ProguardRuleParserException {
      while (true) {
        skipWhitespace();
        ProguardTypeMatcher annotation = parseAnnotation();
        if (annotation != null) {
          // TODO(ager): Should we only allow one annotation? It looks that way from the
          // proguard keep rule description, but that seems like a strange restriction?
          assert builder.getClassAnnotation() == null;
          builder.setClassAnnotation(annotation);
        } else {
          int start = position;
          ProguardAccessFlags flags =
              parseNegation()
                  ? builder.getNegatedClassAccessFlags()
                  : builder.getClassAccessFlags();
          skipWhitespace();
          if (acceptString("public")) {
            flags.setPublic();
          } else if (acceptString("final")) {
            flags.setFinal();
          } else if (acceptString("abstract")) {
            flags.setAbstract();
          } else {
            // Undo reading the ! in case there is no modifier following.
            position = start;
            break;
          }
        }
      }
    }

    private void parseClassType(
        ProguardClassSpecification.Builder builder) throws ProguardRuleParserException {
      skipWhitespace();
      TextPosition start = getPosition();
      if (acceptChar('!')) {
        builder.setClassTypeNegated(true);
      }
      if (acceptString("interface")) {
        builder.setClassType(ProguardClassType.INTERFACE);
      } else if (acceptString("@interface")) {
        builder.setClassType(ProguardClassType.ANNOTATION_INTERFACE);
      } else if (acceptString("class")) {
        builder.setClassType(ProguardClassType.CLASS);
      } else if (acceptString("enum")) {
        builder.setClassType(ProguardClassType.ENUM);
      } else {
        throw reporter
            .fatalError(new StringDiagnostic("Expected [!]interface|@interface|class|enum",
            origin, getPostion(start)));
      }
    }

    private void parseInheritance(ProguardClassSpecification.Builder classSpecificationBuilder)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("implements")) {
        classSpecificationBuilder.setInheritanceIsExtends(false);
      } else if (acceptString("extends")) {
        classSpecificationBuilder.setInheritanceIsExtends(true);
      } else {
        return;
      }
      classSpecificationBuilder.setInheritanceAnnotation(parseAnnotation());
      classSpecificationBuilder.setInheritanceClassName(ProguardTypeMatcher.create(parseClassName(),
          ClassOrType.CLASS, dexItemFactory));
    }

    private void parseMemberRules(ProguardClassSpecification.Builder classSpecificationBuilder,
        boolean allowValueSpecification)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (!eof() && acceptChar('{')) {
        ProguardMemberRule rule = null;
        while ((rule = parseMemberRule(allowValueSpecification)) != null) {
          classSpecificationBuilder.getMemberRules().add(rule);
        }
        skipWhitespace();
        expectChar('}');
      }
    }

    private ProguardMemberRule parseMemberRule(boolean allowValueSpecification)
        throws ProguardRuleParserException {
      ProguardMemberRule.Builder ruleBuilder = ProguardMemberRule.builder();
      skipWhitespace();
      ruleBuilder.setAnnotation(parseAnnotation());
      parseMemberAccessFlags(ruleBuilder);
      parseMemberPattern(ruleBuilder, allowValueSpecification);
      return ruleBuilder.isValid() ? ruleBuilder.build() : null;
    }

    private void parseMemberAccessFlags(ProguardMemberRule.Builder ruleBuilder) {
      boolean found = true;
      while (found && !eof()) {
        found = false;
        ProguardAccessFlags flags =
            parseNegation() ? ruleBuilder.getNegatedAccessFlags() : ruleBuilder.getAccessFlags();
        skipWhitespace();
        switch (peekChar()) {
          case 'a':
            if (found = acceptString("abstract")) {
              flags.setAbstract();
            }
            break;
          case 'f':
            if (found = acceptString("final")) {
              flags.setFinal();
            }
            break;
          case 'n':
            if (found = acceptString("native")) {
              flags.setNative();
            }
            break;
          case 'p':
            if (found = acceptString("public")) {
              flags.setPublic();
            } else if (found = acceptString("private")) {
              flags.setPrivate();
            } else if (found = acceptString("protected")) {
              flags.setProtected();
            }
            break;
          case 's':
            if (found = acceptString("synchronized")) {
              flags.setSynchronized();
            } else if (found = acceptString("static")) {
              flags.setStatic();
            } else if (found = acceptString("strictfp")) {
              flags.setStrict();
            }
            break;
          case 't':
            if (found = acceptString("transient")) {
              flags.setTransient();
            }
            break;
          case 'v':
            if (found = acceptString("volatile")) {
              flags.setVolatile();
            }
            break;
          default:
            // Intentionally left empty.
        }
      }
    }

    private void parseMemberPattern(
        ProguardMemberRule.Builder ruleBuilder, boolean allowValueSpecification)
        throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptString("<methods>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_METHODS);
      } else if (acceptString("<fields>")) {
        ruleBuilder.setRuleType(ProguardMemberType.ALL_FIELDS);
      } else if (acceptString("<init>")) {
        ruleBuilder.setRuleType(ProguardMemberType.INIT);
        ruleBuilder.setName("<init>");
        ruleBuilder.setArguments(parseArgumentList());
      } else {
        String first = acceptClassName();
        if (first != null) {
          skipWhitespace();
          if (first.equals("*") && hasNextChar(';')) {
            ruleBuilder.setRuleType(ProguardMemberType.ALL);
          } else {
            if (hasNextChar('(')) {
              ruleBuilder.setRuleType(ProguardMemberType.CONSTRUCTOR);
              ruleBuilder.setName(first);
              ruleBuilder.setArguments(parseArgumentList());
            } else {
              String second = acceptClassName();
              if (second != null) {
                skipWhitespace();
                if (hasNextChar('(')) {
                  ruleBuilder.setRuleType(ProguardMemberType.METHOD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ClassOrType.TYPE, dexItemFactory));
                  ruleBuilder.setArguments(parseArgumentList());
                } else {
                  ruleBuilder.setRuleType(ProguardMemberType.FIELD);
                  ruleBuilder.setName(second);
                  ruleBuilder
                      .setTypeMatcher(
                          ProguardTypeMatcher.create(first, ClassOrType.TYPE, dexItemFactory));
                }
                skipWhitespace();
                // Parse "return ..." if present.
                if (acceptString("return")) {
                  skipWhitespace();
                  if (acceptString("true")) {
                    ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(true));
                  } else if (acceptString("false")) {
                    ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(false));
                  } else {
                    TextPosition fieldOrValueStart = getPosition();
                    String qualifiedFieldNameOrInteger = acceptFieldNameOrIntegerForReturn();
                    if (qualifiedFieldNameOrInteger != null) {
                      if (isInteger(qualifiedFieldNameOrInteger)) {
                        Integer min = Integer.parseInt(qualifiedFieldNameOrInteger);
                        Integer max = min;
                        skipWhitespace();
                        if (acceptString("..")) {
                          max = acceptInteger();
                          if (max == null) {
                            throw parseError("Expected integer value");
                          }
                        }
                        if (!allowValueSpecification) {
                          throw parseError("Unexpected value specification", fieldOrValueStart);
                        }
                        ruleBuilder.setReturnValue(
                            new ProguardMemberRuleReturnValue(new LongInterval(min, max)));
                      } else {
                        if (ruleBuilder.getTypeMatcher() instanceof MatchSpecificType) {
                          int lastDotIndex = qualifiedFieldNameOrInteger.lastIndexOf(".");
                          DexType fieldType = ((MatchSpecificType) ruleBuilder
                              .getTypeMatcher()).type;
                          DexType fieldClass =
                              dexItemFactory.createType(
                                  DescriptorUtils.javaTypeToDescriptor(
                                      qualifiedFieldNameOrInteger.substring(0, lastDotIndex)));
                          DexString fieldName =
                              dexItemFactory.createString(
                                  qualifiedFieldNameOrInteger.substring(lastDotIndex + 1));
                          DexField field = dexItemFactory
                              .createField(fieldClass, fieldType, fieldName);
                          ruleBuilder.setReturnValue(new ProguardMemberRuleReturnValue(field));
                        } else {
                          throw parseError("Expected specific type", fieldOrValueStart);
                        }
                      }
                    }
                  }
                }
              } else {
                throw parseError("Expected field or method name");
              }
            }
          }
        }
      }
      // If we found a member pattern eat the terminating ';'.
      if (ruleBuilder.isValid()) {
        skipWhitespace();
        expectChar(';');
      }
    }

    private List<ProguardTypeMatcher> parseArgumentList() throws ProguardRuleParserException {
      List<ProguardTypeMatcher> arguments = new ArrayList<>();
      skipWhitespace();
      expectChar('(');
      skipWhitespace();
      if (acceptChar(')')) {
        return arguments;
      }
      if (acceptString("...")) {
        arguments
            .add(ProguardTypeMatcher.create("...", ClassOrType.TYPE, dexItemFactory));
      } else {
        for (String name = parseClassName(); name != null; name =
            acceptChar(',') ? parseClassName() : null) {
          arguments
              .add(ProguardTypeMatcher.create(name, ClassOrType.TYPE, dexItemFactory));
          skipWhitespace();
        }
      }
      skipWhitespace();
      expectChar(')');
      return arguments;
    }

    private Path parseFileName() throws ProguardRuleParserException {
      TextPosition start = getPosition();
      skipWhitespace();
      String fileName = acceptString(character ->
          character != File.pathSeparatorChar
              && !Character.isWhitespace(character)
              && character != '(');
      if (fileName == null) {
        throw parseError("File name expected", start);
      }
      return baseDirectory.resolve(fileName);
    }

    private List<FilteredClassPath> parseClassPath() throws ProguardRuleParserException {
      List<FilteredClassPath> classPath = new ArrayList<>();
      skipWhitespace();
      Path file = parseFileName();
      ImmutableList<String> filters = parseClassPathFilters();
      classPath.add(new FilteredClassPath(file, filters));
      while (acceptChar(File.pathSeparatorChar)) {
        file = parseFileName();
        filters = parseClassPathFilters();
        classPath.add(new FilteredClassPath(file, filters));
      }
      return classPath;
    }

    private ImmutableList<String> parseClassPathFilters() throws ProguardRuleParserException {
      skipWhitespace();
      if (acceptChar('(')) {
        ImmutableList.Builder<String> filters = new ImmutableList.Builder<>();
        filters.add(parseFileFilter());
        skipWhitespace();
        while (acceptChar(',')) {
          filters.add(parseFileFilter());
          skipWhitespace();
        }
        if (peekChar() == ';') {
          throw parseError("Only class file filters are supported in classpath");
        }
        expectChar(')');
        return filters.build();
      } else {
        return ImmutableList.of();
      }
    }

    private String parseFileFilter() throws ProguardRuleParserException {
      TextPosition start = getPosition();
      skipWhitespace();
      String fileFilter = acceptString(character ->
          character != ',' && character != ';' && character != ')'
              && !Character.isWhitespace(character));
      if (fileFilter == null) {
        throw parseError("file filter expected", start);
      }
      return fileFilter;
    }

    private ProguardAssumeNoSideEffectRule parseAssumeNoSideEffectsRule()
        throws ProguardRuleParserException {
      ProguardAssumeNoSideEffectRule.Builder builder = ProguardAssumeNoSideEffectRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private ProguardAssumeValuesRule parseAssumeValuesRule() throws ProguardRuleParserException {
      ProguardAssumeValuesRule.Builder builder = ProguardAssumeValuesRule.builder();
      parseClassSpec(builder, true);
      return builder.build();
    }

    private void skipWhitespace() {
      while (!eof() && Character.isWhitespace(contents.charAt(position))) {
        if (peekChar() == '\n') {
          line++;
          lineStartPosition = position + 1;
        }
        position++;
      }
      skipComment();
    }

    private void skipComment() {
      if (eof()) {
        return;
      }
      if (peekChar() == '#') {
        while (!eof() && peekChar() != '\n') {
          position++;;
        }
        skipWhitespace();
      }
    }

    private boolean isInteger(String s) {
      for (int i = 0; i < s.length(); i++) {
        if (!Character.isDigit(s.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    private boolean eof() {
      return position == contents.length();
    }

    private boolean eof(int position) {
      return position == contents.length();
    }

    private boolean hasNextChar(char c) {
      if (eof()) {
        return false;
      }
      return peekChar() == c;
    }

    private boolean isOptionalArgumentGiven() {
      return !eof() && !hasNextChar('-');
    }

    private boolean acceptChar(char c) {
      if (hasNextChar(c)) {
        position++;
        return true;
      }
      return false;
    }

    private char peekChar() {
      return contents.charAt(position);
    }

    private char peekCharAt(int position) {
      assert !eof(position);
      return contents.charAt(position);
    }

    private char readChar() {
      return contents.charAt(position++);
    }

    private int remainingChars() {
      return contents.length() - position;
    }

    private void expectChar(char c) throws ProguardRuleParserException {
      if (!acceptChar(c)) {
        throw parseError("Expected char '" + c + "'");
      }
    }

    private boolean acceptString(String expected) {
      if (remainingChars() < expected.length()) {
        return false;
      }
      for (int i = 0; i < expected.length(); i++) {
        if (expected.charAt(i) != contents.charAt(position + i)) {
          return false;
        }
      }
      position += expected.length();
      return true;
    }

    private String acceptString() {
      return acceptString(character -> character != ' ' && character != '\n');
    }

    private Integer acceptInteger() {
      String s = acceptString(Character::isDigit);
      if (s == null) {
        return null;
      }
      return Integer.parseInt(s);
    }

    private String acceptClassName() {
      return acceptString(character ->
          IdentifierUtils.isDexIdentifierPart(character)
              || character == '.'
              || character == '*'
              || character == '?'
              || character == '%'
              || character == '['
              || character == ']');
    }

    private String acceptFieldNameOrIntegerForReturn() {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        char current = contents.charAt(end);
        if (current == '.' && !eof(end + 1) && peekCharAt(end + 1) == '.') {
          // The grammar is ambiguous. End accepting before .. token used in return ranges.
          break;
        }
        if ((start == end && IdentifierUtils.isDexIdentifierStart(current)) ||
            ((start < end) && (IdentifierUtils.isDexIdentifierPart(current) || current == '.'))) {
          end++;
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private List<String> acceptPatternList() throws ProguardRuleParserException {
      List<String> patterns = new ArrayList<>();
      String pattern = acceptPattern();
      while (pattern != null) {
        patterns.add(pattern);
        skipWhitespace();
        TextPosition start = getPosition();
        if (acceptChar(',')) {
          pattern = acceptPattern();
          if (pattern == null) {
            throw parseError("Expected list element", start);
          }
        } else {
          pattern = null;
        }
      }
      return patterns;
    }

    private String acceptPattern() {
      return acceptString(character ->
          IdentifierUtils.isDexIdentifierPart(character) || character == '!' || character == '*');
    }

    private String acceptString(Predicate<Character> characterAcceptor) {
      skipWhitespace();
      int start = position;
      int end = position;
      while (!eof(end)) {
        char current = contents.charAt(end);
        if (characterAcceptor.test(current)) {
          end++;
        } else {
          break;
        }
      }
      if (start == end) {
        return null;
      }
      position = end;
      return contents.substring(start, end);
    }

    private void unacceptString(String expected) {
      assert position >= expected.length();
      position -= expected.length();
      for (int i = 0; i < expected.length(); i++) {
        assert expected.charAt(i) == contents.charAt(position + i);
      }
    }

    private ProguardClassNameList parseClassNames() throws ProguardRuleParserException {
      ProguardClassNameList.Builder builder = ProguardClassNameList.builder();
      skipWhitespace();
      boolean negated = acceptChar('!');
      builder.addClassName(negated,
          ProguardTypeMatcher.create(parseClassName(), ClassOrType.CLASS, dexItemFactory));
      skipWhitespace();
      while (acceptChar(',')) {
        negated = acceptChar('!');
        builder.addClassName(negated,
            ProguardTypeMatcher.create(parseClassName(), ClassOrType.CLASS, dexItemFactory));
        skipWhitespace();
      }
      return builder.build();
    }

    private String parsePackageNameOrEmptyString() {
      String name = acceptClassName();
      return name == null ? "" : name;
    }

    private String parseClassName() throws ProguardRuleParserException {
      String name = acceptClassName();
      if (name == null) {
        throw parseError("Class name expected");
      }
      return name;
    }

    private String snippetForPosition() {
      // TODO(ager): really should deal with \r as well to get column right.
      String[] lines = contents.split("\n", -1);  // -1 to get trailing empty lines represented.
      int remaining = position;
      for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
        String line = lines[lineNumber];
        if (remaining <= line.length() || lineNumber == lines.length - 1) {
          String arrow = CharBuffer.allocate(remaining).toString().replace('\0', ' ') + '^';
          return name + ":" + (lineNumber + 1) + ":" + (remaining + 1) + "\n" + line
              + '\n' + arrow;
        }
        remaining -= (line.length() + 1); // Include newline.
      }
      return name;
    }
    private String snippetForPosition(TextPosition start) {
      // TODO(ager): really should deal with \r as well to get column right.
      String[] lines = contents.split("\n", -1);  // -1 to get trailing empty lines represented.
      String line = lines[start.getLine() - 1];
      String arrow = CharBuffer.allocate(start.getColumn() - 1).toString().replace('\0', ' ') + '^';
      return name + ":" + (start.getLine() + 1) + ":" + start.getColumn() + "\n" + line
          + '\n' + arrow;
    }

    private ProguardRuleParserException parseError(String message) {
      return new ProguardRuleParserException(message, snippetForPosition(), origin, getPosition());
    }

    private ProguardRuleParserException parseError(String message, Throwable cause) {
      return new ProguardRuleParserException(message, snippetForPosition(), origin, getPosition(),
          cause);
    }

    private ProguardRuleParserException parseError(String message, TextPosition start,
        Throwable cause) {
      return new ProguardRuleParserException(message, snippetForPosition(start),
          origin, getPostion(start), cause);
    }

    private ProguardRuleParserException parseError(String message, TextPosition start) {
      return new ProguardRuleParserException(message, snippetForPosition(start),
          origin, getPostion(start));
    }

    private void warnIgnoringOptions(String optionName, TextPosition start) {
      reporter.warning(new StringDiagnostic(
          "Ignoring option: -" + optionName,
          origin,
          getPostion(start)));
    }

    private void warnOverridingOptions(String optionName, String victim, TextPosition start) {
      reporter.warning(
          new StringDiagnostic("Option -" + optionName + " overrides -" + victim,
              origin, getPostion(start)));
    }

    private Position getPostion(TextPosition start) {
      if (start.getOffset() == position) {
        return start;
      } else {
        return new TextRange(start, getPosition());
      }
    }

    private TextPosition getPosition() {
      return new TextPosition(position, line, getColumn());
    }

    private int getColumn() {
      return position - lineStartPosition + 1 /* column starts at 1 */;
    }
  }
}
