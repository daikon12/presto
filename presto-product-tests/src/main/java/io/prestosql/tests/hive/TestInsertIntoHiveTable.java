/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.tests.hive;

import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.fulfillment.table.TableDefinition;
import io.prestosql.tempto.fulfillment.table.hive.HiveTableDefinition;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static io.prestosql.tempto.Requirements.compose;
import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.fulfillment.table.MutableTableRequirement.State.CREATED;
import static io.prestosql.tempto.fulfillment.table.MutableTablesState.mutableTablesState;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.mutableTable;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.hive.AllSimpleTypesTableDefinitions.ALL_HIVE_SIMPLE_TYPES_TEXTFILE;
import static io.prestosql.tests.utils.QueryExecutors.onHive;

public class TestInsertIntoHiveTable
        extends ProductTest
        implements RequirementsProvider
{
    private static final String TABLE_NAME = "target_table";
    private static final String PARTITIONED_TABLE_WITH_SERDE = "target_partitioned_with_serde_property";

    @Override
    public Requirement getRequirements(Configuration configuration)
    {
        return compose(
                mutableTable(ALL_HIVE_SIMPLE_TYPES_TEXTFILE, TABLE_NAME, CREATED),
                mutableTable(partitionedTableDefinition(), PARTITIONED_TABLE_WITH_SERDE, CREATED),
                immutableTable(ALL_HIVE_SIMPLE_TYPES_TEXTFILE));
    }

    private static TableDefinition partitionedTableDefinition()
    {
        String createTableDdl = "CREATE TABLE %NAME%( " +
                "id int, " +
                "name string " +
                ") " +
                "PARTITIONED BY (dt string) " +
                "ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe' " +
                "WITH SERDEPROPERTIES ( " +
                "'field.delim'='\\t', " +
                "'line.delim'='\\n', " +
                "'serialization.format'='\\t' " +
                ")";

        return HiveTableDefinition.builder(PARTITIONED_TABLE_WITH_SERDE)
                .setCreateTableDDLTemplate(createTableDdl)
                .setNoData()
                .build();
    }

    @Test
    public void testInsertIntoValuesToHiveTableAllHiveSimpleTypes()
    {
        String tableNameInDatabase = mutableTablesState().get(TABLE_NAME).getNameInDatabase();
        assertThat(query("SELECT * FROM " + tableNameInDatabase)).hasNoRows();
        query("INSERT INTO " + tableNameInDatabase + " VALUES(" +
                "TINYINT '127', " +
                "SMALLINT '32767', " +
                "2147483647, " +
                "9223372036854775807, " +
                "REAL '123.345', " +
                "234.567, " +
                "CAST(346 as DECIMAL(10,0))," +
                "CAST(345.67800 as DECIMAL(10,5))," +
                "timestamp '2015-05-10 12:15:35.123', " +
                "date '2015-05-10', " +
                "'ala ma kota', " +
                "'ala ma kot', " +
                "CAST('ala ma    ' as CHAR(10)), " +
                "true, " +
                "from_base64('a290IGJpbmFybnk=')" +
                ")");

        assertThat(query("SELECT * FROM " + tableNameInDatabase)).containsOnly(
                row(
                        127,
                        32767,
                        2147483647,
                        9223372036854775807L,
                        123.345f,
                        234.567,
                        new BigDecimal("346"),
                        new BigDecimal("345.67800"),
                        Timestamp.valueOf(LocalDateTime.of(2015, 5, 10, 12, 15, 35, 123_000_000)),
                        Date.valueOf("2015-05-10"),
                        "ala ma kota",
                        "ala ma kot",
                        "ala ma    ",
                        true,
                        "kot binarny".getBytes()));
    }

    @Test
    public void testInsertIntoSelectToHiveTableAllHiveSimpleTypes()
    {
        String tableNameInDatabase = mutableTablesState().get(TABLE_NAME).getNameInDatabase();
        assertThat(query("SELECT * FROM " + tableNameInDatabase)).hasNoRows();
        assertThat(query("INSERT INTO " + tableNameInDatabase + " SELECT * from textfile_all_types")).containsExactly(row(1));
        assertThat(query("SELECT * FROM " + tableNameInDatabase)).containsOnly(
                row(
                        127,
                        32767,
                        2147483647,
                        9223372036854775807L,
                        123.345f,
                        234.567,
                        new BigDecimal("346"),
                        new BigDecimal("345.67800"),
                        Timestamp.valueOf(LocalDateTime.of(2015, 5, 10, 12, 15, 35, 123_000_000)),
                        Date.valueOf("2015-05-10"),
                        "ala ma kota",
                        "ala ma kot",
                        "ala ma    ",
                        true,
                        "kot binarny".getBytes()));
    }

    @Test
    public void testInsertIntoPartitionedWithSerdeProperty()
    {
        String tableNameInDatabase = mutableTablesState().get(PARTITIONED_TABLE_WITH_SERDE).getNameInDatabase();
        assertThat(query("INSERT INTO " + tableNameInDatabase + " SELECT 1, 'presto', '2018-01-01'")).containsExactly(row(1));
        assertThat(query("SELECT * FROM " + tableNameInDatabase)).containsExactly(row(1, "presto", "2018-01-01"));
    }

    @Test
    public void testInsertIntoTransactionalTable()
    {
        onHive().executeQuery("" +
                "CREATE TABLE test_insert_into_transactional_table(a bigint)" +
                "CLUSTERED BY(a) INTO 4 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional'='true', 'bucketing_version'='1')");

        assertThat(() -> query("INSERT INTO test_insert_into_transactional_table (a) VALUES (42)"))
                .failsWithMessage("Inserting into Hive transactional tables is not supported: default.test_insert_into_transactional_table");

        onHive().executeQuery("DROP TABLE test_insert_into_transactional_table");
    }
}
