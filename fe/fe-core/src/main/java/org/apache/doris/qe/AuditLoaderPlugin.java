// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.qe;

import org.apache.doris.common.Config;
import org.apache.doris.common.util.DigitalVersion;
import org.apache.doris.plugin.AuditEvent;
import org.apache.doris.plugin.AuditPlugin;
import org.apache.doris.plugin.Plugin;
import org.apache.doris.plugin.PluginContext;
import org.apache.doris.plugin.PluginException;
import org.apache.doris.plugin.PluginInfo;
import org.apache.doris.plugin.PluginInfo.PluginType;

import com.google.common.collect.Queues;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/*
 * This plugin will load audit log to specified doris table at specified interval
 */
public class AuditLoaderPlugin extends Plugin implements AuditPlugin {
    private static final Logger LOG = LogManager.getLogger(AuditLoaderPlugin.class);

    private static final ThreadLocal<SimpleDateFormat> dateFormatContainer = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    private StringBuilder generalLogBuffer = new StringBuilder();
    private StringBuilder slowLogBuffer = new StringBuilder();
    private long lastLoadTimeGeneralLog = 0;
    private long lastLoadTimeSlowLog = 0;
    private boolean enableGeneralLog = false;
    private boolean enableSlowLog = false;

    private BlockingQueue<AuditEvent> auditEventQueue;
    private DorisStreamLoader streamLoader;
    private Thread loadThread;

    private volatile boolean isClosed = false;
    private volatile boolean isInit = false;
    private final PluginInfo pluginInfo;
    // the identity of FE which run this plugin
    public String feIdentity = "";

    public AuditLoaderPlugin() {
        pluginInfo = new PluginInfo("__builtin_AuditLoader", PluginType.AUDIT,
                "load audit log to olap table", DigitalVersion.fromString("1.0.0"),
                DigitalVersion.fromString("1.8.0"), AuditLoaderPlugin.class.getName(), null, null);
    }

    public PluginInfo getPluginInfo() {
        return pluginInfo;
    }

    @Override
    public void setEnableLog(boolean enableGeneralLog, boolean enableSlowLog) {
        this.enableGeneralLog = enableGeneralLog;
        this.enableSlowLog = enableSlowLog;
    }

