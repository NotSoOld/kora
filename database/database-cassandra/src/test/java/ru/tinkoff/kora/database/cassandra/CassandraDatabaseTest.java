package ru.tinkoff.kora.database.cassandra;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.tinkoff.kora.database.common.QueryContext;
import ru.tinkoff.kora.database.common.telemetry.DefaultDataBaseTelemetryFactory;
import ru.tinkoff.kora.test.cassandra.CassandraParams;
import ru.tinkoff.kora.test.cassandra.CassandraTestContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

@ExtendWith(CassandraTestContainer.class)
class CassandraDatabaseTest {
    private static CassandraDatabase createCassandraDatabase(CassandraParams params) {
        var profiles = new HashMap<String, CassandraConfig.Profile>();
        profiles.put(
            "profile",
            new CassandraConfig.Profile(new CassandraConfig.Basic(
                new CassandraConfig.Basic.BasicRequestConfig(Duration.ofSeconds(10), null, null, null, null),
                null,
                null,
                null,
                null,
                null,
                null
            ), null)
        );
        var config = new CassandraConfig(
            profiles,
            new CassandraConfig.Basic(
                null,
                null,
                List.of(params.host() + ":" + params.port()),
                params.dc(),
                params.keyspace(),
                null,
                null
            ),
            null,
            params.username() == null ? null : new CassandraConfig.CassandraCredentials(
                params.username(),
                params.password()
            )
        );
        return new CassandraDatabase(config, new DefaultDataBaseTelemetryFactory(null, null, null));
    }

    private static void withDb(CassandraParams params, Consumer<CassandraDatabase> consumer) {
        var db = createCassandraDatabase(params);
        try {
            db.init().block();
            consumer.accept(db);
        } finally {
            db.release().block();
        }
    }


    @Test
    public void testQuery(CassandraParams params) {
        params.execute("create table test_table(id int, value varchar, primary key (id));\n");
        params.execute("insert into test_table(id, value) values (1,'test1');\n");

        record Entity(Integer id, String value) {}
        var qctx = new QueryContext(
            "SELECT id, value FROM test_table WHERE value = :value allow filtering",
            "SELECT id, value FROM test_table WHERE value = ? allow filtering"
        );

        withDb(params, db -> {
            var result = db.query(qctx, stmt -> {
                var s = stmt.bind( "test1");
                return db.currentSession().execute(s).map(row -> {
                    var __id = row.isNull("id") ? null : row.getInt("id");
                    var __value = row.getString("value");
                    return new Entity(__id, __value);
                });
            });
            Assertions.assertThat(result)
                .hasSize(1)
                .first()
                .isEqualTo(new Entity(1, "test1"));
        });
    }

    @Test
    public void testAsyncQuery(CassandraParams params) {
        params.execute("create table test_table(id int, value varchar, primary key (id));\n");
        params.execute("insert into test_table(id, value) values (1,'test1');\n");

        record Entity(Integer id, String value) {}
        var qctx = new QueryContext(
            "SELECT id, value FROM test_table WHERE value = :value allow filtering",
            "SELECT id, value FROM test_table WHERE value = ? allow filtering"
        );

        withDb(params, db -> {
            var result = db.query(qctx, stmt -> {
                var s = stmt.bind("test1");
                return db.currentSession().execute(s).map(row -> {
                    var __id = row.isNull("id") ? null : row.getInt("id");
                    var __value = row.getString("value");
                    return new Entity(__id, __value);
                });
            });

            Assertions.assertThat(result)
                .hasSize(1)
                .first()
                .isEqualTo(new Entity(1, "test1"));

        });
    }
}
