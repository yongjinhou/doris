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

package org.apache.doris.analysis;

import org.apache.doris.alter.AlterOpType;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.UserException;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;

public class DisablePluginClause extends AlterClause {

    private String logType;

    public DisablePluginClause(String logType) {
        super(AlterOpType.ALTER_OTHER);
        this.logType = logType;
    }

    public String getLogType() {
        return logType;
    }

    @Override
    public void analyze(Analyzer analyzer) throws AnalysisException, UserException {
        if (!Config.plugin_enable_audit_log_loader) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_OPERATION_DISABLED, "DISABLE PLUGIN",
                    "Please enable it by setting 'plugin_enable_audit_log_loader' = 'true'");
        }

        // check operation privilege
        if (!Env.getCurrentEnv().getAuth().checkGlobalPriv(ConnectContext.get(), PrivPredicate.ADMIN)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR, "ADMIN");
        }
    }

    @Override
    public String toSql() {
        return "DISABLE " + logType + " LOG";
    }

    @Override
    public String toString() {
        return toSql();
    }
}
