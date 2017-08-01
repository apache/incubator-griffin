/*
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
*/
package org.apache.griffin.core.metastore.hive;


import org.apache.griffin.core.error.Exception.HiveConnectionException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/metadata/hive")
public class HiveMetastoreController {

    @Autowired
    HiveMetastoreServiceImpl hiveMetastoreService;

    @RequestMapping("/db")
    public Iterable<String> getAllDatabases() throws HiveConnectionException {
        return hiveMetastoreService.getAllDatabases();
    }

    @RequestMapping("/table")
    public Iterable<String> getDefAllTables() throws HiveConnectionException {
        return hiveMetastoreService.getAllTableNames("");
    }

    @RequestMapping("/allTableNames")
    public Iterable<String> getAllTableNames(@RequestParam("db") String dbName) throws HiveConnectionException {
        return hiveMetastoreService.getAllTableNames(dbName);
    }

    @RequestMapping("/db/allTables")
    public List<Table> getAllTables(@RequestParam("db") String dbName) throws HiveConnectionException {
        return hiveMetastoreService.getAllTable(dbName);
    }

    @RequestMapping("/allTables")
    public Map<String,List<Table>> getAllTables() throws HiveConnectionException {
        return hiveMetastoreService.getAllTable();
    }

    @RequestMapping("/default/{table}")
    public Table getDefTable(@PathVariable("table") String tableName) throws HiveConnectionException {
        return hiveMetastoreService.getTable("", tableName);
    }

    @RequestMapping("")
    public Table getTable(@RequestParam("db") String dbName, @RequestParam("table") String tableName) throws HiveConnectionException {
        return hiveMetastoreService.getTable(dbName, tableName);
    }
}
