package ru.fridorin.translator.util;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class BoundedHttpBody {
    private BoundedHttpBody() { }

    public static HttpResponse.BodyHandler<String> ofUtf8String(int maxBytes) {
        return headers -> HttpResponse.BodySubscribers.mapping(
            new LimitedSubscriber(maxBytes, headers.headers().firstValueAsLong("Content-Length").orElse(-1)),
            bytes -> {
                try {
                    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException error) {
                    throw new CompletionException(error);
                }
            });
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maxBytes;
        private final long contentLength;
        private Flow.Subscription subscription;
        private int received;
        private boolean stopped;

        private LimitedSubscriber(int maxBytes, long contentLength) {
            this.maxBytes = maxBytes;
            this.contentLength = contentLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (contentLength > maxBytes) {
                reject();
            } else {
                delegate.onSubscribe(subscription);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (stopped) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - received) {
                    reject();
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!stopped) delegate.onError(error);
        }

        @Override
        public void onComplete() {
            if (!stopped) delegate.onComplete();
        }

        private void reject() {
            stopped = true;
            subscription.cancel();
            delegate.onError(new IOException("HTTP response exceeded the byte limit"));
        }
    }
}
