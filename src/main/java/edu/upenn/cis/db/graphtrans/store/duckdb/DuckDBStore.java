package edu.upenn.cis.db.graphtrans.store.duckdb;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.ConjunctiveQuery.Type;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.datalog.DatalogProgram;
import edu.upenn.cis.db.datalog.simpleengine.IntegerSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.SimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.Tuple;
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.catalog.SchemaMapping;
import edu.upenn.cis.db.graphtrans.datastructure.TransRuleList;
import edu.upenn.cis.db.graphtrans.store.Store;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.graphtrans.store.postgres.PostgresStore;
import edu.upenn.cis.db.helper.Util;

public class DuckDBStore implements Store {
	private HashMap<String, Connection> connections = new HashMap<>();
	private String dbname = "default";

	@Override
	public String getDBname() {
		return dbname;
	}

	@Override
	public boolean connect() {
		try {
			Class.forName("org.duckdb.DuckDBDriver");
			String dbPath = Config.get("duckdb.database");
			if (dbPath == null || dbPath.isEmpty()) {
				dbPath = ""; // in-memory
			}
			Connection conn = DriverManager.getConnection("jdbc:duckdb:" + dbPath);
			connections.put("default", conn);
			dbname = "default";
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public void disconnect() {
		for (Connection conn : connections.values()) {
			try {
				conn.close();
			} catch (SQLException e) {
				// ignore
			}
		}
		connections.clear();
	}

	@Override
	public void initialize() {
		Connection conn = connections.get(dbname);
		if (conn != null) {
			try {
				ArrayList<String> dropQueries = new ArrayList<>();
				try (Statement stmt = conn.createStatement();
					 ResultSet rs = stmt.executeQuery("SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = 'main'")) {
					while (rs.next()) {
						String tableName = rs.getString(1);
						String tableType = rs.getString(2);
						if ("VIEW".equalsIgnoreCase(tableType)) {
							dropQueries.add("DROP VIEW IF EXISTS " + tableName + " CASCADE;");
						} else {
							dropQueries.add("DROP TABLE IF EXISTS " + tableName + " CASCADE;");
						}
					}
				}
				for (String q : dropQueries) {
					executeUpdate(q);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void createSchema(String dbname, Predicate p) {
		String name = p.getRelName();
		StringBuilder str = new StringBuilder();
		str.append("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
		for (int i = 0; i < p.getArgNameList().size(); i++) {			
			String type = "INT DEFAULT 0";
			if (p.getTypes().get(i) == Type.String) {
				type = "VARCHAR(1024)";
			}
			str.append("_").append(i).append(" ").append(type);
			if (i + 1 == p.getArgNameList().size()) {
				str.append(")");
			} else {
				str.append(", ");
			}
		}
		executeUpdate(str.toString());
	}

	@Override
	public void createView(String name, List<DatalogClause> cs, boolean isMaterialized) {
		StringBuilder str = new StringBuilder();
		if (isMaterialized) {
			str.append("CREATE TABLE ").append(name).append(" AS (");
		} else {
			str.append("CREATE VIEW ").append(name).append(" AS (");
		}
		for (int i = 0; i < cs.size(); i++) {
			DatalogClause c = cs.get(i);
			if (i > 0) {
				str.append(" UNION ");
			}
			String subQuery = getSqlForDatalogClause(c);
			str.append("(").append(subQuery).append(")");
		}
		str.append(");");
		executeUpdate(str.toString());
	}

	@Override
	public void createView(DatalogProgram p, TransRuleList transRuleList) {
		int createdViewStartId = p.getCreatedViewId();
		for (int i = createdViewStartId; i < p.getHeadRules().size(); i++) {
			List<DatalogClause> rules = p.getRules(p.getHeadRules().get(i));
			String name = rules.get(0).getHead().getPredicate().getRelName();
			boolean isMaterialized = p.getEDBs().contains(name);
			createView(name, rules, isMaterialized);
			p.incCreatedViewId();
		}
	}

	@Override
	public StoreResultSet getQueryResult(List<DatalogClause> cs) {
		StringBuilder str = new StringBuilder();
		str.append("(");
		for (int i = 0; i < cs.size(); i++) {
			if (i > 0) {
				str.append(" UNION ");
			}
			String subQuery = getSqlForDatalogClause(cs.get(i));
			str.append("(").append(subQuery).append(")");
		}
		str.append(");");
		System.out.println("[runQuery DuckDB] dbname: " + dbname + " str: " + str.toString());

		return executeSelect(str.toString());
	}

	@Override
	public StoreResultSet getQueryResult(DatalogClause c) {
		String query = getSqlForDatalogClause(c);
		return executeSelect(query);
	}

	private String getSqlForDatalogClause(DatalogClause c) {
		SchemaMapping mapping = Config.getSchemaMapping();
		if (mapping != null) {
			PostgresStore pgStore = new PostgresStore();
			return pgStore.getSqlForDatalogClauseWithMapping(c, mapping, "duckdb");
		}
		PostgresStore pgStore = new PostgresStore();
		return pgStore.getSqlForDatalogClause(c);
	}

	private void executeUpdate(String query) {
		Util.writeToFile("test_duckdb.sql", query + "\n\n", true);
		Connection conn = connections.get(dbname);
		if (conn == null) {
			try {
				useDatabase(dbname);
				conn = connections.get(dbname);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (conn != null) {
			try (Statement stmt = conn.createStatement()) {
				stmt.executeUpdate(query);
			} catch (SQLException e) {
				System.out.println("[DuckDB ERR] query: " + query + " msg: " + e.getMessage());
			}
		}
	}

	private StoreResultSet executeSelect(String query) {
		Util.writeToFile("test_duckdb.sql", query + "\n\n", true);
		StoreResultSet result = new StoreResultSet();
		Connection conn = connections.get(dbname);
		if (conn == null) {
			try {
				useDatabase(dbname);
				conn = connections.get(dbname);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (conn != null) {
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery(query)) {
				ResultSetMetaData rsmd = rs.getMetaData();
				int numCols = rsmd.getColumnCount();
				for (int i = 1; i <= numCols; i++) {
					result.getColumns().add(rsmd.getColumnName(i));
				}
				while (rs.next()) {
					Tuple<SimpleTerm> t = new Tuple<>();
					for (int i = 1; i <= numCols; i++) {
						int columnType = rsmd.getColumnType(i);
						if (columnType == Types.INTEGER || columnType == Types.BIGINT || columnType == Types.SMALLINT || columnType == Types.TINYINT) {
							int val = rs.getInt(i);
							t.getTuple().add(new LongSimpleTerm(val));
						} else {
							String val = rs.getString(i);
							t.getTuple().add(new StringSimpleTerm(val));
						}
					}
					result.getResultSet().add(t);
				}
			} catch (SQLException e) {
				System.out.println("select failed query: " + query);
				e.printStackTrace();
			}
		}
		return result;
	}

	@Override
	public void printRelation(String relname) {
	}

	@Override
	public void addTableIndex(Predicate p, ArrayList<String> cols) {
	}

	@Override
	public void addTableIndex(String name, ArrayList<Integer> arrayList) {
	}

	@Override
	public boolean createDatabase(String name) {
		name = name.toLowerCase();
		try {
			File dir = new File("duckdb_dbs");
			if (!dir.exists()) {
				dir.mkdirs();
			}
			String path = "duckdb_dbs/" + name + ".db";
			Connection conn = DriverManager.getConnection("jdbc:duckdb:" + path);
			connections.put(name, conn);
			dbname = name;
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public boolean deleteDatabase(String name) {
		name = name.toLowerCase();
		if (connections.containsKey(name)) {
			try {
				connections.get(name).close();
			} catch (SQLException e) {
				// ignore
			}
			connections.remove(name);
		}
		File f = new File("duckdb_dbs/" + name + ".db");
		if (f.exists()) {
			return f.delete();
		}
		return true;
	}

	@Override
	public boolean useDatabase(String name) {
		name = name.toLowerCase();
		if (connections.containsKey(name)) {
			dbname = name;
			return true;
		}
		try {
			String path = "duckdb_dbs/" + name + ".db";
			Connection conn = DriverManager.getConnection("jdbc:duckdb:" + path);
			connections.put(name, conn);
			dbname = name;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public ArrayList<String> listDatabases() {
		ArrayList<String> list = new ArrayList<>();
		File dir = new File("duckdb_dbs");
		if (dir.exists() && dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isFile() && f.getName().endsWith(".db")) {
						String name = f.getName().replace(".db", "");
						list.add(name);
					}
				}
			}
		}
		for (String k : connections.keySet()) {
			if (!list.contains(k)) {
				list.add(k);
			}
		}
		return list;
	}

	@Override
	public ArrayList<String> listRelations(String dbname) {
		ArrayList<String> list = new ArrayList<>();
		Connection conn = connections.get(dbname);
		if (conn != null) {
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'")) {
				while (rs.next()) {
					list.add(rs.getString(1));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return list;
	}

	@Override
	public String getListRelationStr(String dbname) {
		return listRelations(dbname).toString();
	}

	@Override
	public void addTuple(String rel, ArrayList<SimpleTerm> a) {
		StringBuilder str = new StringBuilder();
		str.append("INSERT INTO ").append(rel);
		str.append(" VALUES (");
		for (int i = 0; i < a.size(); i++) {
			if (i > 0) {
				str.append(", ");
			}
			if (a.get(i) instanceof StringSimpleTerm) {
				str.append("'").append(a.get(i).getString()).append("'");
			} else if (a.get(i) instanceof LongSimpleTerm){
				str.append(a.get(i).getLong());
			} else if (a.get(i) instanceof IntegerSimpleTerm){
				str.append(a.get(i).getInt());
			}
		}
		str.append(");");
		executeUpdate(str.toString());
	}

	@Override
	public long importFromCSV(String relName, String filePath) {
		String table = relName + Config.relname_base_postfix;
		String query = "COPY " + table + " FROM '" + filePath + "' (FORMAT CSV, HEADER);";
		try {
			executeUpdate(query);
			try (Statement stmt = connections.get(dbname).createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + table)) {
				if (rs.next()) {
					return rs.getLong(1);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}

	@Override
	public void debug() {
	}

	@Override
	public void createConstructors() {
		String query = "CREATE TABLE IF NOT EXISTS " + Config.relname_gennewid + "_MAP (\n" + 
				"  NEWID INTEGER PRIMARY KEY,\n" + 
				"  VIEWRULEID VARCHAR(64) NOT NULL,\n" + 
				"  INPUTS INTEGER[]\n" + 
				");\n";
		executeUpdate(query);
		executeUpdate("CREATE OR REPLACE MACRO GENNEWID_CONST(viewrule, inputs) AS (hash(viewrule));");
	}
}
