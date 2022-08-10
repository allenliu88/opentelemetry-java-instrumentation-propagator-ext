/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.ext.allen.propagator;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.apache.commons.lang3.StringUtils;

/**
 * Implementation of the B3 propagation protocol. See <a
 * href=https://github.com/openzipkin/b3-propagation>openzipkin/b3-propagation</a>.
 *
 * <p>Also see <a
 * href=https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/context/api-propagators.md#b3-requirements>B3
 * Requirements</a>
 *
 * <p>To register the default B3 propagator, which injects a single header, use:
 *
 * <pre>{@code
 * OpenTelemetry.setPropagators(
 *   DefaultContextPropagators
 *     .builder()
 *     .addTextMapPropagator(B3Propagator.injectingSingleHeader())
 *     .build());
 * }</pre>
 *
 * <p>To register a B3 propagator that injects multiple headers, use:
 *
 * <pre>{@code
 * OpenTelemetry.setPropagators(
 *   DefaultContextPropagators
 *     .builder()
 *     .addTextMapPropagator(B3Propagator.injectingMultiHeaders())
 *     .build());
 * }</pre>
 */
@Immutable
public final class B3MultiPropagatorExt implements TextMapPropagator {
  static final String TRACE_ID_HEADER = "X-B3-TraceId";
  static final String SPAN_ID_HEADER = "X-B3-SpanId";
  static final String SAMPLED_HEADER = "X-B3-Sampled";
  static final String DEBUG_HEADER = "X-B3-Flags";
  static final ContextKey<Boolean> DEBUG_CONTEXT_KEY = ContextKey.named("b3-debug");
  static final String MULTI_HEADER_DEBUG = "1";

  // Multiple headers fields
  private static final Collection<String> FIELDS =
      Collections.unmodifiableList(Arrays.asList(TRACE_ID_HEADER, SPAN_ID_HEADER, SAMPLED_HEADER));
  private static final B3MultiPropagatorExt INSTANCE = new B3MultiPropagatorExt();

  private static final Logger logger = Logger.getLogger(B3MultiPropagatorExt.class.getName());

  // Propagate headers cache
  private static final String PROPAGATE_REQUEST_HEADERS_CACHE_KEY = "PROPAGATE_REQUEST_HEADERS";
  private final AtomicBoolean propagateRequestHeadersCacheInitFlag = new AtomicBoolean(false);
  private final Map<String, ContextKey> propagateRequestHeadersCache = new ConcurrentHashMap<>();

  private B3MultiPropagatorExt() {
    // singleton
  }

  public static B3MultiPropagatorExt getInstance() {
    return INSTANCE;
  }

  @Override
  public Collection<String> fields() {
    return FIELDS;
  }

