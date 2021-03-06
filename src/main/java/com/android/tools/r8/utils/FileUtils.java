// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.google.common.io.Closer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

public class FileUtils {

  public static final String APK_EXTENSION = ".apk";
  public static final String CLASS_EXTENSION = ".class";
  public static final String DEX_EXTENSION = ".dex";
  public static final String VDEX_EXTENSION = ".vdex";
  public static final String JAR_EXTENSION = ".jar";
  public static final String ZIP_EXTENSION = ".zip";
  public static final String JAVA_EXTENSION = ".java";
  public static final String MODULE_INFO_CLASS = "module-info.class";

  public static boolean isDexFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(DEX_EXTENSION);
  }

  public static boolean isVDexFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(VDEX_EXTENSION);
  }

  public static boolean isClassFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    // Android does not support Java 9 module, thus skip module-info.
    if (name.equals(MODULE_INFO_CLASS)) {
      return false;
    }
    return name.endsWith(CLASS_EXTENSION);
  }

  public static boolean isJarFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(JAR_EXTENSION);
  }

  public static boolean isZipFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(ZIP_EXTENSION);
  }

  public static boolean isApkFile(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(APK_EXTENSION);
  }

  public static boolean isArchive(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(APK_EXTENSION)
        || name.endsWith(JAR_EXTENSION)
        || name.endsWith(ZIP_EXTENSION);
  }

  public static String readTextFile(Path file, Charset charset) throws IOException {
    return new String(Files.readAllBytes(file), charset);
  }

  public static List<String> readAllLines(Path file) throws IOException {
    return Files.readAllLines(file);
  }

  public static void writeTextFile(Path file, List<String> lines) throws IOException {
    Files.write(file, lines);
  }

  public static void writeTextFile(Path file, String... lines) throws IOException {
    Files.write(file, Arrays.asList(lines));
  }

  public static Path validateOutputFile(Path path, Reporter reporter) {
    if (path != null) {
      boolean isJarOrZip = isZipFile(path) || isJarFile(path);
      if (!isJarOrZip  && !(Files.exists(path) && Files.isDirectory(path))) {
        reporter.error(new StringDiagnostic(
            "Invalid output: "
                + path
                + "\nOutput must be a .zip or .jar archive or an existing directory"));
      }
    }
    return path;
  }

  public static OutputStream openPath(
      Closer closer,
      Path file,
      OpenOption... openOptions)
      throws IOException {
    assert file != null;
    return openPathWithDefault(closer, file, null, openOptions);
  }

  public static OutputStream openPathWithDefault(
      Closer closer,
      Path file,
      OutputStream defaultOutput,
      OpenOption... openOptions)
      throws IOException {
    OutputStream mapOut;
    if (file == null) {
      assert defaultOutput != null;
      mapOut = defaultOutput;
    } else {
      mapOut = Files.newOutputStream(file, openOptions);
      closer.register(mapOut);
    }
    return mapOut;
  }

  public static boolean isClassesDexFile(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    if (!name.startsWith("classes") || !name.endsWith(DEX_EXTENSION)) {
      return false;
    }
    String numeral = name.substring("classes".length(), name.length() - DEX_EXTENSION.length());
    if (numeral.isEmpty()) {
      return true;
    }
    char c0 = numeral.charAt(0);
    if (numeral.length() == 1) {
      return '2' <= c0 && c0 <= '9';
    }
    if (c0 < '1' || '9' < c0) {
      return false;
    }
    for (int i = 1; i < numeral.length(); i++) {
      char c = numeral.charAt(i);
      if (c < '0' || '9' < c) {
        return false;
      }
    }
    return true;
  }

  public static void writeToFile(Path output, OutputStream defValue, byte[] contents)
      throws IOException {
    try (Closer closer = Closer.create()) {
      OutputStream outputStream =
          openPathWithDefault(
              closer,
              output,
              defValue,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      outputStream.write(contents);
    }
  }
}
