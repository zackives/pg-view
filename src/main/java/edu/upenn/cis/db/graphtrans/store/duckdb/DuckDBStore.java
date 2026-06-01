package edu.upenn.cis.db.graphtrans.store.duckdb;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.upenn.cis.db.ConjunctiveQuery.Atom;
import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.ConjunctiveQuery.Term;
import edu.upenn.cis.db.ConjunctiveQuery.Type;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.datalog.DatalogProgram;
import edu.upenn.cis.db.datalog.simpleengine.IntegerSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.SimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;
import edu.upenn.cis.db.duckdb.DuckDB;
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.datastructure.TransRuleList;
import edu.upenn.cis.db.graphtrans.graphdb.datalog.BaseRuleGen;
import edu.upenn.cis.db.graphtrans.store.Store;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.helper.Util;

/**
 * DuckDB backend for PGVIEW.
 *
 * Each logical PGVIEW database maps to a separate .duckdb file stored under
 * the directory configured as duckdb.dbdir in graphview.conf.
 *
 * Key differences from PostgresStore:
 *  - No PL/pgSQL; no triggers; IVM is not supported.
 *  - Materialized views are created as plain tables via CREATE TABLE AS SELECT.
 *  - Skolem IDs (GENNEWID) are computed inline as a deterministic hash expression
 *    rather than via a stored-procedure function.
 *  - CSV import uses DuckDB's COPY FROM statement.
 *  - Catalog queries target information_schema / duckdb_indexes().
 */
public class DuckDBStore implements Store {
    final static Logger logger = LogManager.getLogger(DuckDBStore.class);

    /** Open connections keyed by logical database name (lower-case). */
    private HashMap<String, DuckDB> ducks = new HashMap<>();

    /** Directory that holds the .duckdb files. */
    private String dbDir = "duckdbdata";

    /** Currently active logical database name. */
    private String dbname = null;

    private boolean useInnerJoin = false;

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private DuckDB getDuck(String name) {
        name = name.toLowerCase();
        if (!ducks.containsKey(name)) {
            DuckDB db = new DuckDB();
            String filePath = dbDir + File.separator + name + ".duckdb";
            db.connect(filePath, name);
            ducks.put(name, db);
        }
        return ducks.get(name);
    }

    private DuckDB currentDuck() {
        return getDuck(dbname);
    }

    /** Test-only accessor to the active DuckDB connection. */
    DuckDB getDuckForTest() {
        return currentDuck();
    }

    // -----------------------------------------------------------------------
    // Store lifecycle
    // -----------------------------------------------------------------------

