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

package org.apache.doris.plugin;

import org.apache.doris.analysis.InstallPluginStmt;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.UserException;
import org.apache.doris.common.io.Writable;
import org.apache.doris.common.util.PrintableMap;
import org.apache.doris.plugin.PluginInfo.PluginType;
import org.apache.doris.plugin.PluginLoader.PluginStatus;
import org.apache.doris.qe.AuditLoaderPlugin;
import org.apache.doris.qe.AuditLogBuilder;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class PluginMgr implements Writable {
    private static final Logger LOG = LogManager.getLogger(PluginMgr.class);

    public static final String BUILTIN_PLUGIN_PREFIX = "__builtin_";

    private final Map<String, PluginLoader>[] plugins;
    // all dynamic plugins should have unique names,
    private final Set<String> dynamicPluginNames;
    private boolean enableGeneralLog = false;
    private boolean enableSlowLog = false;

    public static String generateOriginCreateTblStmt(String logType, int backendNum) {
        int replicationNum = backendNum > 3 ? 3 : backendNum;
        String replicaNum = String.valueOf(replicationNum);
        String originStmtLogTbl = " ("
                + " query_id varchar(48) comment \"Unique query id\","
                + " `time` datetime not null comment \"Query start time\","
                + " client_ip varchar(32) comment \"Client IP\","
                + " user varchar(64) comment \"User name\","
                + " db varchar(96) comment \"Database of this query\","
                + " state varchar(8) comment \"Query result state. EOF, ERR, OK\","
                + " query_time bigint comment \"Query execution time in millisecond\","
                + " scan_bytes bigint comment \"Total scan bytes of this query\","
                + " scan_rows bigint comment \"Total scan rows of this query\","
                + " return_rows bigint comment \"Returned rows of this query\","
                + " stmt_id int comment \"An incremental id of statement\","
                + " is_query tinyint comment \"Is this statemt a query. 1 or 0\","
                + " frontend_ip varchar(32) comment \"Frontend ip of executing this statement\","
                + " cpu_time_ms bigint comment \"Total scan cpu time in millisecond of this query\","
                + " sql_hash varchar(48) comment \"Hash value for this query\","
                + "sql_digest varchar(48) comment \"Sql digest for this query\","
                + " peak_memory_bytes bigint comment \"Peak memory bytes used on all backends of this query\","
                + " stmt string comment \"The original statement, trimed if longer than 2G\""
                + " ) engine=OLAP"
                + " duplicate key(query_id, `time`, client_ip)"
                + " partition by range(`time`) ()"
                + " distributed by hash(query_id) buckets 1"
                + " properties("
                + " \"dynamic_partition.time_unit\" = \"DAY\","
                + " \"dynamic_partition.start\" = \"-30\","
                + " \"dynamic_partition.end\" = \"3\","
                + " \"dynamic_partition.prefix\" = \"p\","
                + " \"dynamic_partition.buckets\" = \"1\","
                + " \"dynamic_partition.enable\" = \"true\","
                + " \"replication_num\" = \"" + replicaNum + "\""
                + " )";
        String originStmtAuditLogTbl = "create table if not exists ";
        if ("general".equalsIgnoreCase(logType)) {
            originStmtAuditLogTbl += Config.database + "." + Config.general_log_table + originStmtLogTbl;
        } else {
            originStmtAuditLogTbl += Config.database + "." + Config.slow_log_table + originStmtLogTbl;
        }
        return originStmtAuditLogTbl;
    }

    public PluginMgr() {
        plugins = new Map[PluginType.MAX_PLUGIN_TYPE_SIZE];
        for (int i = 0; i < PluginType.MAX_PLUGIN_TYPE_SIZE; i++) {
            plugins[i] = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        }
        dynamicPluginNames = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
    }

    // create the plugin dir if missing
    public void init() throws PluginException {
        File file = new File(Config.plugin_dir);
        if (file.exists() && !file.isDirectory()) {
            throw new PluginException("FE plugin dir " + Config.plugin_dir + " is not a directory");
        }

        if (!file.exists()) {
            if (!file.mkdir()) {
                throw new PluginException("failed to create FE plugin dir " + Config.plugin_dir);
            }
        }

        initBuiltinPlugins();
    }

    private boolean checkPluginNameExist(PluginInfo info) {
        synchronized (plugins) {
            return plugins[info.getTypeId()].containsKey(info.getName());
        }
    }

    private boolean checkDynamicPluginNameExist(String name) {
        synchronized (dynamicPluginNames) {
            return dynamicPluginNames.contains(name);
        }
    }

    private boolean addDynamicPluginNameIfAbsent(String name) {
        synchronized (dynamicPluginNames) {
            return dynamicPluginNames.add(name);
        }
    }

    private boolean removeDynamicPluginName(String name) {
        synchronized (dynamicPluginNames) {
            return dynamicPluginNames.remove(name);
        }
    }

    private void initBuiltinPlugins() {
        // AuditLogBuilder
        AuditLogBuilder auditLogBuilder = new AuditLogBuilder();
        if (!registerBuiltinPlugin(auditLogBuilder.getPluginInfo(), auditLogBuilder)) {
            LOG.warn("failed to register audit log builder");
        }

        // other builtin plugins
    }

    public void enablePlugin(String logType) throws IOException, UserException {
        AuditLoaderPlugin plugin = new AuditLoaderPlugin();
        PluginInfo info = plugin.getPluginInfo();
        PluginLoader pluginLoader = new BuiltinPluginLoader(Config.plugin_dir, info, plugin);

        try {
            boolean enableLog = "general".equalsIgnoreCase(logType) ? enableGeneralLog : enableSlowLog;
            if (checkPluginNameExist(info) && enableLog) {
                throw new UserException(logType + " log has already been enabled");
            }
            if (checkPluginNameExist(info) && !enableGeneralLog && !enableSlowLog) {
                throw new UserException("__builtin_AuditLoader plugin has already been installed");
            }

            if (!enableGeneralLog && !enableSlowLog) {
                pluginLoader.setStatus(PluginStatus.ENABLING);
                // install plugin
                pluginLoader.install();
                pluginLoader.setStatus(PluginStatus.ENABLED);
                plugins[info.getTypeId()].put(info.getName(), pluginLoader);
            }

            setEnableLog(logType, true);
            PluginLoader loader = plugins[info.getTypeId()].get(info.getName());
            loader.getPlugin().setEnableLog(enableGeneralLog, enableSlowLog);
            loader.setPluginInfo(enableGeneralLog, enableSlowLog);
        } catch (IOException | UserException e) {
            pluginLoader.uninstall();
            throw e;
        }
    }

    public void disablePlugin(String logType) throws IOException, UserException {
        for (int i = 0; i < PluginType.MAX_PLUGIN_TYPE_SIZE; i++) {
            if (plugins[i].containsKey("__builtin_AuditLoader")) {
                boolean enableLog = "general".equalsIgnoreCase(logType) ? enableGeneralLog : enableSlowLog;
                if (!enableLog) {
                    throw new DdlException(logType + " log does not enable");
                }

                PluginLoader loader = plugins[i].get("__builtin_AuditLoader");
                if (loader == null) {
                    continue;
                }

                setEnableLog(logType, false);
                loader.getPlugin().setEnableLog(enableGeneralLog, enableSlowLog);
                loader.setPluginInfo(enableGeneralLog, enableSlowLog);

                if (!enableGeneralLog && !enableSlowLog) {
                    loader.pluginUninstallValid();
                    loader.setStatus(PluginStatus.DISABLING);
                    // uninstall plugin
                    loader.uninstall();
                    loader.setStatus(PluginStatus.DISABLED);
                    // disable succeed, remove the plugin
                    plugins[i].remove("__builtin_AuditLoader");
                }
                return;
            }
        }

        throw new DdlException(logType + " log does not enable");
    }

    private void setEnableLog(String logType, boolean enableLog) {
        if ("general".equalsIgnoreCase(logType)) {
            enableGeneralLog = enableLog;
        } else {
            enableSlowLog = enableLog;
        }
    }

    // install a plugin from user's command.
    // install should be successfully, or nothing should be left if failed to install.
    public PluginInfo installPlugin(InstallPluginStmt stmt) throws IOException, UserException {
        PluginLoader pluginLoader = new DynamicPluginLoader(Config.plugin_dir, stmt.getPluginPath(), stmt.getMd5sum());
        pluginLoader.setStatus(PluginStatus.INSTALLING);

        try {
            PluginInfo info = pluginLoader.getPluginInfo();
            if (stmt.getProperties() != null) {
                info.setProperties(stmt.getProperties());
            }

            if (checkDynamicPluginNameExist(info.getName())) {
                throw new UserException("plugin " + info.getName() + " has already been installed.");
            }

            // install plugin
            pluginLoader.install();
            pluginLoader.setStatus(PluginStatus.INSTALLED);

            if (!addDynamicPluginNameIfAbsent(info.getName())) {
                throw new UserException("plugin " + info.getName() + " has already been installed.");
            }
            plugins[info.getTypeId()].put(info.getName(), pluginLoader);

            Env.getCurrentEnv().getEditLog().logInstallPlugin(info);
            LOG.info("install plugin {}", info.getName());
            return info;
        } catch (IOException | UserException e) {
            pluginLoader.uninstall();
            throw e;
        }
    }

    /**
     * Dynamic uninstall plugin.
     * If uninstall failed, the plugin should NOT be removed from plugin manager.
     */
    public PluginInfo uninstallPlugin(String name) throws IOException, UserException {
        if (!checkDynamicPluginNameExist(name)) {
            throw new DdlException("Plugin " + name + " does not exist");
        }

        for (int i = 0; i < PluginType.MAX_PLUGIN_TYPE_SIZE; i++) {
            if (plugins[i].containsKey(name)) {
                PluginLoader loader = plugins[i].get(name);
                if (loader == null) {
                    // this is not a atomic operation, so even if containsKey() is true,
                    // we may still get null object by get() method
                    continue;
                }

                if (!loader.isDynamicPlugin()) {
                    throw new DdlException("Only support uninstall dynamic plugins");
                }

                loader.pluginUninstallValid();
                loader.setStatus(PluginStatus.UNINSTALLING);
                // uninstall plugin
                loader.uninstall();

                // uninstall succeed, remove the plugin
                plugins[i].remove(name);
                loader.setStatus(PluginStatus.UNINSTALLED);
                removeDynamicPluginName(name);

                // do not get plugin info by calling loader.getPluginInfo(). That method will try to
                // reload the plugin properties from source if this plugin is not installed successfully.
                // Here we only need the plugin's name for persisting.
                // TODO(cmy): This is a bad design to couple the persist info with PluginInfo, but for
                // the compatibility, I till use this method.
                return new PluginInfo(name);
            }
        }

        throw new DdlException("Plugin " + name + " does not exist");
    }

    /**
     * For built-in Plugin register
     */
    public boolean registerBuiltinPlugin(PluginInfo pluginInfo, Plugin plugin) {
        if (Objects.isNull(pluginInfo) || Objects.isNull(plugin) || Objects.isNull(pluginInfo.getType())
                || Strings.isNullOrEmpty(pluginInfo.getName())) {
            return false;
        }

        PluginLoader loader = new BuiltinPluginLoader(Config.plugin_dir, pluginInfo, plugin);
        PluginLoader checkLoader = plugins[pluginInfo.getTypeId()].putIfAbsent(pluginInfo.getName(), loader);

        return checkLoader == null;
    }

    /*
     * replay load plugin.
     * It must add the plugin to the "plugins" and "dynamicPluginNames", even if the plugin
     * is not loaded successfully.
     */
    public void replayLoadDynamicPlugin(PluginInfo info) throws IOException, UserException {
        DynamicPluginLoader pluginLoader = new DynamicPluginLoader(Config.plugin_dir, info);
        try {
            // should add to "plugins" first before loading.
            PluginLoader checkLoader = plugins[info.getTypeId()].putIfAbsent(info.getName(), pluginLoader);
            if (checkLoader != null) {
                throw new UserException("plugin " + info.getName() + " has already been installed.");
            }

            pluginLoader.setStatus(PluginStatus.INSTALLING);
            // install plugin
            pluginLoader.reload();
            pluginLoader.setStatus(PluginStatus.INSTALLED);
        } catch (IOException | UserException e) {
            pluginLoader.setStatus(PluginStatus.ERROR, e.getMessage());
            throw e;
        } finally {
            // this is a replay process, so whether it is successful or not, add it's name.
            addDynamicPluginNameIfAbsent(info.getName());
        }
    }

    public final Plugin getActivePlugin(String name, PluginType type) {
        PluginLoader loader = plugins[type.ordinal()].get(name);

        if (null != loader && (loader.getStatus() == PluginStatus.INSTALLED
                || loader.getStatus() == PluginStatus.ENABLED)) {
            return loader.getPlugin();
        }

        return null;
    }

    public final List<Plugin> getActivePluginList(PluginType type) {
        Map<String, PluginLoader> m = plugins[type.ordinal()];
        List<Plugin> l = Lists.newArrayListWithCapacity(m.size());

        m.values().forEach(d -> {
            if (d.getStatus() == PluginStatus.INSTALLED || d.getStatus() == PluginStatus.ENABLED) {
                l.add(d.getPlugin());
            }
        });

        return Collections.unmodifiableList(l);
    }

    public final List<PluginInfo> getAllPluginInfoExceptLogBuilder() {
        List<PluginInfo> list = Lists.newArrayList();
        for (Map<String, PluginLoader> map : plugins) {
            map.values().forEach(loader -> {
                try {
                    PluginInfo info = loader.getPluginInfo();
                    if (!"__builtin_AuditLogBuilder".equalsIgnoreCase(info.getName())) {
                        list.add(info);
                    }
                } catch (Exception e) {
                    LOG.warn("load plugin from {} failed", loader.source, e);
                }
            });
        }

        return list;
    }

    public List<List<String>> getPluginShowInfos() {
        List<List<String>> rows = Lists.newArrayList();
        for (Map<String, PluginLoader> map : plugins) {
            for (Map.Entry<String, PluginLoader> entry : map.entrySet()) {
                List<String> row = Lists.newArrayList();
                PluginLoader loader = entry.getValue();

                PluginInfo pi = null;
                try {
                    pi = loader.getPluginInfo();
                } catch (Exception e) {
                    // plugin may not be loaded successfully
                    LOG.warn("failed to get plugin info for plugin: {}", entry.getKey(), e);
                }

                row.add(entry.getKey());
                row.add(pi != null ? pi.getType().name() : "UNKNOWN");
                row.add(pi != null ? pi.getDescription() : "UNKNOWN");
                row.add(pi != null ? pi.getVersion().toString() : "UNKNOWN");
                row.add(pi != null ? pi.getJavaVersion().toString() : "UNKNOWN");
                row.add(pi != null ? pi.getClassName() : "UNKNOWN");
                row.add(pi != null ? pi.getSoName() : "UNKNOWN");
                if (Strings.isNullOrEmpty(loader.source)) {
                    row.add("Builtin");
                } else {
                    row.add(loader.source);
                }

                row.add(loader.getStatus().toString());
                row.add(pi != null ? "{" + new PrintableMap<>(pi.getProperties(),
                        "=", true, false, true) + "}" : "UNKNOWN");

                if ("__builtin_AuditLoader".equalsIgnoreCase(pi.getName())) {
                    if (enableGeneralLog) {
                        String generalTbl = Config.database + "." + Config.general_log_table;
                        row.set(0, "__builtin_GeneralLogLoader");
                        row.set(2, "load audit logs to " + generalTbl);
                        rows.add(row);
                    }
                    if (enableSlowLog) {
                        List<String> rowTemp = Lists.newArrayList();
                        rowTemp.addAll(row);
                        String slowTbl = Config.database + "." + Config.slow_log_table;
                        rowTemp.set(0, "__builtin_SlowLogLoader");
                        rowTemp.set(2, "load audit logs to " + slowTbl);
                        rows.add(rowTemp);
                    }
                    continue;
                }
                rows.add(row);
            }
        }
        return rows;
    }

    public void readFields(DataInputStream dis) throws IOException {
        int size = dis.readInt();
        for (int i = 0; i < size; i++) {
            try {
                PluginInfo pluginInfo = PluginInfo.read(dis);
                if ("__builtin_AuditLoader".equalsIgnoreCase(pluginInfo.getName())) {
                    List<String> logTypeList = pluginInfo.getlogTypeList();
                    for (String logType : logTypeList) {
                        enablePlugin(logType);
                    }
                } else {
                    replayLoadDynamicPlugin(pluginInfo);
                }
            } catch (Exception e) {
                LOG.warn("load plugin failed.", e);
            }
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        // only need to persist dynamic plugins
        List<PluginInfo> list = getAllPluginInfoExceptLogBuilder();
        int size = list.size();
        out.writeInt(size);
        for (PluginInfo pc : list) {
            pc.write(out);
        }
    }
}
