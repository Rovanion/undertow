/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.conduits;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.server.protocol.http.HttpServerConnection;
import io.undertow.util.Attachable;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HeaderMap;
import org.xnio.IoUtils;
import org.xnio.Pool;
import org.xnio.Pooled;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.ConduitReadableByteChannel;
import org.xnio.conduits.PushBackStreamSourceConduit;
import org.xnio.conduits.StreamSourceConduit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

/**
 * Channel to de-chunkify data
 *
 * @author Stuart Douglas
 */
public class ChunkedStreamSourceConduit extends AbstractStreamSourceConduit<StreamSourceConduit> {

    /**
     * If the response has HTTP footers they are attached to the exchange under this key. They will only be available once the exchange has been fully read.
     */
    @Deprecated
    public static final AttachmentKey<HeaderMap> TRAILERS = HttpAttachments.REQUEST_TRAILERS;

    private final BufferWrapper bufferWrapper;
    private final ConduitListener<? super ChunkedStreamSourceConduit> finishListener;
    private final HttpServerExchange exchange;

    private boolean closed;

    private long remainingAllowed;
    private final ChunkReader chunkReader;

    public ChunkedStreamSourceConduit(final StreamSourceConduit next, final PushBackStreamSourceConduit channel, final Pool<ByteBuffer> pool, final ConduitListener<? super ChunkedStreamSourceConduit> finishListener, Attachable attachable) {
        this(next, new BufferWrapper() {
            @Override
            public Pooled<ByteBuffer> allocate() {
                return pool.allocate();
            }

            @Override
            public void pushBack(Pooled<ByteBuffer> pooled) {
                channel.pushBack(pooled);
            }
        }, finishListener, attachable, null);
    }

    public ChunkedStreamSourceConduit(final StreamSourceConduit next, final HttpServerExchange exchange, final ConduitListener<? super ChunkedStreamSourceConduit> finishListener) {
        this(next, new BufferWrapper() {
            @Override
            public Pooled<ByteBuffer> allocate() {
                return exchange.getConnection().getBufferPool().allocate();
            }

            @Override
            public void pushBack(Pooled<ByteBuffer> pooled) {
                ((HttpServerConnection) exchange.getConnection()).ungetRequestBytes(pooled);
            }
        }, finishListener, exchange, exchange);
    }

    protected ChunkedStreamSourceConduit(final StreamSourceConduit next, final BufferWrapper bufferWrapper, final ConduitListener<? super ChunkedStreamSourceConduit> finishListener, final Attachable attachable, final HttpServerExchange exchange) {
        super(next);
        this.bufferWrapper = bufferWrapper;
        this.finishListener = finishListener;
        this.remainingAllowed = Long.MIN_VALUE;
        this.chunkReader = new ChunkReader<ChunkedStreamSourceConduit>(attachable, HttpAttachments.REQUEST_TRAILERS, finishListener, this);
        this.exchange = exchange;
    }

    public long transferTo(final long position, final long count, final FileChannel target) throws IOException {
        return target.transferFrom(new ConduitReadableByteChannel(this), position, count);
    }

    private void updateRemainingAllowed(final int written) throws IOException {
        if (remainingAllowed == Long.MIN_VALUE) {
            if (exchange == null) {
                return;
            } else {
                long maxEntitySize = exchange.getMaxEntitySize();
                if (maxEntitySize <= 0) {
                    return;
                }
                remainingAllowed = maxEntitySize;
            }
        }
        remainingAllowed -= written;
        if (remainingAllowed < 0) {
            //max entity size is exceeded
            //we need to forcibly close the read side
            try {
                next.terminateReads();
            } catch (IOException e) {
                UndertowLogger.REQUEST_LOGGER.debug("Exception terminating reads due to exceeding max size", e);
            }
            closed = true;
            finishListener.handleEvent(this);
            exchange.setPersistent(false);
            throw UndertowMessages.MESSAGES.requestEntityWasTooLarge(exchange.getMaxEntitySize());
        }
    }

