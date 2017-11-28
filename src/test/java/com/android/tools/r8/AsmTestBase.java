// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AsmTestBase extends TestBase {

  protected void ensureException(String main, Class<? extends Throwable> exceptionClass,
      byte[]... classes) throws Exception {
    ensureExceptionThrown(runOnJava(main, classes), exceptionClass);
    AndroidApp app = buildAndroidApp(classes);
    ensureExceptionThrown(runOnArtRaw(compileWithD8(app), main), exceptionClass);
    ensureExceptionThrown(runOnArtRaw(compileWithR8(app), main), exceptionClass);
    ensureExceptionThrown(
        runOnArtRaw(compileWithR8(app, keepMainProguardConfiguration(main)), main),
        exceptionClass);
  }

  private AndroidApp buildAndroidApp(byte[]... classes) {
    AndroidApp.Builder builder = AndroidApp.builder();
    for (byte[] clazz : classes) {
      builder.addClassProgramData(clazz, Origin.unknown());
    }
    return builder.build();
  }

  private void ensureExceptionThrown(ProcessResult result, Class<? extends Throwable> exception) {
    assertFalse(result.stdout, result.exitCode == 0);
    assertTrue(result.stderr, result.stderr.contains(exception.getCanonicalName()));
  }

  protected ProcessResult runOnJava(String main, byte[]... classes) throws IOException {
    Path file = writeToZip(classes);
    return ToolHelper.runJavaNoVerify(ImmutableList.of(file.toString()), main);
  }

  private Path writeToZip(byte[]... classes) throws IOException {
    NameExtrator nameExtrator = new NameExtrator();
    File result = temp.newFile();
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(result.toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
      for (byte[] clazz : classes) {
        String name = nameExtrator.getName(clazz);
        ZipEntry zipEntry = new ZipEntry(DescriptorUtils.getPathFromJavaType(name));
        zipEntry.setSize(clazz.length);
        out.putNextEntry(zipEntry);
        out.write(clazz);
        out.closeEntry();
      }
    }
    return result.toPath();
  }

  private static class NameExtrator extends ClassLoader {

    public String getName(byte[] clazz) {
      Class loaded = defineClass(clazz, 0, clazz.length);
      return loaded.getCanonicalName();
    }
  }
}