// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.internal;

import com.android.tools.r8.CompilationException;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.R8RunArtTestsTest.CompilerUnderTest;
import com.android.tools.r8.shaking.ProguardRuleParserException;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class GMSCoreDeployJarVerificationTest extends GMSCoreCompilationTestBase {

  public AndroidApp buildFromDeployJar(
      CompilerUnderTest compiler, CompilationMode mode, String base, boolean hasReference)
      throws ExecutionException, IOException, ProguardRuleParserException, CompilationException,
      CompilationFailedException {
    return runAndCheckVerification(
        compiler, mode, hasReference ? base + REFERENCE_APK : null, null, base + DEPLOY_JAR);
  }


  public AndroidApp buildFromDeployJar(
      CompilerUnderTest compiler, CompilationMode mode, String base, boolean hasReference,
      Consumer<InternalOptions> optionsConsumer)
      throws ExecutionException, IOException, ProguardRuleParserException, CompilationException,
      CompilationFailedException {
    return runAndCheckVerification(
        compiler,
        mode,
        hasReference ? base + REFERENCE_APK : null,
        null,
        optionsConsumer,
        Collections.singletonList(base + DEPLOY_JAR));
  }
}
