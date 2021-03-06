/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.tchannel.tracing;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.uber.jaeger.Span;
import com.uber.jaeger.SpanContext;
import com.uber.jaeger.Tracer;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.samplers.ConstSampler;
import com.uber.jaeger.samplers.Sampler;
import com.uber.tchannel.api.SubChannel;
import com.uber.tchannel.api.TChannel;
import com.uber.tchannel.api.TFuture;
import com.uber.tchannel.api.handlers.JSONRequestHandler;
import com.uber.tchannel.api.handlers.ThriftAsyncRequestHandler;
import com.uber.tchannel.api.handlers.ThriftRequestHandler;
import com.uber.tchannel.messages.JSONSerializer;
import com.uber.tchannel.messages.JsonRequest;
import com.uber.tchannel.messages.JsonResponse;
import com.uber.tchannel.messages.Request;
import com.uber.tchannel.messages.ThriftRequest;
import com.uber.tchannel.messages.ThriftResponse;
import com.uber.tchannel.messages.generated.Example;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.opentracing.tag.Tags;
import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * This test validates tracing context propagation through multiple network hops.
 */
@RunWith(Parameterized.class)
public class TracingPropagationTest {

    private static final String BAGGAGE_KEY = "baggage-key";
    private static final String CUSTOM_BAGGAGE_PARAM_KEY = "custom-baggage-param";

    private static Tracer tracer;
    private static InMemoryReporter reporter;
    private static TracingContext tracingContext;
    private static TChannel tchannel;
    private static SubChannel subChannel;

    private final String forwardEncodings;
    private final boolean sampled;
    private final String customBaggage;

    public TracingPropagationTest(String forwardEncodings, boolean sampled, String customBaggage) {
        this.forwardEncodings = forwardEncodings;
        this.sampled = sampled;
        this.customBaggage = customBaggage;
    }

    @Parameters(name = "{index}: encodings({0}), sampled({1}), customBaggage({2})")
    public static Collection<Object[]> data() {
        String[] encodings = { "json", "thrift", "thriftAsync" };
        boolean[] sampling = { true, false };
        String[] customBaggageParams = { "pass", null, "failServerSide" };
        List<Object[]> data = new ArrayList<>();
        for (String encoding1 : encodings) {
            for (String encoding2 : encodings) {
                for (boolean sampled: sampling) {
                    for (String customBaggage : customBaggageParams)
                    data.add(new Object[] { encoding1 + ',' + encoding2, sampled, customBaggage });
                }
            }
        }
        return data;
    }

    @BeforeClass
    public static void setUp() throws Exception {
        reporter = new InMemoryReporter();
        Sampler sampler = new ConstSampler(true);
        tracer = new Tracer.Builder("tchannel-name", reporter, sampler).build();

        tracingContext = new CustomTracingContext();

        tchannel = new TChannel.Builder("tchannel-name")
                .setServerHost(InetAddress.getByName(null))
                .setTracer(tracer)
                .setTracingContext(tracingContext)
                .build();

        subChannel = tchannel.makeSubChannel("tchannel-name")
                .register("endpoint", new JSONHandler())
                .register("Behavior::trace", new ThriftHandler())
                .register("Behavior::asynctrace", new ThriftAsyncHandler());

        tchannel.listen();
    }

    @AfterClass
    public static void tearDown() {
        reporter.close();
        tchannel.shutdown();
    }

    @Before
    public void setUpInstance() {
        tracingContext.clear();
        reporter.clear();
    }

    static class TraceResponse {
        String traceId;
        boolean sampled;
        String baggage;
        TraceResponse downstream;

        @Override
        public String toString() {
            return "TraceResponse{" +
                    "traceId='" + traceId + '\'' +
                    ", sampled=" + sampled +
                    ", baggage='" + baggage + '\'' +
                    ", downstream=" + downstream +
                    '}';
        }
    }

    private static class JSONHandler extends JSONRequestHandler<String, TraceResponse> {
        @Override
        public JsonResponse<TraceResponse> handleImpl(JsonRequest<String> request) {
            String encodings = request.getBody(String.class);
            TraceResponse response = observeSpanAndDownstream(encodings);
            return new JsonResponse.Builder<TraceResponse>(request)
                    .setTransportHeaders(request.getTransportHeaders())
                    .setBody(response)
                    .build();
        }
    }