    @Override
    public boolean connect() {
        String dir = Config.get("duckdb.dbdir");
        if (dir != null && !dir.isBlank()) {
            dbDir = dir.trim();
        }
        // Ensure the directory exists.
        File dirFile = new File(dbDir);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }
        // No persistent "default" connection required; connections are opened
        // on demand per database file.
        return true;
    }

    @Override
    public void disconnect() {
        for (Map.Entry<String, DuckDB> e : ducks.entrySet()) {
            e.getValue().disconnect();
        }
        ducks.clear();
    }

    @Override
    public String getDBname() {
        return dbname;
    }

    // -----------------------------------------------------------------------
    // Database management
    // -----------------------------------------------------------------------

    @Override
    public boolean createDatabase(String name) {
        name = name.toLowerCase();
        // Opening a connection to a new file path creates the database.
        DuckDB db = getDuck(name);
        if (db == null) {
            return false;
        }
        BaseRuleGen.addRule();
        useDatabase(name);
        return true;
    }

    @Override
    public boolean useDatabase(String name) {
        name = name.toLowerCase();
        if (!listDatabases().contains(name)) {
            return false;
        }
        getDuck(name); // ensure connection is open
        dbname = name;
        return true;
    }

    @Override
    public boolean deleteDatabase(String name) {
        name = name.toLowerCase();
        if (ducks.containsKey(name)) {
            ducks.get(name).disconnect();
            ducks.remove(name);
        }
        File f = new File(dbDir + File.separator + name + ".duckdb");
        if (f.exists()) {
            return f.delete();
        }
        return true;
    }

    @Override
    public ArrayList<String> listDatabases() {
        ArrayList<String> list = new ArrayList<>();
        File dir = new File(dbDir);
        if (dir.exists() && dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                String fname = f.getName();
                if (fname.endsWith(".duckdb")) {
                    list.add(fname.substring(0, fname.length() - ".duckdb".length()));
                }
            }
        }
        return list;
    }

    // -----------------------------------------------------------------------
    // Schema management
    // -----------------------------------------------------------------------

    @Override
    public void createSchema(String dbname, Predicate p) {
        String name = p.getRelName();
        boolean isBaseRel = name.equals(Config.relname_node + Config.relname_base_postfix)
                || name.equals(Config.relname_edge + Config.relname_base_postfix);

        StringBuilder str = new StringBuilder();
        str.append("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
        for (int i = 0; i < p.getArgNameList().size(); i++) {
            String type = "INTEGER DEFAULT 0";
            if (p.getTypes().get(i) == Type.String) {
                type = "VARCHAR(1024)";
            }
            str.append("_").append(i).append(" ").append(type);
            str.append(i + 1 == p.getArgNameList().size() ? ")" : ", ");
        }
        getDuck(dbname).executeUpdate(str.toString());

        if (isBaseRel) {
            ArrayList<Integer> indexes = new ArrayList<>();
            for (int i = 0; i < p.getArgNameList().size(); i++) {
                indexes.clear();
                indexes.add(i);
                addTableIndex(name, indexes);
            }
        }
    }

    @Override
    public void addTableIndex(String name, ArrayList<Integer> cols) {
        int tid = Util.startTimer();
        StringBuilder colsStr = new StringBuilder();
        StringBuilder colsCommaStr = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            colsStr.append("_").append(cols.get(i));
            if (i > 0) colsCommaStr.append(", ");
            colsCommaStr.append("_").append(cols.get(i));
        }
        String indexName = name + "__" + colsStr;
        String sql = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + name + " (" + colsCommaStr + ")";
        System.out.println("[DuckDBStore] index: " + sql);
        currentDuck().executeUpdate(sql);
    }

    @Override
    public void addTableIndex(Predicate p, ArrayList<String> cols) {
        String name = p.getRelName();
        StringBuilder colsStr = new StringBuilder();
        StringBuilder colsCommaStr = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            colsStr.append(cols.get(i));
            if (i > 0) colsCommaStr.append(", ");
            colsCommaStr.append(cols.get(i));
        }
        String indexName = name + "__" + colsStr;
        String sql = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + name + " (" + colsCommaStr + ")";
        currentDuck().executeUpdate(sql);
    }

    // -----------------------------------------------------------------------
    // Tuple / data loading
    // -----------------------------------------------------------------------

    @Override
    public void addTuple(String rel, ArrayList<SimpleTerm> a) {
        StringBuilder str = new StringBuilder();
        str.append("INSERT INTO ").append(rel).append(" VALUES (");
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) str.append(", ");
            if (a.get(i) instanceof StringSimpleTerm) {
                str.append("'").append(a.get(i).getString()).append("'");
            } else if (a.get(i) instanceof LongSimpleTerm) {
                str.append(a.get(i).getLong());
            } else if (a.get(i) instanceof IntegerSimpleTerm) {
                str.append(a.get(i).getInt());
            }
        }
        str.append(")");
        currentDuck().executeUpdate(str.toString());
    }

    @Override
    public long importFromCSV(String relName, String filePath) {
        return currentDuck().importFromCSV(relName, filePath);
    }

    // -----------------------------------------------------------------------
    // Initialize (clear all user relations)
    // -----------------------------------------------------------------------

    @Override
    public void initialize() {
        DuckDB db = currentDuck();

        // Collect names to drop (can't drop while iterating the catalog)
        ArrayList<String> dropViews = new ArrayList<>();
        ArrayList<String> dropTables = new ArrayList<>();
        ArrayList<String> dropIndexes = new ArrayList<>();

        ResultSet rs;

        rs = db.getResultSetFromSelect(
                "SELECT table_name FROM information_schema.views WHERE table_schema = 'main'");
        if (rs != null) {
            try {
                while (rs.next()) dropViews.add(rs.getString(1));
                rs.close();
            } catch (SQLException e) { e.printStackTrace(); }
        }

        rs = db.getResultSetFromSelect(
                "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'main' AND table_type = 'BASE TABLE'");
        if (rs != null) {
            try {
                while (rs.next()) dropTables.add(rs.getString(1));
                rs.close();
            } catch (SQLException e) { e.printStackTrace(); }
        }

        rs = db.getResultSetFromSelect(
                "SELECT index_name FROM duckdb_indexes() WHERE schema_name = 'main'");
        if (rs != null) {
            try {
                while (rs.next()) dropIndexes.add(rs.getString(1));
                rs.close();
            } catch (SQLException e) { e.printStackTrace(); }
        }

        for (String v : dropViews)   db.executeUpdate("DROP VIEW IF EXISTS " + v + " CASCADE");
        for (String t : dropTables)  db.executeUpdate("DROP TABLE IF EXISTS " + t + " CASCADE");
        for (String i : dropIndexes) db.executeUpdate("DROP INDEX IF EXISTS " + i);
    }

    // -----------------------------------------------------------------------
    // GENNEWID (Skolem IDs via hash)
    //
    // DuckDB has no stored procedures. Instead of registering a PL/pgSQL
    // function, createConstructors() creates only the GENNEWID_MAP table for
    // bookkeeping (used in logging/debugging). The actual ID generation in
    // SQL queries uses an inline hash expression produced by
    // buildGennewidHashExpr(), replacing the PostgreSQL VARIADIC function call.
    // -----------------------------------------------------------------------

    @Override
    public void createConstructors() {
        // Create the GENNEWID_MAP table for tracking purposes.
        // IDs are generated inline as hash expressions; no stored procedure needed.
        String query = "CREATE TABLE IF NOT EXISTS " + Config.relname_gennewid + "_MAP (\n"
                + "  NEWID BIGINT NOT NULL,\n"
                + "  VIEWRULEID VARCHAR(64) NOT NULL,\n"
                + "  INPUTS VARCHAR(256)\n"
                + ")";
        currentDuck().executeUpdate(query);
    }

    /**
     * Build a deterministic hash-based ID expression for use in SELECT queries.
     * Replaces the PostgreSQL GENNEWID_CONST('ruleId', VARIADIC Array[...]) call.
     *
     * The expression hashes a concatenation of the rule ID and all input column
     * references, yielding a BIGINT in the range [100_000_000, 2_000_000_000).
     */
    private String buildGennewidHashExpr(String ruleId, String variadicArray) {
        // variadicArray is comma-separated SQL column refs, e.g. "R0._0, R1._1"
        String[] parts = variadicArray.isEmpty() ? new String[0] : variadicArray.split(",\\s*");
        StringBuilder hashInput = new StringBuilder("'" + ruleId + "'");
        for (String part : parts) {
            hashInput.append(" || '|' || CAST(").append(part.trim()).append(" AS VARCHAR)");
        }
        // DuckDB hash() returns UBIGINT; mod + offset gives a positive BIGINT in range.
        return "(CAST(hash(" + hashInput + ") % 1900000000 AS BIGINT) + 100000000)";
    }

    // -----------------------------------------------------------------------
    // Datalog → SQL translation
    // (Mirrors PostgresStore logic; GENNEWID_CONST replaced with hash expr.)
    // -----------------------------------------------------------------------

    public String getSqlForDatalogClause(DatalogClause c) {
        StringBuilder str = new StringBuilder();
        ArrayList<String> selects = new ArrayList<>();
        HashMap<Integer, String> tables = new HashMap<>();
        ArrayList<String> leftjoinTables = new ArrayList<>();
        ArrayList<String> wheres = new ArrayList<>();
        HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings = new HashMap<>();
        HashMap<String, String> varOnlyInInterpretedAtoms = new HashMap<>();
        HashMap<String, String> substituteVarByVar = new HashMap<>();

        System.out.println("ccccc: " + c);
        handlePositiveAtoms(c, varBindings, substituteVarByVar, wheres, tables);
        handleInterpretedAtoms(c, varBindings, substituteVarByVar, wheres, varOnlyInInterpretedAtoms);
        handleHeadAtom(c, varBindings, substituteVarByVar, selects, varOnlyInInterpretedAtoms);
        handleNegativeAtoms(c, str, varBindings, selects, tables, wheres, leftjoinTables);

        return str.toString();
    }

    private void handlePositiveAtoms(DatalogClause c,
            HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
            HashMap<String, String> substituteVarByVar,
            ArrayList<String> wheres, HashMap<Integer, String> tables) {
        for (int i = 0; i < c.getBody().size(); i++) {
            Atom a = c.getBody().get(i);
            if (!a.isNegated() && !a.isInterpreted()) {
                String relName = a.getRelName();
                if (relName.startsWith(Config.relname_gennewid + "_MAP_")) {
                    int j = a.getTerms().size() - 1;
                    String var = a.getTerms().get(j).getVar();
                    varBindings.put(var, new ArrayList<>());
                    varBindings.get(var).add(Pair.of(i, -1));
                } else {
                    for (int j = 0; j < a.getTerms().size(); j++) {
                        Term t = a.getTerms().get(j);
                        String var = t.toString().replace("\"", "'");
                        if (!var.equals("_")) {
                            if (t.isVariable()) {
                                varBindings.computeIfAbsent(var, k -> new ArrayList<>())
                                           .add(Pair.of(i, j));
                            } else {
                                wheres.add("R" + i + "._" + j + " = " + var);
                            }
                        }
                    }
                    tables.put(i, relName);
                }
            }
        }

        if (!useInnerJoin) {
            for (Map.Entry<String, ArrayList<Pair<Integer, Integer>>> e : varBindings.entrySet()) {
                ArrayList<Pair<Integer, Integer>> p = e.getValue();
                if (p.size() > 1) {
                    String relL = "R" + p.get(0).getLeft();
                    String colL = "_" + p.get(0).getRight();
                    for (int i = 1; i < p.size(); i++) {
                        wheres.add(relL + "." + colL + " = R" + p.get(i).getLeft() + "._" + p.get(i).getRight());
                    }
                }
            }
        }
    }

    private void handleInterpretedAtoms(DatalogClause c,
            HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
            HashMap<String, String> substituteVarByVar,
            ArrayList<String> wheres,
            HashMap<String, String> varOnlyInInterpretedAtoms) {
        // Pass 1: interpreted atoms with constant operand
        for (Atom a : c.getBody()) {
            if (a.isInterpreted() && a.getTerms().get(1).isConstant()) {
                String var = a.getTerms().get(0).toString();
                String operator = a.getPredicate().getRelName();
                String operand = a.getTerms().get(1).toString().replace("\"", "'");

                if (varBindings.containsKey(var)) {
                    String rel = "R" + varBindings.get(var).get(0).getLeft();
                    String col = "_" + varBindings.get(var).get(0).getRight();
                    wheres.add(rel + "." + col + " " + operator + " " + operand);
                    varOnlyInInterpretedAtoms.put(var, rel + "." + col);
                } else {
                    if (!a.isInterpreted()) {
                        throw new IllegalArgumentException(
                                "var[" + var + "] in header is not in the body. atom: " + a);
                    }
                    varOnlyInInterpretedAtoms.put(var, operand);
                }
            }
        }

        // Pass 2: interpreted atoms with variable operand
        for (Atom a : c.getBody()) {
            if (a.isInterpreted() && a.getTerms().get(1).isVariable()) {
                String var = a.getTerms().get(0).toString();
                String operator = a.getPredicate().getRelName();
                String operand = a.getTerms().get(1).toString();

                if (varOnlyInInterpretedAtoms.containsKey(operand)) {
                    String val = varOnlyInInterpretedAtoms.get(operand).replace("\"", "'");
                    if (varBindings.containsKey(var)) {
                        String rel = "R" + varBindings.get(var).get(0).getLeft();
                        String col = "_" + varBindings.get(var).get(0).getRight();
                        wheres.add(rel + "." + col + " " + operator + " " + val);
                        varOnlyInInterpretedAtoms.put(var, val);
                    } else {
                        varOnlyInInterpretedAtoms.put(var, val);
                    }
                } else if (varBindings.containsKey(operand)) {
                    String rel = "R" + varBindings.get(operand).get(0).getLeft();
                    String col = "_" + varBindings.get(operand).get(0).getRight();
                    varOnlyInInterpretedAtoms.put(var, rel + "." + col);
                } else {
                    substituteVarByVar.put(operand, var);
                }
            }
        }
    }

    private void handleNegativeAtoms(DatalogClause c, StringBuilder str,
            HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
            ArrayList<String> selects, HashMap<Integer, String> tables,
            ArrayList<String> wheres, ArrayList<String> leftjoinTables) {
        System.out.println("WHERES ==> " + wheres);
        System.out.println("varBindings ==> " + varBindings);

        for (int i = 0; i < c.getBody().size(); i++) {
            Atom a = c.getBody().get(i);
            if (a.isNegated() && !a.isInterpreted()) {
                String rel = a.getPredicate().getRelName();
                String alias = "R" + i;
                StringBuilder ljStr = new StringBuilder("LEFT JOIN " + rel + " AS " + alias + " ON ");
                int found = 0;
                for (int j = 0; j < a.getTerms().size(); j++) {
                    String var = a.getTerms().get(j).toString();
                    if (varBindings.containsKey(var)) {
                        if (found > 0) ljStr.append(" AND ");
                        String relL = "R" + varBindings.get(var).get(0).getLeft();
                        String colL = "_" + varBindings.get(var).get(0).getRight();
                        ljStr.append(relL).append(".").append(colL)
                             .append("=").append(alias).append("._").append(j);
                        // IS NULL implements NOT EXISTS: LEFT JOIN rows where no match
                        // was found have NULL in the right-side columns.
                        wheres.add(alias + "._" + j + " IS NULL");
                        found++;
                    }
                }
                leftjoinTables.add(ljStr.toString());
            }
        }

        str.append("SELECT DISTINCT ");
        for (int i = 0; i < selects.size(); i++) {
            if (i > 0) str.append(", ");
            str.append(selects.get(i));
        }
        str.append(" FROM ");

        if (useInnerJoin) {
            appendInnerJoinFrom(str, tables, varBindings);
        } else {
            int numRels = 0;
            for (Map.Entry<Integer, String> e : tables.entrySet()) {
                if (numRels > 0) str.append(" CROSS JOIN ");
                str.append(e.getValue()).append(" AS R").append(e.getKey());
                numRels++;
            }
        }

        for (String lj : leftjoinTables) {
            str.append(" ").append(lj);
        }

        if (!wheres.isEmpty()) {
            str.append(" WHERE ");
            for (int i = 0; i < wheres.size(); i++) {
                if (i > 0) str.append(" AND ");
                str.append(wheres.get(i));
            }
        }
    }

    /** Append INNER JOIN / CROSS JOIN clauses (mirrors PostgresStore logic). */
    private void appendInnerJoinFrom(StringBuilder str,
            HashMap<Integer, String> tables,
            HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings) {
        ArrayList<ArrayList<Integer>> relVarSets = new ArrayList<>();
        for (ArrayList<Pair<Integer, Integer>> bindings : varBindings.values()) {
            for (int i = 0; i < bindings.size(); i++) {
                Pair<Integer, Integer> p1 = bindings.get(i);
                for (int j = i + 1; j < bindings.size(); j++) {
                    Pair<Integer, Integer> p2 = bindings.get(j);
                    ArrayList<Integer> arr = new ArrayList<>();
                    if (p1.getLeft() < p2.getLeft()) {
                        arr.add(p1.getLeft()); arr.add(p2.getLeft());
                        arr.add(p1.getRight()); arr.add(p2.getRight());
                    } else {
                        arr.add(p2.getLeft()); arr.add(p1.getLeft());
                        arr.add(p2.getRight()); arr.add(p1.getRight());
                    }
                    relVarSets.add(arr);
                }
            }
        }

        HashSet<Integer> checkedRelIDs = new HashSet<>();
        boolean hasInnerJoin = !relVarSets.isEmpty();

        if (hasInnerJoin) {
            int r0 = relVarSets.get(0).get(0);
            int r1 = relVarSets.get(0).get(1);
            str.append(tables.get(r0)).append(" AS R").append(r0)
               .append(" INNER JOIN ").append(tables.get(r1)).append(" AS R").append(r1);
            checkedRelIDs.add(r0);
            checkedRelIDs.add(r1);

            boolean usedON = false;
            while (!relVarSets.isEmpty()) {
                boolean checkedAll = true;
                for (int i = 0; i < relVarSets.size(); i++) {
                    r0 = relVarSets.get(i).get(0);
                    r1 = relVarSets.get(i).get(1);
                    int a0 = relVarSets.get(i).get(2);
                    int a1 = relVarSets.get(i).get(3);
                    if (checkedRelIDs.contains(r0) && checkedRelIDs.contains(r1)) {
                        str.append("\n\t").append(usedON ? "AND " : "ON ");
                        usedON = true;
                        str.append("R").append(r0).append("._").append(a0)
                           .append(" = R").append(r1).append("._").append(a1).append(" ");
                        relVarSets.remove(i);
                        checkedAll = false;
                        break;
                    }
                }
                if (checkedAll) {
                    usedON = false;
                    if (!relVarSets.isEmpty()) {
                        r0 = relVarSets.get(0).get(0);
                        r1 = relVarSets.get(0).get(1);
                        if (!checkedRelIDs.contains(r0) && !checkedRelIDs.contains(r1)) {
                            str.append("\nCROSS JOIN ").append(tables.get(r0)).append(" AS R").append(r0);
                            checkedRelIDs.add(r0);
                        } else if (!checkedRelIDs.contains(r0)) {
                            str.append("\nINNER JOIN ").append(tables.get(r0)).append(" AS R").append(r0);
                            checkedRelIDs.add(r0);
                        } else if (!checkedRelIDs.contains(r1)) {
                            str.append("\nINNER JOIN ").append(tables.get(r1)).append(" AS R").append(r1);
                            checkedRelIDs.add(r1);
                        }
                    }
                }
            }
        }

        HashSet<Integer> relCrossJoins = new LinkedHashSet<>();
        for (ArrayList<Pair<Integer, Integer>> bindings : varBindings.values()) {
            if (bindings.size() == 1 && !checkedRelIDs.contains(bindings.get(0).getLeft())
                    && bindings.get(0).getRight() >= 0) {
                relCrossJoins.add(bindings.get(0).getLeft());
            }
        }

        boolean isFirst = true;
        for (int r : relCrossJoins) {
            String t = tables.get(r);
            if (isFirst && !hasInnerJoin) {
                str.append(t).append(" AS R").append(r).append(" ");
            } else {
                str.append("\nCROSS JOIN ").append(t).append(" AS R").append(r).append(" ");
            }
            isFirst = false;
        }
        str.append("\n");
    }

    private void handleHeadAtom(DatalogClause c,
            HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
            HashMap<String, String> substituteVarByVar,
            ArrayList<String> selects,
            HashMap<String, String> varOnlyInInterpretedAtoms) {
        Atom head = c.getHead();
        int size1 = head.getTerms().size();

        for (int i = 0; i < size1; i++) {
            String var = head.getTerms().get(i).getVar();

            if (head.getRelName().startsWith(Config.relname_gennewid + "_")) {
                continue;
            }

            if (head.getTerms().get(i).isVariable()) {
                if (!varBindings.containsKey(var)) {
                    if (!varOnlyInInterpretedAtoms.containsKey(var)) {
                        if (substituteVarByVar.containsKey(var)) {
                            String val = varOnlyInInterpretedAtoms.get(substituteVarByVar.get(var));
                            val = Util.removeQuotes(val);
                            val = "'" + val + "'";
                            selects.add(val + " AS _" + i);
                        } else {
                            System.out.println("WARN: var[" + var + "] in header is not in the body.");
                        }
                    } else {
                        String val = varOnlyInInterpretedAtoms.get(var);
                        if (!val.isEmpty() && val.charAt(0) == '"') {
                            val = "'" + Util.removeQuotes(val) + "'";
                        }
                        selects.add(val + " AS _" + i);
                    }
                } else {
                    ArrayList<Pair<Integer, Integer>> pairs = varBindings.get(var);
                    if (pairs.size() == 1 && pairs.get(0).getRight() == -1) {
                        // Skolem / GENNEWID case: generate a hash-based ID inline.
                        Atom udfAtom = c.getBody().get(pairs.get(0).getLeft());
                        String udfArg = udfAtom.getRelName()
                                .replace(Config.relname_gennewid + "_MAP_", "");

                        StringBuilder variadicParts = new StringBuilder();
                        for (int j = 0; j < udfAtom.getTerms().size() - 1; j++) {
                            if (j > 0) variadicParts.append(", ");
                            String var2 = udfAtom.getTerms().get(j).getVar();
                            String rel = "R" + varBindings.get(var2).get(0).getLeft();
                            String col = "_" + varBindings.get(var2).get(0).getRight();
                            variadicParts.append(rel).append(".").append(col);
                        }
                        String hashExpr = buildGennewidHashExpr(udfArg, variadicParts.toString());
                        selects.add(hashExpr + " AS _" + i);
                    } else {
                        String rel = "R" + pairs.get(0).getLeft();
                        String col = "_" + pairs.get(0).getRight();
                        selects.add(rel + "." + col + " AS _" + i);
                    }
                }
            } else {
                // constant
                String constVal = "'" + Util.removeQuotes(var) + "'";
                selects.add(constVal + " AS _" + i);
            }
        }
    }

    // -----------------------------------------------------------------------
    // View creation
    // -----------------------------------------------------------------------

    @Override
    public void createView(String name, List<DatalogClause> cs, boolean isMaterialized) {
        HashSet<String> rels = new LinkedHashSet<>();
        HashMap<String, ArrayList<Integer>> relToIndexes = new HashMap<>();

        for (int i = 0; i < cs.size(); i++) {
            String name1 = cs.get(i).getHead().getRelName();
            relToIndexes.computeIfAbsent(name1, k -> new ArrayList<>()).add(i);
            rels.add(name1);
        }

        for (String name1 : rels) {
            if (name1.startsWith(Config.relname_gennewid + "_")) {
                continue;
            }

            // For IVM materialized tables, pre-create the table with explicit schema
            // (mirrors PostgresStore behaviour for MAP_, N_, E_, INDEX_ prefixes).
            if (Config.isUseIVM() && isMaterialized && !name1.startsWith("MATCH_")) {
                String createSql = buildIvmTableSchema(name, name1, cs);
                if (createSql != null) {
                    System.out.println("[DuckDBStore] IVM pre-create table: " + createSql);
                    currentDuck().executeUpdate(createSql);
                }
            }

            StringBuilder str = new StringBuilder();
            if (Config.isUseIVM() && isMaterialized && !name1.startsWith("MATCH_")) {
                // IVM path: populate the pre-created table
                str.append("INSERT INTO ").append(name1).append(" ");
            } else if (isMaterialized) {
                // Non-IVM materialized: create as a plain table
                str.append("CREATE TABLE ").append(name1).append(" AS ");
            } else {
                str.append("CREATE VIEW ").append(name1).append(" AS ");
            }

            for (int i = 0; i < relToIndexes.get(name1).size(); i++) {
                DatalogClause dc = cs.get(relToIndexes.get(name1).get(i));
                if (i > 0) str.append(" UNION ");
                str.append("(").append(getSqlForDatalogClause(dc)).append(")");
            }

            if (!Config.isUseIVM() || !isMaterialized || name1.startsWith("MATCH_")) {
                // VIEW and non-IVM TABLE AS SELECT need no trailing semicolon inside DuckDB
            }

            System.out.println("[DuckDBStore] createView: " + str);
            currentDuck().executeUpdate(str.toString());
        }
    }

    /**
     * Build the CREATE TABLE DDL for IVM materialized tables, matching the
     * schema conventions used by PostgresStore.
     */
    private String buildIvmTableSchema(String viewName, String name1,
            List<DatalogClause> cs) {
        if (name1.startsWith("MAP_")) {
            return "CREATE TABLE IF NOT EXISTS " + name1
                    + " (_0 INTEGER NOT NULL, _1 VARCHAR(16) NOT NULL,"
                    + " _2 INTEGER NOT NULL, _3 VARCHAR(16) NOT NULL)";
        } else if (name1.startsWith("N_")) {
            return "CREATE TABLE IF NOT EXISTS " + name1
                    + " (_0 INTEGER NOT NULL, _1 VARCHAR(16) NOT NULL)";
        } else if (name1.startsWith("E_")) {
            return "CREATE TABLE IF NOT EXISTS " + name1
                    + " (_0 INTEGER NOT NULL, _1 INTEGER NOT NULL,"
                    + " _2 INTEGER NOT NULL, _3 VARCHAR(16) NOT NULL)";
        } else if (name1.startsWith("INDEX_")) {
            if (name1.endsWith("_NP")) {
                return "CREATE TABLE IF NOT EXISTS " + name1
                        + " (_0 INTEGER NOT NULL, _1 VARCHAR(64) NOT NULL, _2 VARCHAR(64) NOT NULL)";
            }
            HashSet<String> headVars = cs.get(0).getHead().getVars();
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                    .append(name1).append(" (");
            int k = 0;
            for (String v : headVars) {
                if (k > 0) sb.append(",\n");
                sb.append("_").append(k).append(" INTEGER NOT NULL");
                k++;
            }
            sb.append(")");
            return sb.toString();
        }
        // Generic fallback: let the SELECT determine the schema
        return null;
    }

    /**
     * IVM path for materialized view creation (triggered on edge/node inserts).
     * DuckDB does not support triggers or PL/pgSQL, so this method performs a
     * one-shot materialization and logs a warning that incremental updates are
     * not supported.
     */
    @Override
    public void createView(DatalogProgram p, TransRuleList transRuleList) {
        int createdViewStartId = p.getCreatedViewId();
        for (int i = createdViewStartId; i < p.getHeadRules().size(); i++) {
            int tid = Util.startTimer();
            List<DatalogClause> rules = p.getRules(p.getHeadRules().get(i));
            String name = rules.get(0).getHead().getPredicate().getRelName();
            boolean isMaterialized = p.getEDBs().contains(name);
            createView(name, rules, isMaterialized);
            System.out.println("[createView] view [" + name + "] Time: " + Util.getElapsedTime(tid));
            ArrayList<ArrayList<Integer>> idxSet = p.getIndexSet(p.getHeadRules().get(i));
            if (idxSet != null && isMaterialized) {
                for (ArrayList<Integer> idxCols : idxSet) {
                    addTableIndex(name, idxCols);
                }
            }
            p.incCreatedViewId();
        }

        if (transRuleList.getViewType().equals("materialized")) {
            System.out.println("[DuckDBStore] WARNING: IVM (trigger-based incremental view maintenance)"
                    + " is not supported for DuckDB. Views have been materialized once."
                    + " Re-run createView after inserting new data to refresh.");
        }
    }

    // -----------------------------------------------------------------------
    // Query execution
    // -----------------------------------------------------------------------

    @Override
    public StoreResultSet getQueryResult(List<DatalogClause> cs) {
        StringBuilder str = new StringBuilder("(");
        for (int i = 0; i < cs.size(); i++) {
            if (i > 0) str.append(" UNION ");
            str.append("(").append(getSqlForDatalogClause(cs.get(i))).append(")");
        }
        str.append(")");
        System.out.println("[DuckDBStore] runQuery: " + str);
        return currentDuck().select(str.toString());
    }

    @Override
    public StoreResultSet getQueryResult(DatalogClause c) {
        throw new NotImplementedException();
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    @Override
    public void printRelation(String relname) {
        currentDuck().select("SELECT count(*) FROM " + relname);
    }

    @Override
    public void debug() { }

    @Override
    public ArrayList<String> listRelations(String dbname) {
        ArrayList<String> list = new ArrayList<>();
        ResultSet rs = getDuck(dbname).getResultSetFromSelect(
                "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'main' AND table_type = 'BASE TABLE'");
        if (rs != null) {
            try {
                while (rs.next()) list.add(rs.getString(1));
                rs.close();
            } catch (SQLException e) { e.printStackTrace(); }
        }
        return list;
    }

    @Override
    public String getListRelationStr(String dbname) {
        ArrayList<String> rels = listRelations(dbname);
        return String.join(", ", rels);
    }
}
