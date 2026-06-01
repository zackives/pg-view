package edu.upenn.cis.db.graphtrans.store.duckdb;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.*;

import edu.upenn.cis.db.ConjunctiveQuery.Atom;
import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.ConjunctiveQuery.Term;
import edu.upenn.cis.db.ConjunctiveQuery.Type;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.datalog.DatalogParser;
import edu.upenn.cis.db.datalog.DatalogProgram;
import edu.upenn.cis.db.duckdb.DuckDB;
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.store.Store;
import edu.upenn.cis.db.graphtrans.store.StoreFactory;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;

/**
 * Tests for DuckDBStore and the DuckDB helper.
 *
 * All tests use in-memory or temp-directory DuckDB instances — no external
 * services required.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DuckDBStoreTest {

    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Config.initialize();
        tempDir = Files.createTempDirectory("pgview-duck-test");
    }

    @AfterAll
    static void teardown() throws Exception {
        // clean up .duckdb files created during tests
        if (tempDir != null) {
            for (File f : tempDir.toFile().listFiles()) f.delete();
            tempDir.toFile().delete();
        }
    }

    // -----------------------------------------------------------------------
    // StoreFactory
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void storeFactoryReturnsDuckDBStore() {
        Store store = new StoreFactory().getStore("duck");
        assertInstanceOf(DuckDBStore.class, store);
    }

    // -----------------------------------------------------------------------
    // DuckDB helper — low-level JDBC
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    void duckDbInMemoryConnectAndQuery() {
        DuckDB db = new DuckDB();
        assertTrue(db.connect(":memory:", "mem"));
        db.executeUpdate("CREATE TABLE t (id INTEGER, name VARCHAR)");
        db.executeUpdate("INSERT INTO t VALUES (1, 'alice'), (2, 'bob')");
        StoreResultSet rs = db.select("SELECT id, name FROM t ORDER BY id");
        assertEquals(2, rs.getResultSet().size());
        assertEquals(1L, ((LongSimpleTerm) rs.getResultSet().get(0).getTuple().get(0)).getLong());
        assertEquals("alice", rs.getResultSet().get(0).getTuple().get(1).getString());
        db.disconnect();
    }

    @Test
    @Order(3)
    void duckDbFileBasedConnect() {
        String path = tempDir + File.separator + "filetest.duckdb";
        DuckDB db = new DuckDB();
        assertTrue(db.connect(path, "filetest"));
        db.executeUpdate("CREATE TABLE nums (n INTEGER)");
        db.executeUpdate("INSERT INTO nums VALUES (42)");
        StoreResultSet rs = db.select("SELECT n FROM nums");
        assertEquals(1, rs.getResultSet().size());
        assertEquals(42L, ((LongSimpleTerm) rs.getResultSet().get(0).getTuple().get(0)).getLong());
        db.disconnect();

        // Reconnect and verify persistence
        DuckDB db2 = new DuckDB();
        db2.connect(path, "filetest");
        StoreResultSet rs2 = db2.select("SELECT n FROM nums");
        assertEquals(1, rs2.getResultSet().size());
        db2.disconnect();
    }

    // -----------------------------------------------------------------------
    // DuckDBStore — database lifecycle
    // -----------------------------------------------------------------------

    private DuckDBStore newStore() {
        DuckDBStore store = new DuckDBStore();
        // Inject temp dir via Config so no real filesystem side effects outside tempDir
        System.setProperty("duckdb.test.dbdir", tempDir.toString());
        // Manually set dbDir by reflection or use a test-only config key.
        // Since connect() reads Config.get("duckdb.dbdir"), set it in config map.
        // Workaround: directly pass through a subclass would require refactor;
        // instead, rely on the fact that each test creates its own store with a
        // unique dbdir by temporarily placing a config value.
        return store;
    }

    @Test
    @Order(10)
    void createAndUseDatabaseLifecycle() throws Exception {
        // Point the store at our temp dir by overriding Config
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        assertTrue(store.connect());

        String dbName = "lifecycle_test";
        assertTrue(store.createDatabase(dbName));
        assertTrue(store.listDatabases().contains(dbName));
        assertTrue(store.useDatabase(dbName));
        assertEquals(dbName, store.getDBname());

        assertTrue(store.deleteDatabase(dbName));
        assertFalse(store.listDatabases().contains(dbName));

        store.disconnect();
    }

    @Test
    @Order(11)
    void createSchemaAndInsertTuple() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("schematest");
        store.useDatabase("schematest");

        // Simulate N_g (node table)
        Predicate p = new Predicate("N_g");
        p.addArg("_0", Type.Integer);
        p.addArg("_1", Type.String);
        store.createSchema("schematest", p);

        ArrayList<edu.upenn.cis.db.datalog.simpleengine.SimpleTerm> tuple = new ArrayList<>();
        tuple.add(new LongSimpleTerm(1L));
        tuple.add(new StringSimpleTerm("Person"));
        store.addTuple("N_g", tuple);

        // Query back via a simple DatalogClause
        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        DatalogClause q = parser.ParseQuery("ANS(a,b) <- N_g(a,b).");
        List<DatalogClause> cs = new ArrayList<>();
        cs.add(q);

        StoreResultSet rs = store.getQueryResult(cs);
        assertEquals(1, rs.getResultSet().size());
        assertEquals(1L, ((LongSimpleTerm) rs.getResultSet().get(0).getTuple().get(0)).getLong());
        assertEquals("Person", rs.getResultSet().get(0).getTuple().get(1).getString());

        store.deleteDatabase("schematest");
        store.disconnect();
    }

    @Test
    @Order(12)
    void initializeClearsAllRelations() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("inittest");
        store.useDatabase("inittest");

        // Create a table and a view
        Predicate p = new Predicate("N_g");
        p.addArg("_0", Type.Integer);
        p.addArg("_1", Type.String);
        store.createSchema("inittest", p);

        List<DatalogClause> viewClauses = new ArrayList<>();
        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        viewClauses.add(parser.ParseQuery("N_view(a,b) <- N_g(a,b)."));
        store.createView("N_view", viewClauses, false);

        // initialize() should drop them
        store.initialize();
        assertTrue(store.listRelations("inittest").isEmpty());

        store.deleteDatabase("inittest");
        store.disconnect();
    }

    @Test
    @Order(13)
    void createConstructorsCreatesGennewIdTable() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("ctortest");
        store.useDatabase("ctortest");

        store.createConstructors();

        // GENNEWID_MAP should now exist
        List<String> rels = store.listRelations("ctortest");
        assertTrue(rels.stream().anyMatch(r -> r.equalsIgnoreCase("GENNEWID_MAP")),
                "GENNEWID_MAP table should be created by createConstructors()");

        store.deleteDatabase("ctortest");
        store.disconnect();
    }

    // -----------------------------------------------------------------------
    // SQL generation — virtual and materialized views
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void virtualViewIsQueryable() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("viewtest");
        store.useDatabase("viewtest");

        // Build N_g and E_g base tables manually
        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("viewtest", pN);

        Predicate pE = new Predicate("E_g");
        pE.addArg("_0", Type.Integer);
        pE.addArg("_1", Type.Integer);
        pE.addArg("_2", Type.Integer);
        pE.addArg("_3", Type.String);
        store.createSchema("viewtest", pE);

        // Insert some data
        insertNode(store, 1, "Person");
        insertNode(store, 2, "Person");
        insertNode(store, 3, "City");
        insertEdge(store, 10, 1, 2, "KNOWS");
        insertEdge(store, 11, 1, 3, "LIVES_IN");

        // Create a virtual view: V_Person(id, label) <- N_g(id, label), label = 'Person'
        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        List<DatalogClause> clauses = new ArrayList<>();
        clauses.add(parser.ParseQuery("V_Person(a,b) <- N_g(a,b), b = \"Person\"."));
        store.createView("V_Person", clauses, false);

        // Query the view
        List<DatalogClause> q = new ArrayList<>();
        q.add(parser.ParseQuery("ANS(a,b) <- V_Person(a,b)."));
        StoreResultSet rs = store.getQueryResult(q);

        assertEquals(2, rs.getResultSet().size(),
                "Virtual view should return 2 Person nodes");

        store.deleteDatabase("viewtest");
        store.disconnect();
    }

    @Test
    @Order(21)
    void materializedViewIsQueryable() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("matviewtest");
        store.useDatabase("matviewtest");

        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("matviewtest", pN);

        insertNode(store, 1, "Person");
        insertNode(store, 2, "Company");

        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        List<DatalogClause> clauses = new ArrayList<>();
        clauses.add(parser.ParseQuery("MAT_Person(a,b) <- N_g(a,b), b = \"Person\"."));
        store.createView("MAT_Person", clauses, true); // materialized

        List<DatalogClause> q = new ArrayList<>();
        q.add(parser.ParseQuery("ANS(a,b) <- MAT_Person(a,b)."));
        StoreResultSet rs = store.getQueryResult(q);

        assertEquals(1, rs.getResultSet().size());
        assertEquals("Person", rs.getResultSet().get(0).getTuple().get(1).getString());

        store.deleteDatabase("matviewtest");
        store.disconnect();
    }

    @Test
    @Order(22)
    void joinQueryAcrossNodeAndEdgeTables() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());

        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("jointest");
        store.useDatabase("jointest");

        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("jointest", pN);

        Predicate pE = new Predicate("E_g");
        pE.addArg("_0", Type.Integer);
        pE.addArg("_1", Type.Integer);
        pE.addArg("_2", Type.Integer);
        pE.addArg("_3", Type.String);
        store.createSchema("jointest", pE);

        insertNode(store, 1, "Person");
        insertNode(store, 2, "Person");
        insertEdge(store, 10, 1, 2, "KNOWS");

        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);

        // Find pairs of people who know each other: ANS(a,c) <- N_g(a,'Person'), E_g(_,a,c,'KNOWS'), N_g(c,'Person')
        List<DatalogClause> q = new ArrayList<>();
        q.add(parser.ParseQuery("ANS(a,c) <- N_g(a,la), E_g(e,a,c,le), N_g(c,lc), la = \"Person\", le = \"KNOWS\", lc = \"Person\"."));
        StoreResultSet rs = store.getQueryResult(q);

        assertEquals(1, rs.getResultSet().size(), "Should find one KNOWS pair");

        store.deleteDatabase("jointest");
        store.disconnect();
    }

    // -----------------------------------------------------------------------
    // SQL generation — getSqlForDatalogClause details
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    void sqlGenerationSimpleProjection() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());
        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("sqlgen1");
        store.useDatabase("sqlgen1");

        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("sqlgen1", pN);

        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        DatalogClause c = parser.ParseQuery("ANS(a,b) <- N_g(a,b).");

        String sql = store.getSqlForDatalogClause(c);
        assertTrue(sql.contains("SELECT DISTINCT"), "Should generate SELECT DISTINCT");
        assertTrue(sql.contains("N_g"), "Should reference N_g table");
        assertTrue(sql.contains("FROM"), "Should have FROM clause");

        store.deleteDatabase("sqlgen1");
        store.disconnect();
    }

    @Test
    @Order(31)
    void sqlGenerationNegation() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());
        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("sqlgen2");
        store.useDatabase("sqlgen2");

        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("sqlgen2", pN);

        Predicate pE = new Predicate("E_g");
        pE.addArg("_0", Type.Integer);
        pE.addArg("_1", Type.Integer);
        pE.addArg("_2", Type.Integer);
        pE.addArg("_3", Type.String);
        store.createSchema("sqlgen2", pE);

        insertNode(store, 1, "Person");
        insertNode(store, 2, "Person");
        insertNode(store, 3, "Person");
        insertEdge(store, 10, 1, 2, "KNOWS");
        // Node 3 has no outgoing KNOWS edge

        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);

        // Nodes that have no outgoing edge at all (DuckDBStore uses LEFT JOIN + IS NULL
        // for correct NOT-EXISTS semantics: result is nodes with no match on the join).
        List<DatalogClause> q = new ArrayList<>();
        q.add(parser.ParseQuery("ANS(a) <- N_g(a,la), !E_g(e,a,c,l), la = \"Person\"."));
        StoreResultSet rs = store.getQueryResult(q);

        // Node 1 has an outgoing KNOWS edge (src=1), so it is excluded.
        // Nodes 2 and 3 have no outgoing edges → included.
        assertEquals(2, rs.getResultSet().size(),
                "NOT-EXISTS negation: nodes 2 and 3 have no outgoing edges");

        store.deleteDatabase("sqlgen2");
        store.disconnect();
    }

    @Test
    @Order(32)
    void sqlGenerationConstantInHead() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());
        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("sqlgen3");
        store.useDatabase("sqlgen3");

        Predicate pN = new Predicate("N_g");
        pN.addArg("_0", Type.Integer);
        pN.addArg("_1", Type.String);
        store.createSchema("sqlgen3", pN);
        insertNode(store, 5, "City");

        DatalogProgram prog = new DatalogProgram();
        DatalogParser parser = new DatalogParser(prog);
        List<DatalogClause> q = new ArrayList<>();
        // Constant in head: always emit label 'Place' regardless of stored label
        q.add(parser.ParseQuery("ANS(a,\"Place\") <- N_g(a,b)."));
        StoreResultSet rs = store.getQueryResult(q);

        assertEquals(1, rs.getResultSet().size());
        assertEquals("Place", rs.getResultSet().get(0).getTuple().get(1).getString(),
                "Head constant should appear in result");

        store.deleteDatabase("sqlgen3");
        store.disconnect();
    }

    // -----------------------------------------------------------------------
    // Hash-based GENNEWID — determinism and range
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
    void gennewIdHashIsDeterministic() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());
        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("hashtest");
        store.useDatabase("hashtest");

        store.createConstructors();

        // Verify that the same inputs always yield the same hash via a SQL round-trip.
        DuckDB db = store.getDuckForTest();
        String expr = "(CAST(hash('rule1' || '|' || CAST(42 AS VARCHAR)) % 1900000000 AS BIGINT) + 100000000)";
        StoreResultSet r1 = db.select("SELECT " + expr);
        StoreResultSet r2 = db.select("SELECT " + expr);

        long id1 = ((LongSimpleTerm) r1.getResultSet().get(0).getTuple().get(0)).getLong();
        long id2 = ((LongSimpleTerm) r2.getResultSet().get(0).getTuple().get(0)).getLong();
        assertEquals(id1, id2, "Hash-based GENNEWID must be deterministic");
        assertTrue(id1 >= 100_000_000L && id1 < 2_000_000_000L,
                "Hash-based GENNEWID must be in [100M, 2B) range");

        store.deleteDatabase("hashtest");
        store.disconnect();
    }

    @Test
    @Order(41)
    void gennewIdHashDiffersForDifferentInputs() throws Exception {
        injectConfigKey("duckdb.dbdir", tempDir.toString());
        DuckDBStore store = new DuckDBStore();
        store.connect();
        store.createDatabase("hashtest2");
        store.useDatabase("hashtest2");

        DuckDB db = store.getDuckForTest();
        String expr1 = "(CAST(hash('rule1' || '|' || CAST(1 AS VARCHAR)) % 1900000000 AS BIGINT) + 100000000)";
        String expr2 = "(CAST(hash('rule1' || '|' || CAST(2 AS VARCHAR)) % 1900000000 AS BIGINT) + 100000000)";
        long id1 = ((LongSimpleTerm) db.select("SELECT " + expr1).getResultSet().get(0).getTuple().get(0)).getLong();
        long id2 = ((LongSimpleTerm) db.select("SELECT " + expr2).getResultSet().get(0).getTuple().get(0)).getLong();

        assertNotEquals(id1, id2, "Different inputs should (very likely) yield different hash IDs");

        store.deleteDatabase("hashtest2");
        store.disconnect();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void insertNode(DuckDBStore store, long id, String label) {
        ArrayList<edu.upenn.cis.db.datalog.simpleengine.SimpleTerm> t = new ArrayList<>();
        t.add(new LongSimpleTerm(id));
        t.add(new StringSimpleTerm(label));
        store.addTuple("N_g", t);
    }

    private void insertEdge(DuckDBStore store, long eid, long src, long dst, String label) {
        ArrayList<edu.upenn.cis.db.datalog.simpleengine.SimpleTerm> t = new ArrayList<>();
        t.add(new LongSimpleTerm(eid));
        t.add(new LongSimpleTerm(src));
        t.add(new LongSimpleTerm(dst));
        t.add(new StringSimpleTerm(label));
        store.addTuple("E_g", t);
    }

    /** Inject a key/value into Config's internal map for testing purposes. */
    private static void injectConfigKey(String key, String value) throws Exception {
        java.lang.reflect.Field f = Config.class.getDeclaredField("config");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<String, String> cfg = (java.util.HashMap<String, String>) f.get(null);
        cfg.put(key, value);
    }
}
