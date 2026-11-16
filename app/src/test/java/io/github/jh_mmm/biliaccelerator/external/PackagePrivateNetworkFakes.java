package io.github.jh_mmm.biliaccelerator.external;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class PackagePrivateRequest {
    private final String methodName;
    private final String requestUrl;

    PackagePrivateRequest(String methodName, String requestUrl) {
        this.methodName = methodName;
        this.requestUrl = requestUrl;
    }

    public String method() {
        return methodName;
    }

    public String url() {
        return requestUrl;
    }
}

final class PackagePrivateResponseBody {
    private final String text;

    PackagePrivateResponseBody(String text) {
        this.text = text;
    }

    public String string() {
        return text;
    }
}

final class PackagePrivateResponse {
    private final boolean ok;
    private final PackagePrivateResponseBody responseBody;
    boolean closed = false;

    PackagePrivateResponse(boolean ok, String bodyText) {
        this.ok = ok;
        this.responseBody = new PackagePrivateResponseBody(bodyText);
    }

    public boolean isSuccessful() {
        return ok;
    }

    public PackagePrivateResponseBody body() {
        return responseBody;
    }

    public void close() {
        closed = true;
    }
}

final class PackagePrivateCall {
    private final PackagePrivateResponse response;

    PackagePrivateCall(PackagePrivateResponse response) {
        this.response = response;
    }

    public PackagePrivateResponse execute() {
        return response;
    }
}

final class PackagePrivateMethodDescriptor {
    private final String fullMethodName;

    PackagePrivateMethodDescriptor(String fullMethodName) {
        this.fullMethodName = fullMethodName;
    }

    public String getFullMethodName() {
        return fullMethodName;
    }
}

final class PackagePrivateCallOptions {
    private final long deadlineValue;
    private final TimeUnit deadlineUnit;

    PackagePrivateCallOptions(long deadlineValue, TimeUnit deadlineUnit) {
        this.deadlineValue = deadlineValue;
        this.deadlineUnit = deadlineUnit;
    }

    public PackagePrivateCallOptions withDeadlineAfter(long duration, TimeUnit unit) {
        return new PackagePrivateCallOptions(duration, unit);
    }

    public long getDeadlineValue() {
        return deadlineValue;
    }

    public TimeUnit getDeadlineUnit() {
        return deadlineUnit;
    }
}

final class PackagePrivateListenableFuture<V> extends CompletableFuture<V> {
    public void addListener(Runnable listener, Executor executor) {
        whenComplete((v, ex) -> executor.execute(listener));
    }
}

public final class PackagePrivateNetworkFakes {
    private PackagePrivateNetworkFakes() {}

    public static final class Client {
        public int callCount = 0;
        private PackagePrivateResponse lastResponse;

        public PackagePrivateCall newCall(Object request) {
            callCount++;
            lastResponse = new PackagePrivateResponse(true, refreshedJson);
            return new PackagePrivateCall(lastResponse);
        }

        private final String refreshedJson;

        public Client(String refreshedJson) {
            this.refreshedJson = refreshedJson;
        }

        public boolean isLastResponseClosed() {
            return lastResponse != null && lastResponse.closed;
        }
    }

    public static Object newRequest(String method, String url) {
        return new PackagePrivateRequest(method, url);
    }

    public static Object newMethodDescriptor(String fullMethodName) {
        return new PackagePrivateMethodDescriptor(fullMethodName);
    }

    public static Object newCallOptions() {
        return new PackagePrivateCallOptions(0L, TimeUnit.SECONDS);
    }

    public static long readCallOptionsDeadline(Object callOptions) {
        return ((PackagePrivateCallOptions) callOptions).getDeadlineValue();
    }

    public static <V> CompletableFuture<V> newListenableFuture() {
        return new PackagePrivateListenableFuture<>();
    }
}
