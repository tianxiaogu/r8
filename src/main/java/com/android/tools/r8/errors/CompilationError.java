// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;

/**
 * Exception to signal an compilation error.
 * <p>
 * This is always an expected error and considered a user input issue. A user-understandable message
 * must be provided.
 */
public class CompilationError extends RuntimeException implements Diagnostic {

  private final Origin origin;
  private final Position position;
  public CompilationError(String message) {
    this(message, Origin.unknown());
  }

  public CompilationError(String message, Throwable cause) {
    this(message, cause, Origin.unknown());
  }

  public CompilationError(String message, Origin origin) {
    this(message, null, origin);
  }

  public CompilationError(String message, Throwable cause, Origin origin) {
    this(message, cause, origin, Position.UNKNOWN);
  }

  public CompilationError(String message, Throwable cause, Origin origin, Position position) {
    super(message, cause);
    this.origin = origin;
    this.position = position;
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public Position getPosition() {
    return position;
  }

  @Override
  public String getDiagnosticMessage() {
    return getMessage();
  }
}