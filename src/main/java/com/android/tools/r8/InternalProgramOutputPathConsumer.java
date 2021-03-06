// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import java.nio.file.Path;

// Internal interface for testing the command setup.
interface InternalProgramOutputPathConsumer extends ProgramConsumer {

  Path internalGetOutputPath();

}
