---
{
    "title": "Audit log plugin",
    "language": "en"
}
---

<!-
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
->

# Audit log plugin

Doris's audit log plugin was developed based on FE's plugin framework. Is an optional plugin. Users can enable or disable this plugin at mysql client.

This plugin can periodically import the FE audit log into the specified Doris cluster, so that users can easily view and analyze the audit log through SQL.

## Configure, Enable and Disable

### AuditLoader Configuration

The configuration of the auditloader plugin is in FE configuration. See the comments of the configuration items.

### Enable

```
alter system enable general log;
alter system enable slow log;
```

### Disable

```
alter system disable general log;
alter system disable slow log;
```

>**Notice**
>
After successful enable plugin, you can see the installed plug-ins through `SHOW PLUGINS`, and the status is `INSTALLED`, and the `DESCRIBTION` field shows which table you are currently importing data into.

After the general log function is enabled, the plug-in continuously inserts audit logs into the general table at specified intervals.

After slow log is enabled, the plug-in continuously inserts audit logs into the slow table at specified intervals.
