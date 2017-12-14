// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.ClassFileConsumer;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DexIndexedConsumer.ForwardingConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.ProgramConsumer;
import com.android.tools.r8.StringConsumer;
import com.android.tools.r8.origin.Origin;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class AndroidAppConsumers {

  private final AndroidApp.Builder builder = AndroidApp.builder();
  private boolean closed = false;

  private ProgramConsumer programConsumer = null;
  private StringConsumer mainDexListConsumer = null;
  private StringConsumer proguardMapConsumer = null;
  private StringConsumer usageInformationConsumer = null;

  public AndroidAppConsumers(InternalOptions options) {
    options.programConsumer = wrapProgramConsumer(options.programConsumer);
    options.mainDexListConsumer = wrapMainDexListConsumer(options.mainDexListConsumer);
    options.proguardMapConsumer = wrapProguardMapConsumer(options.proguardMapConsumer);
    options.usageInformationConsumer =
        wrapUsageInformationConsumer(options.usageInformationConsumer);
  }

  private ProgramConsumer wrapProgramConsumer(ProgramConsumer consumer) {
    assert programConsumer == null;
    if (consumer instanceof ClassFileConsumer) {
      programConsumer = wrapClassFileConsumer((ClassFileConsumer) consumer);
    } else if (consumer instanceof DexIndexedConsumer) {
      programConsumer = wrapDexIndexedConsumer((DexIndexedConsumer) consumer);
    } else if (consumer instanceof DexFilePerClassFileConsumer) {
      programConsumer = wrapDexFilePerClassFileConsumer((DexFilePerClassFileConsumer) consumer);
    } else {
      // TODO(zerny): Refine API to disallow running without a program consumer.
      assert consumer == null;
      programConsumer = wrapDexIndexedConsumer(null);
    }
    return programConsumer;
  }

  private StringConsumer wrapMainDexListConsumer(StringConsumer consumer) {
    assert mainDexListConsumer == null;
    if (consumer != null) {
      mainDexListConsumer =
          new StringConsumer.ForwardingConsumer(consumer) {
            @Override
            public void accept(String string, DiagnosticsHandler handler) {
              super.accept(string, handler);
              builder.setMainDexListOutputData(string.getBytes(StandardCharsets.UTF_8));
            }
          };
    }
    return mainDexListConsumer;
  }

  private StringConsumer wrapProguardMapConsumer(StringConsumer consumer) {
    assert proguardMapConsumer == null;
    if (consumer != null) {
      proguardMapConsumer =
          new StringConsumer.ForwardingConsumer(consumer) {
            @Override
            public void accept(String string, DiagnosticsHandler handler) {
              super.accept(string, handler);
              builder.setProguardMapData(string);
            }
          };
    }
    return proguardMapConsumer;
  }

  private StringConsumer wrapUsageInformationConsumer(StringConsumer consumer) {
    assert usageInformationConsumer == null;
    if (consumer != null) {
      usageInformationConsumer =
          new StringConsumer.ForwardingConsumer(consumer) {
            @Override
            public void accept(String string, DiagnosticsHandler handler) {
              super.accept(string, handler);
              builder.setDeadCode(string.getBytes(StandardCharsets.UTF_8));
            }
          };
    }
    return usageInformationConsumer;
  }

  private DexIndexedConsumer wrapDexIndexedConsumer(DexIndexedConsumer consumer) {
    return new ForwardingConsumer(consumer) {

      // Sort the files by id so that their order is deterministic. Some tests depend on this.
      private Int2ReferenceSortedMap<DescriptorsWithContents> files =
          new Int2ReferenceAVLTreeMap<>();

      @Override
      public void accept(
          int fileIndex, byte[] data, Set<String> descriptors, DiagnosticsHandler handler) {
        super.accept(fileIndex, data, descriptors, handler);
        addDexFile(fileIndex, data, descriptors);
      }

      @Override
      public void finished(DiagnosticsHandler handler) {
        super.finished(handler);
        closed = true;
        files.forEach((v, d) -> builder.addDexProgramData(d.contents, d.descriptors));
        files = null;
      }

      synchronized void addDexFile(int fileIndex, byte[] data, Set<String> descriptors) {
        files.put(fileIndex, new DescriptorsWithContents(descriptors, data));
      }
    };
  }

  private DexFilePerClassFileConsumer wrapDexFilePerClassFileConsumer(
      DexFilePerClassFileConsumer consumer) {
    return new DexFilePerClassFileConsumer.ForwardingConsumer(consumer) {

      // Sort the files by their name for good measure.
      private TreeMap<String, DescriptorsWithContents> files = new TreeMap<>();

      @Override
      public void accept(
          String primaryClassDescriptor,
          byte[] data,
          Set<String> descriptors,
          DiagnosticsHandler handler) {
        super.accept(primaryClassDescriptor, data, descriptors, handler);
        addDexFile(primaryClassDescriptor, data, descriptors);
      }

      synchronized void addDexFile(
          String primaryClassDescriptor, byte[] data, Set<String> descriptors) {
        files.put(primaryClassDescriptor, new DescriptorsWithContents(descriptors, data));
      }

      @Override
      public void finished(DiagnosticsHandler handler) {
        super.finished(handler);
        closed = true;
        files.forEach((v, d) -> builder.addDexProgramData(d.contents, d.descriptors, v));
        files = null;
      }
    };
  }

  private ClassFileConsumer wrapClassFileConsumer(ClassFileConsumer consumer) {
    return new ClassFileConsumer.ForwardingConsumer(consumer) {

      private List<DescriptorsWithContents> files = new ArrayList<>();

      @Override
      public void accept(byte[] data, String descriptor, DiagnosticsHandler handler) {
        super.accept(data, descriptor, handler);
        addClassFile(data, descriptor);
      }

      synchronized void addClassFile(byte[] data, String descriptor) {
        files.add(new DescriptorsWithContents(Collections.singleton(descriptor), data));
      }

      @Override
      public void finished(DiagnosticsHandler handler) {
        super.finished(handler);
        closed = true;
        files.forEach(
            d -> builder.addClassProgramData(d.contents, Origin.unknown(), d.descriptors));
        files = null;
      }
    };
  }

  public AndroidApp build() {
    assert closed;
    return builder.build();
  }

  private static class DescriptorsWithContents {

    final Set<String> descriptors;
    final byte[] contents;

    private DescriptorsWithContents(Set<String> descriptors, byte[] contents) {
      this.descriptors = descriptors;
      this.contents = contents;
    }
  }
}