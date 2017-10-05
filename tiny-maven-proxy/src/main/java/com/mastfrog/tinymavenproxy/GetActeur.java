/*
 * The MIT License
 *
 * Copyright 2015 Tim Boudreau.
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
package com.mastfrog.tinymavenproxy;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.Concluders;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.LAST_MODIFIED;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.acteurbase.Deferral;
import com.mastfrog.acteurbase.Deferral.Resumer;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.settings.Settings;
import com.mastfrog.tinymavenproxy.Downloader.DownloadReceiver;
import com.mastfrog.tinymavenproxy.GetActeur.ConcludeHttpRequest;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.ACCESS_LOGGER;
import com.mastfrog.url.Path;
import com.mastfrog.util.time.TimeUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import io.netty.util.CharsetUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE - 1)
@Concluders(ConcludeHttpRequest.class)
@Methods({GET, HEAD})
@Description("Download maven artifacts, fetching them from remote repositories "
        + "and caching the results if necessary")
public class GetActeur extends Acteur {

    @Inject
    GetActeur(HttpEvent req, Deferral def, Config config, FileFinder finder, Closables clos, Downloader dl, @Named(ACCESS_LOGGER) Logger accessLog, RequestID id) throws FileNotFoundException {
        if ("true".equals(req.urlParameter("browse")) || "true".equals(req.urlParameter("index"))) {
            reject();
            return;
        }
        Path path = req.path();
        if (path.size() == 0) {
            reject();
            return;
        }
        if (path.toString().contains("..")) {
            setState(new RespondWith(Err.badRequest("Relative paths not allowed")));
            return;
        }
        File file = finder.find(path);
        if (file != null) {
            try (Log<?> log = accessLog.info("fetch")) {
                log.add("path", path).add("id", id).add("cached", true);
                add(Headers.LAST_MODIFIED, TimeUtil.fromUnixTimestamp(file.lastModified()));
                add(Headers.CONTENT_TYPE, findMimeType(path));
                add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE);
                ZonedDateTime inm = req.header(Headers.IF_MODIFIED_SINCE);
                if (inm != null) {
                    long theirs = TimeUtil.toUnixTimestamp(inm.with(ChronoField.MILLI_OF_SECOND, 0));
                    long ours = TimeUtil.toUnixTimestamp(TimeUtil.fromUnixTimestamp(file.lastModified()).with(ChronoField.MILLI_OF_SECOND, 0));
                    if (ours <= theirs) {
                        reply(NOT_MODIFIED);
                        return;
                    }
                }
                setState(new RespondWith(HttpResponseStatus.OK));
                if (req.method() != HEAD) {
                    add(Headers.CONTENT_LENGTH, file.length());
                    setResponseBodyWriter(new FileWriter(file, accessLog));
                }
            }
        } else {
            if (dl.isFailedPath(path)) {
                setState(new RespondWith(404));
                setResponseBodyWriter(ChannelFutureListener.CLOSE);
                return;
            }
            setChunked(true);
            final Resumer r = def.defer();
            ChannelFutureListener l = dl.download(path, id, new DownloadReceiverImpl(r));
            req.channel().closeFuture().addListener(l);
            next();
        }
    }

    private static MediaType findMimeType(Path path) {
        if (path.size() == 0) {
            return MediaType.ANY_APPLICATION_TYPE;
        }
        String file = path.getLastElement().toString();
        int ix = file.lastIndexOf(".");
        if (ix < 0) {
            return MediaType.ANY_APPLICATION_TYPE;
        }
        String ext = file.substring(ix + 1);
        switch (ext) {
            case "html":
                return MediaType.HTML_UTF_8;
            case "jar":
                return MediaType.parse("application/java-archive");
            case "xml":
            case "pom":
                return MediaType.XML_UTF_8;
            case "sha1":
            default:
                return MediaType.PLAIN_TEXT_UTF_8;
        }
    }

    static class ConcludeHttpRequest extends Acteur {

        @Inject
        ConcludeHttpRequest(HttpEvent evt, DownloadResult res, @Named(ACCESS_LOGGER) Logger accessLog, RequestID id, Closables clos, Settings settings) throws FileNotFoundException {
            if (!res.isFail()) {
                setChunked(true);
                try (Log<?> log = accessLog.info("fetch")) {
                    ok();
                    add(Headers.CONTENT_TYPE, findMimeType(evt.path()));
                    if (res.headers.contains(LAST_MODIFIED.name())) {
                        add(LAST_MODIFIED, LAST_MODIFIED.toValue(res.headers.get(LAST_MODIFIED.name())));
                    }
                    if (evt.method() != HEAD) {
                        if (res.isFile()) {
                            add(Headers.CONTENT_LENGTH, res.file.length());
                            setResponseBodyWriter(new FileWriter(res.file, accessLog));
                        } else {
                            setResponseWriter(new Responder(res.buf));
                        }
                    }
                    log.add("path", evt.path()).add("id", id).add("cached", false);
                }
            } else if (res.isFail() && res.buf != null) {
                reply(res.status);
                setResponseWriter(new Responder(res.buf));
            } else {
                reply(NOT_FOUND, "Not cached and could not download " + evt.path());
            }
        }
    }

    static final class FileWriter implements ChannelFutureListener {

        private final File file;
        private final Logger logger;

        FileWriter(File file, Logger logger) {
            this.file = file;
            this.logger = logger;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (f.isSuccess()) {
                FileRegion region = new DefaultFileRegion(file, 0, file.length());
                f.channel().writeAndFlush(region);
            } else if (f.channel().isOpen()) {
                f.channel().close();
                if (f.cause() != null) {
                    logger.warn("download").add("file", file.getPath()).add(f.cause());
                }
            }
        }

    }

    static class Responder extends ResponseWriter {

        private final ByteBuf buf;

        public Responder(ByteBuf buf) {
            this.buf = buf;
        }

        @Override
        public Status write(Event<?> evt, Output out, int iteration) throws Exception {
            out.write(buf);
            return Status.DONE;
        }
    }

    private static class DownloadReceiverImpl implements DownloadReceiver {

        private final Resumer r;

        public DownloadReceiverImpl(Resumer r) {
            this.r = new WrapperResumer(r);
        }

        @Override
        public void receive(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers) {
            r.resume(new DownloadResult(status, buf, headers));
        }

        @Override
        public void failed(final HttpResponseStatus status) {
            r.resume(new DownloadResult(status));
        }

        @Override
        public void receive(HttpResponseStatus status, File file, HttpHeaders headers) {
            r.resume(new DownloadResult(status, file, headers));
        }

        @Override
        public void failed(HttpResponseStatus status, String msg) {
            ByteBuf buf = Unpooled.buffer(msg.length() + 4);
            buf.writeCharSequence(msg, CharsetUtil.UTF_8);
            buf.writeChar('\n');
            r.resume(new DownloadResult(status, buf));
        }
    }

    private static class WrapperResumer implements Resumer {

        private AtomicBoolean resumed = new AtomicBoolean();
        private final Resumer resumer;
        private Exception ex;

        public WrapperResumer(Resumer resumer) {
            this.resumer = resumer;
        }

        @Override
        public void resume(Object... os) {
            if (resumed.compareAndSet(false, true)) {
                ex = new Exception();
                resumer.resume(os);
            } else {
                throw new IllegalStateException("Already resumed", ex);
            }
        }
    }
}
