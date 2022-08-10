/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.ext.allen.propagator;

import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurablePropagatorProvider;

/**
 * A {@link ConfigurablePropagatorProvider} which allows enabling the B3-multi-Ext propagator with
 * the propagator name {@code b3multi-ext}.
 */
// @AutoService(ConfigurablePropagatorProvider.class)
public final class B3MultiConfigurablePropagatorExt implements ConfigurablePropagatorProvider {
  @Override
  public TextMapPropagator getPropagator(ConfigProperties config) {
    return B3MultiPropagatorExt.getInstance();
  }

  @Override
  public String getName() {
    return "b3multi-ext";
  }
}
