package com.novelforge.generation;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bound memory while receiving, so the async request deadline also covers the full body. */
final class BoundedModelBody implements HttpResponse.BodySubscriber<byte[]> {
    static final int LIMIT=4_000_000;
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription=subscription; subscription.request(Long.MAX_VALUE);
    }
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining()>LIMIT-bytes.size()) {
                subscription.cancel(); body.completeExceptionally(new BodyTooLarge()); return;
            }
            byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
        }
    }
    public void onError(Throwable error) { body.completeExceptionally(error); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
    static final class BodyTooLarge extends RuntimeException {}
}
