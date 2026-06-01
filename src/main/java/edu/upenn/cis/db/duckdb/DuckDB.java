package edu.upenn.cis.db.duckdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.SimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.Tuple;
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.helper.Util;

/**
 * DuckDB JDBC connection helper, analogous to Postgres.java.
 * Each instance manages a single JDBC connection to one DuckDB file.
 */
public class DuckDB {
    final static Logger logger = LogManager.getLogger(DuckDB.class);

    private Connection conn = null;
    private Statement stmt = null;
    private String dbname = null;

    public String getDBname() {
        return dbname;
    }

    /**
     * Connect to a DuckDB database file. filePath may be "" or ":memory:" for
     * in-memory, or an absolute/relative path for a persistent file.
     */
    public boolean connect(String filePath, String name) {
        if (conn != null) {
            disconnect();
        }
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            Properties props = new Properties();
            conn = DriverManager.getConnection("jdbc:duckdb:" + filePath, props);
            stmt = conn.createStatement();
            dbname = name;
        } catch (Exception e) {
            System.err.println("[DuckDB] connect failed for [" + filePath + "]: " + e.getMessage());
            return false;
        }
        return true;
    }

    public void disconnect() {
        if (conn != null) {
            try {
                stmt.close();
                conn.close();
                conn = null;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public void executeUpdate(String query) {
        Util.writeToFile("test_duck.sql", query + "\n\n", true);
        try {
            stmt.executeUpdate(query);
        } catch (SQLException e) {
            System.out.println("[DuckDB ERR] query: " + query + "\n  msg: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[DuckDB ERR2] query: " + query + "\n  msg: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public ResultSet getResultSetFromSelect(String query) {
        try {
            return stmt.executeQuery(query);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public StoreResultSet select(String query) {
        Util.writeToFile("test_duck.sql", query + "\n\n", true);
        StoreResultSet result = new StoreResultSet();

        try {
            System.out.println("[DuckDB] query: " + query);
            ResultSet rs = stmt.executeQuery(query);
            ResultSetMetaData rsmd = rs.getMetaData();
            for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                result.getColumns().add(rsmd.getColumnName(i));
            }
            while (rs.next()) {
                Tuple<SimpleTerm> t = new Tuple<>();
                for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                    int colType = rsmd.getColumnType(i);
                    if (colType == Types.INTEGER || colType == Types.BIGINT || colType == Types.SMALLINT) {
                        t.getTuple().add(new LongSimpleTerm(rs.getLong(i)));
                    } else {
                        t.getTuple().add(new StringSimpleTerm(rs.getString(i)));
                    }
                }
                result.getResultSet().add(t);
            }
            rs.close();
        } catch (SQLException e) {
            Util.Console.errln("[DuckDB] select failed: " + query);
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Import rows from a CSV file into a table using DuckDB's COPY FROM statement.
     * Returns the number of rows inserted.
     */
    public long importFromCSV(String relName, String filePath) {
        String cols;
        if (relName.equalsIgnoreCase("n") || relName.equalsIgnoreCase("n_g")) {
            cols = "(_0, _1)";
        } else {
            cols = "(_0, _1, _2, _3)";
        }
        String baseName = relName.toLowerCase().endsWith(Config.relname_base_postfix)
                ? relName
                : relName + Config.relname_base_postfix;
        String sql = "COPY " + baseName + " " + cols
                + " FROM '" + filePath + "' (FORMAT CSV, HEADER TRUE)";
        try {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            Util.Console.errln("[DuckDB] importFromCSV failed for [" + filePath + "]: " + e.getMessage());
            return 0;
        }
    }
}
