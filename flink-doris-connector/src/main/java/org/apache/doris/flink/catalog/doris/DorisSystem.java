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
package org.apache.doris.flink.catalog.doris;


import org.apache.commons.compress.utils.Lists;
import org.apache.doris.flink.cfg.DorisConnectionOptions;
import org.apache.doris.flink.connection.JdbcConnectionProvider;
import org.apache.doris.flink.connection.SimpleJdbcConnectionProvider;
import org.apache.doris.flink.exception.CreateTableException;
import org.apache.doris.flink.exception.DorisRuntimeException;
import org.apache.doris.flink.exception.DorisSystemException;
import org.apache.doris.flink.tools.cdc.DatabaseSync;
import org.apache.flink.annotation.Public;
import org.apache.flink.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * Doris System Operate
 */
@Public
public class DorisSystem {

    private boolean useLowerCase;
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseSync.class);
    private JdbcConnectionProvider jdbcConnectionProvider;
    private static final List<String> builtinDatabases = Arrays.asList("information_schema");

    public DorisSystem(DorisConnectionOptions options) {
        this.jdbcConnectionProvider = new SimpleJdbcConnectionProvider(options);
    }

    public List<String> listDatabases() {
        return extractColumnValuesBySQL(
                "SELECT `SCHEMA_NAME` FROM `INFORMATION_SCHEMA`.`SCHEMATA`;",
                1,
                dbName -> !builtinDatabases.contains(dbName));
    }

    public boolean databaseExists(String database) {
        checkArgument(!StringUtils.isNullOrWhitespaceOnly(database));
        return listDatabases().contains(database);
    }

    public boolean createDatabase(String database) {
        execute(String.format("CREATE DATABASE IF NOT EXISTS %s", database));
        return true;
    }

    public boolean dropDatabase(String database) {
        execute(String.format("DROP DATABASE IF EXISTS %s", database));
        return true;
    }

    public boolean tableExists(String database, String table) {
        return databaseExists(database)
                && listTables(database).contains(table);
    }

    public List<String> listTables(String databaseName) {
        if (!databaseExists(databaseName)) {
            throw new DorisRuntimeException("database" + databaseName + " is not exists");
        }
        return extractColumnValuesBySQL(
                "SELECT TABLE_NAME FROM information_schema.`TABLES` WHERE TABLE_SCHEMA = ?",
                1,
                null,
                databaseName);
    }

    public void dropTable(String tableName) {
        execute(String.format("DROP TABLE IF EXISTS %s", tableName));
    }

    public void createTable(TableSchema schema, boolean useLowerCase) {
        this.useLowerCase = useLowerCase;
        String ddl = buildCreateTableDDL(schema, useLowerCase);
        LOG.info("Create table with ddl:{}", ddl);
        execute(ddl);
    }

    public void execute(String sql) {
        try (Statement statement = jdbcConnectionProvider.getOrEstablishConnection().createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new DorisSystemException(String.format("SQL query could not be executed: %s", sql), e);
        }
    }

    public List<String> extractColumnValuesBySQL(
            String sql,
            int columnIndex,
            Predicate<String> filterFunc,
            Object... params) {

        List<String> columnValues = Lists.newArrayList();
        try (PreparedStatement ps = jdbcConnectionProvider.getOrEstablishConnection().prepareStatement(sql)) {
            if (Objects.nonNull(params) && params.length > 0) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String columnValue = rs.getString(columnIndex);
                if (Objects.isNull(filterFunc) || filterFunc.test(columnValue)) {
                    columnValues.add(columnValue);
                }
            }
            return columnValues;
        } catch (Exception e) {
            throw new DorisSystemException(
                    String.format(
                            "The following SQL query could not be executed: %s", sql),
                    e);
        }
    }

    public String buildCreateTableDDL(TableSchema schema, boolean useLowerCase) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(identifier(schema.getDatabase()))
                .append(".")
                .append(identifier(schema.getTable()))
                .append("(");

        // 待验证, 生成Doris DDL语句时,排除不支持的字段名, 该规则尚未匹配 源数据对应字段名剔除
        // Map<String, FieldSchema> fields = new Map<String, FieldSchema>;
        // schema.getFields().forEach((key, val) -> {
        //     if (key.matches("^[a-zA-Z][a-zA-Z0-9-_]*$")) {
        //         fields.put(key, val);
        //     }
        // });
        Map<String, FieldSchema> fields = schema.getFields();
        List<String> keys = schema.getKeys();
        //append keys
        for (String key : keys) {
            if (!fields.containsKey(key)) {
                throw new CreateTableException("key " + key + " not found in column list");
            }
            FieldSchema field = fields.get(key);
            buildColumn(sb, field, true);
        }

        //append values
        for (Map.Entry<String, FieldSchema> entry : fields.entrySet()) {
            if (keys.contains(entry.getKey())) {
                continue;
            }
            FieldSchema field = entry.getValue();
            buildColumn(sb, field, false);

        }
        sb = sb.deleteCharAt(sb.length() - 1);
        sb.append(" ) ");
        //append uniq model
        if (DataModel.UNIQUE.equals(schema.getModel())) {
            sb.append(schema.getModel().name())
                    .append(" KEY(")
                    .append(String.join(",", identifier(schema.getKeys())))
                    .append(")");
        }

        //append table comment
        if (!StringUtils.isNullOrWhitespaceOnly(schema.getTableComment())) {
            sb.append(" COMMENT '")
                    .append(quoteComment(schema.getTableComment()))
                    .append("' ");
        }

        //append distribute key
        sb.append(" DISTRIBUTED BY HASH(")
                .append(String.join(",", identifier(schema.getDistributeKeys())))
                .append(") BUCKETS AUTO ");

        //append properties
        int index = 0;
        for (Map.Entry<String, String> entry : schema.getProperties().entrySet()) {
            if (index == 0) {
                sb.append(" PROPERTIES (");
            }
            if (index > 0) {
                sb.append(",");
            }
            sb.append(quoteProperties(entry.getKey()))
                    .append("=")
                    .append(quoteProperties(entry.getValue()));
            index++;

            if (index == schema.getProperties().size()) {
                sb.append(")");
            }
        }
        return sb.toString();
    }

    private void buildColumn(StringBuilder sql, FieldSchema field, boolean isKey) {
        String fieldType = field.getTypeString();
        if (isKey && DorisType.STRING.equals(fieldType)) {
            fieldType = String.format("%s(%s)", DorisType.VARCHAR, 65533);
        }
        sql.append(identifier(field.getName()))
                .append(" ")
                .append(fieldType)
                .append(" COMMENT '")
                .append(quoteComment(field.getComment()))
                .append("',");
    }

    private String quoteComment(String comment) {
        if (comment == null) {
            return "";
        } else {
            return comment.replaceAll("'", "\\\\'");
        }
    }

    private List<String> identifier(List<String> name) {
        return name.stream().map(m -> identifier(m)).collect(Collectors.toList());
    }

    private String identifier(String name) {
        return "`" + (this.useLowerCase ? name.toLowerCase() : name) + "`";
    }

    private String quoteProperties(String name) {
        return "'" + name + "'";
    }

}
