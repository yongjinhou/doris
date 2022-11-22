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

import org.apache.doris.common.UserException;

import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.List;

public class BuiltinPluginLoader extends PluginLoader {

    BuiltinPluginLoader(String path, PluginInfo info, Plugin plugin) {
        super(path, info);
        this.plugin = plugin;
        this.status = PluginStatus.INSTALLED;
    }

    @Override
    public void install() throws UserException, IOException {
        pluginInstallValid();
        plugin.init(pluginInfo, pluginContext);
    }

    @Override
    public void uninstall() throws IOException, UserException {
        if (plugin != null) {
            pluginUninstallValid();
            plugin.close();
        }
    }

    @Override
    public void setPluginInfo(boolean enableGeneralLog, boolean enableSlowLog) {
        List<String> logTypeList = Lists.newArrayList();
        if (enableGeneralLog && enableSlowLog) {
            logTypeList.add("general");
            logTypeList.add("slow");
        } else if (enableGeneralLog) {
            logTypeList.add("general");
        } else {
            logTypeList.add("slow");
        }
        pluginInfo.setLogTypeList(logTypeList);
    }
}