    @Override
    public void init(PluginInfo info, PluginContext ctx) throws PluginException {
        super.init(info, ctx);

        synchronized (this) {
            if (isInit) {
                return;
            }
            this.lastLoadTimeGeneralLog = System.currentTimeMillis();
            this.lastLoadTimeSlowLog = System.currentTimeMillis();

            feIdentity = ctx.getFeIdentity();

            this.auditEventQueue = Queues.newLinkedBlockingDeque(Config.max_queue_size);
            this.streamLoader = new DorisStreamLoader(feIdentity);
            this.loadThread = new Thread(new LoadWorker(this.streamLoader), "audit loader thread");
            this.loadThread.start();

            isInit = true;
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        isClosed = true;

        if (loadThread != null) {
            try {
                loadThread.join();
            } catch (InterruptedException e) {
                LOG.debug("encounter exception when closing the audit loader", e);
            }
        }
    }

    public boolean eventFilter(AuditEvent.EventType type) {
        return type == AuditEvent.EventType.AFTER_QUERY;
    }

    public void exec(AuditEvent event) {
        try {
            auditEventQueue.add(event);
        } catch (Exception e) {
            // In order to ensure that the system can run normally, here we directly
            // discard the current audit_event. If this problem occurs frequently,
            // improvement can be considered.
            LOG.debug("encounter exception when putting current audit batch, discard current audit event", e);
        }
    }

    private void assembleAudit(AuditEvent event) {
        if (enableSlowLog && event.queryTime > Config.qe_slow_log_ms) {
            fillLogBuffer(event, slowLogBuffer);
        }
        if (enableGeneralLog) {
            fillLogBuffer(event, generalLogBuffer);
        }
    }

    private void fillLogBuffer(AuditEvent event, StringBuilder logBuffer) {
        logBuffer.append(event.queryId).append("\t");
        logBuffer.append(longToTimeString(event.timestamp)).append("\t");
        logBuffer.append(event.clientIp).append("\t");
        logBuffer.append(event.user).append("\t");
        logBuffer.append(event.db).append("\t");
        logBuffer.append(event.state).append("\t");
        logBuffer.append(event.queryTime).append("\t");
        logBuffer.append(event.scanBytes).append("\t");
        logBuffer.append(event.scanRows).append("\t");
        logBuffer.append(event.returnRows).append("\t");
        logBuffer.append(event.stmtId).append("\t");
        logBuffer.append(event.isQuery ? 1 : 0).append("\t");
        logBuffer.append(event.feIp).append("\t");
        logBuffer.append(event.cpuTimeMs).append("\t");
        logBuffer.append(event.sqlHash).append("\t");
        logBuffer.append(event.sqlDigest).append("\t");
        logBuffer.append(event.peakMemoryBytes).append("\t");
        // trim the query to avoid too long
        // use `getBytes().length` to get real byte length
        String stmt = truncateByBytes(event.stmt).replace("\n", " ").replace("\t", " ");
        LOG.debug("receive audit event with stmt: {}", stmt);
        logBuffer.append(stmt).append("\n");
    }

    private String truncateByBytes(String str) {
        int maxLen = Math.min(Config.max_stmt_length, str.getBytes().length);
        if (maxLen >= str.getBytes().length) {
            return str;
        }
        Charset utf8Charset = Charset.forName("UTF-8");
        CharsetDecoder decoder = utf8Charset.newDecoder();
        byte[] sb = str.getBytes();
        ByteBuffer buffer = ByteBuffer.wrap(sb, 0, maxLen);
        CharBuffer charBuffer = CharBuffer.allocate(maxLen);
        decoder.onMalformedInput(CodingErrorAction.IGNORE);
        decoder.decode(buffer, charBuffer, true);
        decoder.flush(charBuffer);
        return new String(charBuffer.array(), 0, charBuffer.position());
    }

    private void loadIfNecessary(DorisStreamLoader loader, boolean slowLog) {
        StringBuilder logBuffer = slowLog ? slowLogBuffer : generalLogBuffer;
        long lastLoadTime = slowLog ? lastLoadTimeSlowLog : lastLoadTimeGeneralLog;
        long currentTime = System.currentTimeMillis();

        if (logBuffer.length() >= Config.max_batch_size
                || currentTime - lastLoadTime >= Config.max_batch_interval_sec * 1000) {
            // begin to load
            try {
                DorisStreamLoader.LoadResponse response = loader.loadBatch(logBuffer, slowLog);
                LOG.debug("audit loader response: {}", response);
            } catch (Exception e) {
                LOG.debug("encounter exception when putting current audit batch, discard current batch", e);
            } finally {
                // make a new string builder to receive following events.
                resetLogBufferAndLastLoadTime(currentTime, slowLog);
            }
        }

        return;
    }

    private void resetLogBufferAndLastLoadTime(long currentTime, boolean slowLog) {
        if (slowLog) {
            this.slowLogBuffer = new StringBuilder();
            lastLoadTimeSlowLog = currentTime;
        } else {
            this.generalLogBuffer = new StringBuilder();
            lastLoadTimeGeneralLog = currentTime;
        }

        return;
    }

    private class LoadWorker implements Runnable {
        private DorisStreamLoader loader;

        public LoadWorker(DorisStreamLoader loader) {
            this.loader = loader;
        }

        public void run() {
            while (!isClosed) {
                try {
                    AuditEvent event = auditEventQueue.poll(5, TimeUnit.SECONDS);
                    if (event != null) {
                        assembleAudit(event);
                        if (enableSlowLog) {
                            // process slow logs
                            loadIfNecessary(loader, true);
                        }
                        if (enableGeneralLog) {
                            // process general logs
                            loadIfNecessary(loader, false);
                        }
                    }
                } catch (InterruptedException ie) {
                    LOG.debug("encounter exception when loading current audit batch", ie);
                } catch (Exception e) {
                    LOG.error("run audit logger error:", e);
                }
            }
        }
    }

    public static String longToTimeString(long timeStamp) {
        if (timeStamp <= 0L) {
            return "1900-01-01 00:00:00";
        }
        return dateFormatContainer.get().format(new Date(timeStamp));
    }
}
