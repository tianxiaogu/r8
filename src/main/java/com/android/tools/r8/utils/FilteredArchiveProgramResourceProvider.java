// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.FileUtils.isArchive;
import static com.android.tools.r8.utils.FileUtils.isClassFile;
import static com.android.tools.r8.utils.FileUtils.isDexFile;

import com.android.tools.r8.ProgramResource;
import com.android.tools.r8.ProgramResource.Kind;
import com.android.tools.r8.ProgramResourceProvider;
import com.android.tools.r8.ResourceException;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.origin.ArchiveEntryOrigin;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.shaking.FilteredClassPath;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class FilteredArchiveProgramResourceProvider implements ProgramResourceProvider {

  private final Origin origin;
  private final FilteredClassPath archive;
  private final boolean ignoreDexInArchive;

  FilteredArchiveProgramResourceProvider(FilteredClassPath archive, boolean ignoreDexInArchive) {
    origin = new PathOrigin(archive.getPath());
    this.archive = archive;
    this.ignoreDexInArchive = ignoreDexInArchive;
  }

  private List<ProgramResource> readArchive() throws IOException {
    assert isArchive(archive.getPath());
    List<ProgramResource> dexResources = new ArrayList<>();
    List<ProgramResource> classResources = new ArrayList<>();
    try (ZipFile zipFile = new ZipFile(archive.getPath().toFile())) {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        try (InputStream stream = zipFile.getInputStream(entry)) {
          Path name = Paths.get(entry.getName());
          Origin entryOrigin = new ArchiveEntryOrigin(entry.getName(), origin);
          if (archive.matchesFile(name)) {
            if (isDexFile(name)) {
              if (!ignoreDexInArchive) {
                ProgramResource resource =
                    OneShotByteResource.create(
                        Kind.DEX, entryOrigin, ByteStreams.toByteArray(stream), null);
                dexResources.add(resource);
              }
            } else if (isClassFile(name)) {
              String descriptor = DescriptorUtils.guessTypeDescriptor(name);
              ProgramResource resource =
                  OneShotByteResource.create(
                      Kind.CF,
                      entryOrigin,
                      ByteStreams.toByteArray(stream),
                      Collections.singleton(descriptor));
              classResources.add(resource);
            }
          }
        }
      }
    } catch (ZipException e) {
      throw new CompilationError(
          "Zip error while reading '" + archive + "': " + e.getMessage(), e);
    }
    if (!dexResources.isEmpty() && !classResources.isEmpty()) {
      throw new CompilationError(
          "Cannot create android app from an archive '" + archive
              + "' containing both DEX and Java-bytecode content");
    }
    return !dexResources.isEmpty() ? dexResources : classResources;
  }

  @Override
  public Collection<ProgramResource> getProgramResources() throws ResourceException {
    try {
      return readArchive();
    } catch (IOException e) {
      throw new ResourceException(origin, e);
    }
  }
}