  @Override
  public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter) {
    logger.info("inject: ============================");
    if (context == null) {
      logger.info("context in inject is null.");
      return;
    }
    if (setter == null) {
      logger.info("setter in inject is null.");
      return;
    }

    SpanContext spanContext = Span.fromContext(context).getSpanContext();
    if (!spanContext.isValid()) {
      logger.info("span context is invalid.");
      return;
    }

    logger.info(
        "span context in inject[traceId="
            + spanContext.getTraceId()
            + ", spanId="
            + spanContext.getSpanId());

    String sampled = spanContext.isSampled() ? Common.TRUE_INT : Common.FALSE_INT;

    if (Boolean.TRUE.equals(context.get(DEBUG_CONTEXT_KEY))) {
      setter.set(carrier, DEBUG_HEADER, Common.TRUE_INT);
      sampled = Common.TRUE_INT;
    }

    setter.set(carrier, TRACE_ID_HEADER, spanContext.getTraceId());
    setter.set(carrier, SPAN_ID_HEADER, spanContext.getSpanId());
    setter.set(carrier, SAMPLED_HEADER, sampled);

    // Propagate Custom Request Headers
    this.injectCustomRequestHeaders(context, carrier, setter);
  }

  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    logger.info("extract: ============================");

    // Propagate Custom Request Headers
    context = this.extractCustomRequestHeaders(context, carrier, getter);

    String traceId = getter.get(carrier, TRACE_ID_HEADER);
    if (!Common.isTraceIdValid(traceId)) {
      logger.fine(
          "Invalid TraceId in B3 header: " + traceId + "'. Returning INVALID span context.");
      return context;
    }

    String spanId = getter.get(carrier, SPAN_ID_HEADER);
    if (!Common.isSpanIdValid(spanId)) {
      logger.fine("Invalid SpanId in B3 header: " + spanId + "'. Returning INVALID span context.");
      return context;
    }

    // if debug flag is set, then set sampled flag, and also set B3 debug to true in the context
    // for onward use by B3 injector
    if (MULTI_HEADER_DEBUG.equals(getter.get(carrier, DEBUG_HEADER))) {
      return Optional.of(
              context
                  .with(DEBUG_CONTEXT_KEY, true)
                  .with(Span.wrap(Common.buildSpanContext(traceId, spanId, Common.TRUE_INT))))
          .orElse(context);
    }

    String sampled = getter.get(carrier, SAMPLED_HEADER);
    return Optional.of(context.with(Span.wrap(Common.buildSpanContext(traceId, spanId, sampled))))
        .orElse(context);
  }

  /**
   * 提取客户化请求头信息，并设置到Context中
   *
   * @param context 上下文
   * @param carrier Carrier
   * @param getter Getter
   * @return 上下文
   * @param <C> Carrier
   */
  private <C> Context extractCustomRequestHeaders(
      Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    // Request Id
    String requestId = getter.get(carrier, Common.REQUEST_ID_HEADER);
    Common.infoWithFlag(logger, "X-Request-Id header in extractCustomRequestHeaders: " + requestId);
    if (StringUtils.isNotBlank(requestId)) {
      context = context.with(Common.REQUEST_ID_CONTEXT_KEY, requestId);
    }

    // 客户化请求头
    Set<Map.Entry<String, ContextKey>> propagateRequestHeaders = this.getPropagateRequestHeaders();
    for (Map.Entry<String, ContextKey> entry : propagateRequestHeaders) {
      String requestHeader = entry.getKey();
      ContextKey<String> requestHeaderKey = entry.getValue();

      if (Common.REQUEST_ID_HEADER.equalsIgnoreCase(requestHeader)) {
        // 屏蔽掉默认的X-Request-Id
        continue;
      }

      // 其他可配置请求头
      String value = getter.get(carrier, requestHeader);
      Common.infoWithFlag(
          logger,
          "in extractCustomRequestHeaders: request header = "
              + requestHeader
              + ", value = "
              + value);
      if (StringUtils.isNotBlank(value)) {
        context = context.with(requestHeaderKey, value);
      }
    }

    return context;
  }

  /**
   * 提取客户化请求头信息，并设置到Context中
   *
   * @param context 上下文
   * @param carrier Carrier
   * @param setter Setter
   * @return void
   * @param <C> Carrier
   */
  private <C> void injectCustomRequestHeaders(
      Context context, @Nullable C carrier, TextMapSetter<C> setter) {
    String requestId = context.get(Common.REQUEST_ID_CONTEXT_KEY);
    Common.infoWithFlag(logger, "X-Request-Id header in injectCustomRequestHeaders: " + requestId);
    if (StringUtils.isNotBlank(requestId)) {
      setter.set(carrier, Common.REQUEST_ID_HEADER, requestId);
    }

    // 客户化请求头
    Set<Map.Entry<String, ContextKey>> propagateRequestHeaders = this.getPropagateRequestHeaders();
    for (Map.Entry<String, ContextKey> entry : propagateRequestHeaders) {
      String requestHeader = entry.getKey();
      ContextKey<String> requestHeaderKey = entry.getValue();

      if (Common.REQUEST_ID_HEADER.equalsIgnoreCase(requestHeader)) {
        // 屏蔽掉默认的X-Request-Id
        continue;
      }

      // 其他可配置请求头
      String value = context.get(requestHeaderKey);
      Common.infoWithFlag(
          logger,
          "in injectCustomRequestHeaders: request header = "
              + requestHeader
              + ", value = "
              + value);
      if (StringUtils.isNotBlank(value)) {
        setter.set(carrier, requestHeader, value);
      }
    }
  }

  /**
   * 获取自定义请求头数据
   *
   * @return 自定义请求头数据
   */
  private Set<Map.Entry<String, ContextKey>> getPropagateRequestHeaders() {
    if (this.propagateRequestHeadersCacheInitFlag.compareAndSet(Boolean.FALSE, Boolean.TRUE)) {
      // 回源获取原始数据
      List<String> ret =
          InstrumentationConfig.get()
              .getList(Common.PROPAGATE_REQUEST_HEADERS_PROP_KEY, Collections.emptyList());
      ret.forEach(
          item ->
              this.propagateRequestHeadersCache.putIfAbsent(item, Common.buildContextKey(item)));
    }

    // 缓存数据已经初始化
    Common.infoWithFlag(
        logger,
        "in getPropagateRequestHeaders: propagateRequestHeaders=["
            + this.propagateRequestHeadersCache.keySet().stream().collect(Collectors.joining(","))
            + "]");
    return this.propagateRequestHeadersCache.entrySet();
  }
}