    private static class ThriftHandler extends ThriftRequestHandler<Example, Example> {
        @Override
        public ThriftResponse<Example> handleImpl(ThriftRequest<Example> request) {
            String encodings = request.getBody(Example.class).getAString();
            TraceResponse response = observeSpanAndDownstream(encodings);
            ByteBuf bytes = new JSONSerializer().encodeBody(response);
            Example thriftResponse = new Example(new String(bytes.array(), StandardCharsets.UTF_8), 0);
            bytes.release();
            return new ThriftResponse.Builder<Example>(request)
                    .setTransportHeaders(request.getTransportHeaders())
                    .setBody(thriftResponse)
                    .build();
        }
    }

    private static class ThriftAsyncHandler extends ThriftAsyncRequestHandler<Example, Example> {
        @Override
        public ListenableFuture<ThriftResponse<Example>> handleImpl(final ThriftRequest<Example> request) {
            // To make downstream calls the original thread should be released therefore a future is
            // setup and returned. This also simulates the behavior of a real handler impl better.
            final SettableFuture<ThriftResponse<Example>> responseFuture = SettableFuture.create();
            final Span span = (Span) tracingContext.currentSpan();
            Executors.newSingleThreadExecutor().submit(new Callable<Object>() {
                @Override
                public Object call() {
                    try {
                        // continue the same span
                        tracingContext.pushSpan(span);
                        String encodings = request.getBody(Example.class).getAString();
                        TraceResponse traceResponse = observeSpanAndDownstream(encodings);
                        ByteBuf bytes = new JSONSerializer().encodeBody(traceResponse);
                        Example thriftResponse = new Example(new String(bytes.array(), StandardCharsets.UTF_8), 0);
                        bytes.release();
                        ThriftResponse<Example> response = new ThriftResponse.Builder<Example>(request)
                                .setTransportHeaders(request.getTransportHeaders())
                                .setBody(thriftResponse)
                                .build();
                        responseFuture.set(response);
                        return new Object();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        fail("Unexpected exception");
                    } finally {
                        tracingContext.popSpan();
                    }
                    return null;
                }
            });
            return responseFuture;
        }
    }