    public long transferTo(final long count, final ByteBuffer throughBuffer, final StreamSinkChannel target) throws IOException {
        return IoUtils.transfer(new ConduitReadableByteChannel(this), count, throughBuffer, target);
    }

    public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
        for (int i = offset; i < length; ++i) {
            if (dsts[i].hasRemaining()) {
                return read(dsts[i]);
            }
        }
        return 0;
    }

    @Override
    public void terminateReads() throws IOException {
        if (!isFinished()) {
            super.terminateReads();
            throw UndertowMessages.MESSAGES.chunkedChannelClosedMidChunk();
        }
    }

    public int read(final ByteBuffer dst) throws IOException {
        long chunkRemaining = chunkReader.getChunkRemaining();
        //we have read the last chunk, we just return EOF
        if (chunkRemaining == -1) {
            return -1;
        }
        if (closed) {
            throw new ClosedChannelException();
        }
        Pooled<ByteBuffer> pooled = bufferWrapper.allocate();
        ByteBuffer buf = pooled.getResource();
        try {
            int r = next.read(buf);
            buf.flip();
            if (r == -1) {
                //Channel is broken, not sure how best to report it
                throw new ClosedChannelException();
            } else if (r == 0) {
                return 0;
            }
            if (chunkRemaining == 0) {
                chunkRemaining = chunkReader.readChunk(buf);
                if (chunkRemaining <= 0) {
                    return (int) chunkRemaining;
                }
            }


            final int originalLimit = dst.limit();
            try {
                //now we may have some stuff in the raw buffer
                //or the raw buffer may be exhausted, and we should read directly into the destination buffer
                //from the next

                int read = 0;
                long chunkInBuffer = Math.min(buf.remaining(), chunkRemaining);
                int remaining = dst.remaining();
                if (chunkInBuffer > remaining) {
                    //it won't fit
                    int orig = buf.limit();
                    buf.limit(buf.position() + remaining);
                    dst.put(buf);
                    buf.limit(orig);
                    chunkRemaining -= remaining;
                    updateRemainingAllowed(remaining);
                    return remaining;
                } else if (buf.hasRemaining()) {
                    int old = buf.limit();
                    buf.limit((int) Math.min(old, buf.position() + chunkInBuffer));
                    try {
                        dst.put(buf);
                    } finally {
                        buf.limit(old);
                    }
                    read += chunkInBuffer;
                    chunkRemaining -= chunkInBuffer;
                }
                //there is still more to read
                //we attempt to just read it directly into the destination buffer
                //adjusting the limit as necessary to make sure we do not read too much
                if (chunkRemaining > 0) {
                    int old = dst.limit();
                    try {
                        if (chunkRemaining < dst.remaining()) {
                            dst.limit((int) (dst.position() + chunkRemaining));
                        }
                        int c = 0;
                        do {
                            c = next.read(dst);
                            if (c > 0) {
                                read += c;
                                chunkRemaining -= c;
                            }
                        } while (c > 0 && chunkRemaining > 0);
                        if (c == -1) {
                            throw new ClosedChannelException();
                        }
                    } finally {
                        dst.limit(old);
                    }
                }
                updateRemainingAllowed(read);
                return read;

            } finally {
                //buffer will be freed if not needed in exitRead
                dst.limit(originalLimit);
            }

        } finally {
            if(chunkRemaining >= 0) {
                chunkReader.setChunkRemaining(chunkRemaining);
            }
            if (buf.hasRemaining()) {
                bufferWrapper.pushBack(pooled);
            } else {
                pooled.free();
            }
        }

    }

    public boolean isFinished() {
        return chunkReader.getChunkRemaining() == -1;
    }

    interface BufferWrapper {

        Pooled<ByteBuffer> allocate();

        void pushBack(Pooled<ByteBuffer> pooled);

    }
}
