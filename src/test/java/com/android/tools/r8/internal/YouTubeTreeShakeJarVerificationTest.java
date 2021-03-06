// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.internal;

import static org.junit.Assert.assertTrue;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.ProgramResource;
import com.android.tools.r8.R8RunArtTestsTest.CompilerUnderTest;
import com.android.tools.r8.utils.AndroidApp;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import org.junit.Test;

public class YouTubeTreeShakeJarVerificationTest extends YouTubeCompilationBase {

  @Test
  public void buildAndTreeShakeFromDeployJar() throws Exception {
    int maxSize = 20000000;
    AndroidApp app = runAndCheckVerification(
        CompilerUnderTest.R8,
        CompilationMode.RELEASE,
        BASE + APK,
        BASE + PG_CONF,
        null,
        // Don't pass any inputs. The input will be read from the -injars in the Proguard
        // configuration file.
        ImmutableList.of());
    int bytes = 0;
    try (Closer closer = Closer.create()) {
      for (ProgramResource dex : app.getDexProgramResourcesForTesting()) {
        bytes += ByteStreams.toByteArray(closer.register(dex.getByteStream())).length;
      }
    }
    assertTrue("Expected max size of " + maxSize + ", got " + bytes, bytes < maxSize);
  }
}
