---
{
    "title": "审计日志插件",
    "language": "zh-CN"
}
---

<!-- 
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# 审计日志插件

Doris 的审计日志插件是在 FE 的插件框架基础上开发的。是一个可选插件。用户可以在 mysql 客户端开启或关闭这个插件。

该插件可以将 FE 的审计日志定期的导入到指定 Doris 集群中，以方便用户通过 SQL 对审计日志进行查看和分析。

## 配置、开启和关闭

### AuditLoader 配置

auditloader plugin 的配置位于 FE 配置中，配置项说明参见注释。

### 开启

```
alter system enable general log;
alter system enable slow log;
```

### 关闭

```
alter system disable general log;
alter system disable slow log;
```

>**注意**
>
插件开启后，可以通过 `SHOW PLUGINS` 看到已经安装的插件，并且状态为 `INSTALLED`，并且在 `DESCRIBTION` 字段中显示当前正在向哪个表中导入数据。

开启 general log 后插件会不断地以指定的时间间隔将审计日志插入到 general 表中。

开启 slow log 后插件会不断地以指定的时间间隔将审计日志插入到 slow 表中。
