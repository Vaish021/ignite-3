/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine;

import static org.apache.ignite.internal.sql.engine.util.SqlTestUtils.assertThrowsSqlException;
import static org.apache.ignite.internal.table.TableTestUtils.getTableStrict;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.table.TableImpl;
import org.apache.ignite.lang.ErrorGroups.Sql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for CREATE TABLE DDL command.
 */
public class ItCreateTableDdlTest extends ClusterPerClassIntegrationTest {
    @AfterEach
    public void dropTables() {
        dropAllTables();
    }

    @Test
    public void pkWithNullableColumns() {
        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Primary key cannot contain nullable column [col=ID0]",
                () -> sql("CREATE TABLE T0(ID0 INT NULL, ID1 INT NOT NULL, VAL INT, PRIMARY KEY (ID1, ID0))")
        );

        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Primary key cannot contain nullable column [col=ID]",
                () -> sql("CREATE TABLE T0(ID INT NULL PRIMARY KEY, VAL INT)")
        );
    }

    @Test
    public void pkWithInvalidColumns() {
        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Primary key constraint contains undefined columns: [cols=[ID2]]",
                () -> sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY (ID2, ID0))")
        );
    }

    @Test
    public void emptyPk() {
        assertThrowsSqlException(
                Sql.STMT_PARSE_ERR,
                "Failed to parse query: Encountered \")\"",
                () -> sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY ())")
        );

        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Table without PRIMARY KEY is not supported",
                () -> sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT)")
        );
    }

    @Test
    public void tableWithInvalidColumns() {
        assertThrowsSqlException(
                Sql.STMT_PARSE_ERR,
                "Failed to parse query: Encountered \")\"",
                () -> sql("CREATE TABLE T0()")
        );

        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Column with name 'ID0' specified more than once",
                () -> sql("CREATE TABLE T0(ID0 INT PRIMARY KEY, ID1 INT, ID0 INT)")
        );
    }

    @Test
    public void pkWithFunctionalDefault() {
        sql("create table t (id varchar default gen_random_uuid primary key, val int)");
        sql("insert into t (val) values (1), (2)");

        var result = sql("select * from t");

        assertThat(result, hasSize(2)); // both rows are inserted without conflict
    }

    @Test
    public void undefinedColumnsInPrimaryKey() {
        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Primary key constraint contains undefined columns: [cols=[ID0, ID2, ID1]]",
                () -> sql("CREATE TABLE T0(ID INT, VAL INT, PRIMARY KEY (ID1, ID0, ID2))")
        );
    }

    /**
     * Check invalid colocation columns configuration: - not PK columns; - duplicates colocation columns.
     */
    @Test
    public void invalidColocationColumns() {
        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Failed to validate query. Colocation column 'VAL' is not part of PK",
                () -> sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY (ID1, ID0)) COLOCATE (ID0, VAL)")
        );

        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Failed to validate query. Colocation column 'ID1' specified more that once",
                () -> sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY (ID1, ID0)) COLOCATE (ID1, ID0, ID1)")
        );
    }

    /**
     * Check implicit colocation columns configuration (defined by PK)..
     */
    @Test
    public void implicitColocationColumns() {
        sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY (ID1, ID0))");

        Column[] colocationColumns = ((TableImpl) table("T0")).schemaView().lastKnownSchema().colocationColumns();

        assertEquals(2, colocationColumns.length);
        assertEquals("ID1", colocationColumns[0].name());
        assertEquals("ID0", colocationColumns[1].name());
    }

    /** Test correct mapping schema after alter columns. */
    @Test
    public void testDropAndAddColumns() {
        sql("CREATE TABLE my (c1 INT PRIMARY KEY, c2 INT, c3 VARCHAR)");

        sql("INSERT INTO my VALUES (1, 2, '3')");

        List<List<Object>> res = sql("SELECT c1, c3 FROM my");

        assertFalse(res.isEmpty());

        sql("ALTER TABLE my DROP COLUMN c2");

        res = sql("SELECT c1, c3 FROM my");

        assertFalse(res.isEmpty());

        sql("ALTER TABLE my ADD COLUMN (c2 INT, c4 VARCHAR)");

        sql("INSERT INTO my VALUES (2, '2', 2, '3')");

        res = sql("SELECT c2, c4 FROM my WHERE c1=2");

        assertEquals(2, res.get(0).get(0));

        sql("ALTER TABLE my DROP COLUMN c4");
        sql("ALTER TABLE my ADD COLUMN (c4 INT)");
        sql("INSERT INTO my VALUES (3, '2', 3, 3)");

        res = sql("SELECT c4 FROM my WHERE c1=3");

        assertEquals(3, res.get(0).get(0));
    }

    /**
     * Checks that schema version is updated even if column names are intersected.
     */
    // Need to be removed after https://issues.apache.org/jira/browse/IGNITE-19082
    @Test
    public void checkSchemaUpdatedWithEqAlterColumn() {
        sql("CREATE TABLE TEST(ID INT PRIMARY KEY, VAL0 INT)");

        IgniteImpl node = (IgniteImpl) CLUSTER_NODES.get(0);

        int tableVersionBefore = getTableStrict(node.catalogManager(), "TEST", node.clock().nowLong()).tableVersion();

        sql("ALTER TABLE TEST ADD COLUMN (VAL1 INT)");

        int tableVersionAfter = getTableStrict(node.catalogManager(), "TEST", node.clock().nowLong()).tableVersion();

        assertEquals(tableVersionBefore + 1, tableVersionAfter);
    }

    /**
     * Check explicit colocation columns configuration.
     */
    @Test
    public void explicitColocationColumns() {
        sql("CREATE TABLE T0(ID0 INT, ID1 INT, VAL INT, PRIMARY KEY (ID1, ID0)) COLOCATE BY (id0)");

        Column[] colocationColumns = ((TableImpl) table("T0")).schemaView().lastKnownSchema().colocationColumns();

        assertEquals(1, colocationColumns.length);
        assertEquals("ID0", colocationColumns[0].name());
    }

    /**
     * Check explicit colocation columns configuration.
     */
    @Test
    public void explicitColocationColumnsCaseSensitive() {
        sql("CREATE TABLE T0(\"Id0\" INT, ID1 INT, VAL INT, PRIMARY KEY (ID1, \"Id0\")) COLOCATE BY (\"Id0\")");

        Column[] colocationColumns = ((TableImpl) table("T0")).schemaView().lastKnownSchema().colocationColumns();

        assertEquals(1, colocationColumns.length);
        assertEquals("Id0", colocationColumns[0].name());
    }

    @Test
    public void doNotAllowFunctionsInNonPkColumns() {
        assertThrowsSqlException(
                Sql.STMT_VALIDATION_ERR,
                "Functional defaults are not supported for non-primary key columns",
                () -> sql("create table t (id varchar primary key, val varchar default gen_random_uuid)")
        );
    }

    @Test
    public void dummyAlterColumnDataType() {
        sql("CREATE TABLE t0 (ID INT PRIMARY KEY, C2 varbinary, C3 varchar, C4 varbinary(10), C5 varchar(11))");
        sql("ALTER TABLE t0 ALTER COLUMN C2 SET DATA TYPE varbinary");
        sql("ALTER TABLE t0 ALTER COLUMN C4 SET DATA TYPE varbinary(10)");
        sql("ALTER TABLE t0 ALTER COLUMN C5 SET DATA TYPE varchar(11)");

        sql("CREATE TABLE t1 (ID INT PRIMARY KEY, DECIMAL_C2 DECIMAL(2))");
        sql("ALTER TABLE t1 ALTER COLUMN DECIMAL_C2 SET DATA TYPE DECIMAL");
        assertThrowsSqlException(Sql.STMT_VALIDATION_ERR, "Decreasing the precision is not allowed",
                () -> sql("ALTER TABLE t1 ALTER COLUMN DECIMAL_C2 SET DATA TYPE DECIMAL(1)"));
    }
}