    private static TraceResponse observeSpanAndDownstream(String encodings) {
        Span span = (Span) tracingContext.currentSpan();
        TraceResponse response = new TraceResponse();
        SpanContext context = span.context();
        response.traceId = String.format("%x", context.getTraceId());
        response.sampled = context.isSampled();
        response.baggage = span.getBaggageItem(BAGGAGE_KEY);
        try {
            response.downstream = callDownstream(encodings);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return response;
    }

    private static TraceResponse callDownstream(String encodings) throws Exception {
        if (encodings.isEmpty()) {
            return null;
        }
        int comma = encodings.indexOf(',');
        String encoding, remainingEncodings;
        if (comma == -1) {
            encoding = encodings;
            remainingEncodings = "";
        } else {
            encoding = encodings.substring(0, comma);
            remainingEncodings = encodings.substring(comma + 1);
        }
        switch (encoding) {
            case "json":
                return callDownstreamJSON(remainingEncodings);
            case "thrift":
                return callDownstreamThrift(remainingEncodings, "Behavior::trace");
            case "thriftAsync":
                return callDownstreamThrift(remainingEncodings, "Behavior::asynctrace");
            default:
                throw new IllegalArgumentException(encodings);
        }
    }

    private static TraceResponse callDownstreamJSON(String remainingEncodings) throws Exception {
        JsonRequest<String> request = new JsonRequest
                .Builder<String>("tchannel-name", "endpoint")
                .setTimeout(1000, TimeUnit.MILLISECONDS)
                .setRetryLimit(0)
                .setBody(remainingEncodings)
                .build();

        TFuture<JsonResponse<TraceResponse>> responsePromise = subChannel.send(
                request,
                tchannel.getHost(),
                tchannel.getListeningPort()
        );
        JsonResponse<TraceResponse> response = responsePromise.get();
        try {
            if (response.isError()) {
                throw new ErrorResponseException(response.getError().getMessage());
            }
            return response.getBody(TraceResponse.class);
        } finally {
            response.release();
        }
    }

    private static TraceResponse callDownstreamThrift(String remainingEncodings, String endpoint)
        throws Exception {
        ThriftRequest<Example> request = new ThriftRequest
                .Builder<Example>("tchannel-name", endpoint)
                .setTimeout(1000, TimeUnit.MILLISECONDS)
                .setRetryLimit(0)
                .setBody(new Example(remainingEncodings, 0))
                .build();

        TFuture<ThriftResponse<Example>> responsePromise = subChannel.send(
                request,
                tchannel.getHost(),
                tchannel.getListeningPort()
        );

        ThriftResponse<Example> thriftResponse = responsePromise.get();
        try {
            if (thriftResponse.isError()) {
                throw new ErrorResponseException(thriftResponse.getError().getMessage());
            }
            String json = thriftResponse.getBody(Example.class).getAString();
            ByteBuf byteBuf = UnpooledByteBufAllocator.DEFAULT.buffer(json.length());
            byteBuf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
            TraceResponse response = new JSONSerializer().decodeBody(byteBuf, TraceResponse.class);
            byteBuf.release();
            return response;
        } finally {
            thriftResponse.release();
        }
    }

    @Test
    public void testPropagation() throws Exception {
        Span span = (Span) tracer.buildSpan("root").startManual();
        SpanContext context = span.context();
        String traceId = String.format("%x", context.getTraceId());
        String baggage = "Baggage-" + System.currentTimeMillis();
        span.setBaggageItem(BAGGAGE_KEY, baggage);
        span.setBaggageItem(CUSTOM_BAGGAGE_PARAM_KEY, customBaggage);
        if (!sampled) {
            Tags.SAMPLING_PRIORITY.set(span, 0);
        }
        tracingContext.pushSpan(span);

        try {
            TraceResponse response = callDownstream(forwardEncodings);
            assertEquals("Only requests with 'pass' baggage should pass", "pass", customBaggage);
            List<String> encodings = new ArrayList<>(Arrays.asList(forwardEncodings.split(",")));
            validate(encodings, traceId, baggage, response);
            if (sampled) {
                for (int i = 0; i < 100; i++) {
                    if (reporter.getSpans().size() == 4) {
                        break;
                    }
                    Thread.sleep(10);
                }
                assertEquals(4, reporter.getSpans().size());
            }
        } catch (ErrorResponseException error) {
            if ("pass".equals(customBaggage)) {
                fail("Request with 'pass' baggage must pass but failed with: " + error);
            }
        }
    }

    private void validate(List<String> encodings, String traceId, String baggage, TraceResponse response) {
        assertEquals(traceId, response.traceId);
        assertEquals(sampled, response.sampled);
        assertEquals(baggage, response.baggage);
        encodings.remove(0);
        if (encodings.isEmpty()) return;
        assertNotNull("Expecting downstream response", response.downstream);
        validate(encodings, traceId, baggage, response.downstream);
    }

    /** Custom {@link TracingContext} implementation with request span interceptors. */
    private static class CustomTracingContext extends TracingContext.ThreadLocal implements RequestSpanInterceptor {

        private static final String CUSTOM_BAGGAGE_KEY = "interceptor-test";

        /** This will fail the inbound request in case custom baggage is not "pass". */
        @Override
        public void interceptInbound(
            @NotNull Request request, @NotNull io.opentracing.Span span
        ) throws RuntimeException {
            String customBaggage = span.getBaggageItem(CUSTOM_BAGGAGE_KEY);
            if (!"pass".equals(customBaggage)) {
                throw new UnsupportedOperationException("Failing request on the server side");
            }
        }

        /** This will fail the outbound request in case custom baggage is null. */
        @Override
        public void interceptOutbound(
            @NotNull Request request, @NotNull io.opentracing.Span span
        ) throws IllegalArgumentException {
            String customBaggageParam = span.getBaggageItem(CUSTOM_BAGGAGE_PARAM_KEY);
            if (customBaggageParam == null) {
                throw new IllegalArgumentException("Failing request on the client side");
            }
            span.setBaggageItem(CUSTOM_BAGGAGE_KEY, customBaggageParam);
        }

    }

    private static class ErrorResponseException extends Exception {

        ErrorResponseException(String message) {
            super(message);
        }

    }

}
