package edu.upenn.cis.db.graphtrans.store.postgres;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.NotImplementedException;
//import org.apache.commons.lang.NotImplementedException;
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
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.GraphTransServer;
import edu.upenn.cis.db.graphtrans.datastructure.TransRule;
import edu.upenn.cis.db.graphtrans.datastructure.TransRuleList;
import edu.upenn.cis.db.graphtrans.graphdb.datalog.BaseRuleGen;
import edu.upenn.cis.db.graphtrans.store.Store;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.helper.Util;
import edu.upenn.cis.db.postgres.Postgres;
import edu.upenn.cis.db.graphtrans.catalog.SchemaMapping;

public class PostgresStore implements Store {
	final static Logger logger = LogManager.getLogger(PostgresStore.class);
	
	private HashMap<String, Postgres> postgres = new HashMap<String, Postgres>();
	private static final String default_dbname = "postgres";
	private String dbname = default_dbname; // currently connected

	private String pg_ip;
	private int pg_port;
	private String pg_username;
	private String pg_password;
	
	private boolean useInnerJoin = false;
	
	
	public Postgres getPostgres(String name) {
		name = name.toLowerCase();
		if (postgres.containsKey(name) == false) {
			Postgres pg = new Postgres();
			pg.connect(pg_ip, pg_port, pg_username, pg_password, name);
			postgres.put(name, pg);
		}
		Postgres pg = postgres.get(name);
		return pg;
	}
	
	@Override
	public void createSchema(String dbname, Predicate p) {
		String name = p.getRelName();
		StringBuilder str = new StringBuilder();
		ArrayList<Integer> indexes = new ArrayList<Integer>();
		boolean isBaseRels = false;
		if (p.getRelName().contentEquals(Config.relname_node + Config.relname_base_postfix) == true ||
				p.getRelName().contentEquals(Config.relname_edge + Config.relname_base_postfix) == true) {
			isBaseRels = true;
		}
		
		str.append("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
		for (int i = 0; i < p.getArgNameList().size(); i++) {			
			String type = "INT DEFAULT 0";
			if (p.getTypes().get(i) == Type.String) {
				type = "VARCHAR(1024)";
			}
			str.append("_"+i).append(" ").append(type);
			if (i + 1 == p.getArgNameList().size()) {
				str.append(")");
			} else {
				str.append(", ");
			}
		}
		
//		System.out.println(str);
//		Postgres pg = getPostgres(dbname);
//		System.out.println("pg: " + pg + " ==> dbname: " + dbname + " postgres: " + postgres);
		
		getPostgres(dbname).executeUpdate(str.toString());

		if (isBaseRels == true) {
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
		StringBuilder str = new StringBuilder();

		StringBuilder colsStr = new StringBuilder();
		StringBuilder colsCommaStr = new StringBuilder();
		for (int i = 0; i < cols.size(); i++) {
			colsStr.append("_"+cols.get(i));
			colsCommaStr.append("_"+cols.get(i));

			if (i + 1 != cols.size()) {
				colsCommaStr.append(", ");
			}
		}

		String indexName = name + "__" + colsStr.toString();  
		//CREATE INDEX n_g_idx ON n_g (_0,_1,_2);
		str.append("CREATE INDEX ")
		.append(indexName).append(" ON ").append(name).append(" (")
		.append(colsCommaStr.toString())
		.append(")");

		System.out.println("[PostgresStore] index: " + str);
		postgres.get(dbname).executeUpdate(str.toString());
//		System.out.println("[ADD INDEX 2] str: " + str.toString() + " Time: " + Util.getElapsedTime(tid));
	}
	
	public void addTableIndex(Predicate p, ArrayList<String> cols) {
		String name = p.getRelName();
		StringBuilder str = new StringBuilder();

		StringBuilder colsStr = new StringBuilder();
		StringBuilder colsCommaStr = new StringBuilder();
		for (int i = 0; i < cols.size(); i++) {
			colsStr.append(cols.get(i));
			colsCommaStr.append(cols.get(i));

			if (i + 1 != cols.size()) {
				colsCommaStr.append(", ");
			}
		}

		String indexName = name + "__" + colsStr.toString();  
		//CREATE INDEX n_g_idx ON n_g (_0,_1,_2);
		str.append("CREATE INDEX ")
		.append(indexName).append(" ON ").append(name).append(" (")
		.append(colsCommaStr.toString())
		.append(")");

//		System.out.println("[ADD INDEX] str: " + str.toString());
		postgres.get(dbname).executeUpdate(str.toString());

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
//		System.out.println("addTuple: " + str);
		postgres.get(dbname).executeUpdate(str.toString());
		// TODO Auto-generated method stub
	}

	public String getSqlForDatalogClauseWithMapping(DatalogClause c, SchemaMapping mapping, String dialect) {
		HashMap<String, String> varToNodeLabel = new HashMap<String, String>();
		HashMap<String, String> varToEdgeLabel = new HashMap<String, String>();
		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == false) {
				String relName = a.getRelName();
				if (relName.startsWith("N_") || relName.equals("N")) {
					String var = a.getTerms().get(0).getVar();
					String label = Util.removeQuotes(a.getTerms().get(1).toString());
					varToNodeLabel.put(var, label);
				} else if (relName.startsWith("E_") || relName.equals("E")) {
					String var = a.getTerms().get(0).getVar();
					String label = Util.removeQuotes(a.getTerms().get(3).toString());
					varToEdgeLabel.put(var, label);
				}
			}
		}

		HashMap<String, String> varToColumnExpr = new HashMap<String, String>();
		HashMap<String, String[]> valVarToNodeProp = new HashMap<String, String[]>();
		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == false) {
				String relName = a.getRelName();
				if (relName.startsWith("NP_") || relName.equals("NP")) {
					String nodeVar = a.getTerms().get(0).getVar();
					String propName = Util.removeQuotes(a.getTerms().get(1).toString());
					String valVar = a.getTerms().get(2).getVar();
					String nodeLabel = varToNodeLabel.get(nodeVar);
					String colName = mapping.getColumnForNodeProperty(nodeLabel, propName);
					if (colName != null) {
						varToColumnExpr.put(valVar, nodeVar + "." + colName);
					}
					valVarToNodeProp.put(valVar, new String[]{nodeVar, propName});
				} else if (relName.startsWith("EP_") || relName.equals("EP")) {
					String edgeVar = a.getTerms().get(0).getVar();
					String propName = Util.removeQuotes(a.getTerms().get(1).toString());
					String valVar = a.getTerms().get(2).getVar();
					String edgeLabel = varToEdgeLabel.get(edgeVar);
					String colName = mapping.getColumnForEdgeProperty(edgeLabel, propName);
					if (colName != null) {
						varToColumnExpr.put(valVar, edgeVar + "." + colName);
					}
				}
			}
		}

		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == false) {
				String relName = a.getRelName();
				if (!relName.startsWith("N") && !relName.startsWith("E") && !relName.startsWith("SIM_EDGE")) {
					int numArgs = a.getTerms().size() - 1;
					String retVar = a.getTerms().get(numArgs).getVar();
					String funcExpr = "";
					if (relName.equalsIgnoreCase("cosine_similarity") || relName.equalsIgnoreCase("l2_distance")) {
						String propVar = a.getTerms().get(0).getVar();
						String queryLiteral = a.getTerms().get(1).toString();
						String modelName = (numArgs > 2) ? a.getTerms().get(2).toString() : null;
						
						String embedCol = "gem_embed";
						String nodeVar = "";
						if (valVarToNodeProp.containsKey(propVar)) {
							nodeVar = valVarToNodeProp.get(propVar)[0];
							String propName = valVarToNodeProp.get(propVar)[1];
							String nodeLabel = varToNodeLabel.get(nodeVar);
							SchemaMapping.EmbeddingInfo embedInfo = mapping.getEmbedding(nodeLabel, propName, modelName);
							if (embedInfo != null) {
								embedCol = embedInfo.column;
							}
						}
						
						String leftExpr = nodeVar + "." + embedCol;
						String rightExpr = "get_embedding(" + queryLiteral + ")";
						if (dialect.equals("duckdb")) {
							if (relName.equalsIgnoreCase("cosine_similarity")) {
								funcExpr = "array_cosine_similarity(" + leftExpr + ", " + rightExpr + ")";
							} else {
								funcExpr = "array_distance(" + leftExpr + ", " + rightExpr + ")";
							}
						} else {
							if (relName.equalsIgnoreCase("cosine_similarity")) {
								funcExpr = "(1 - (" + leftExpr + " <=> " + rightExpr + "))";
							} else {
								funcExpr = "(" + leftExpr + " <-> " + rightExpr + ")";
							}
						}
					} else {
						StringBuilder args = new StringBuilder();
						for (int k = 0; k < numArgs; k++) {
							if (k > 0) args.append(", ");
							String argVal = a.getTerms().get(k).toString();
							if (varToColumnExpr.containsKey(argVal)) {
								args.append(varToColumnExpr.get(argVal));
							} else {
								args.append(argVal);
							}
						}
						funcExpr = relName + "(" + args.toString() + ")";
					}
					varToColumnExpr.put(retVar, funcExpr);
				}
			}
		}

		ArrayList<String> tablesList = new ArrayList<String>();
		ArrayList<String> joinConditions = new ArrayList<String>();

		for (Map.Entry<String, String> entry : varToNodeLabel.entrySet()) {
			String var = entry.getKey();
			String label = entry.getValue();
			String table = mapping.getTableForNode(label);
			tablesList.add(table + " AS " + var);
		}

		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == false) {
				String relName = a.getRelName();
				if (relName.startsWith("E_") || relName.equals("E")) {
					String edgeVar = a.getTerms().get(0).getVar();
					String srcVar = a.getTerms().get(1).getVar();
					String tgtVar = a.getTerms().get(2).getVar();
					String edgeLabel = Util.removeQuotes(a.getTerms().get(3).toString());

					SchemaMapping.EdgeMapping em = mapping.edges.get(edgeLabel);
					if (em != null) {
						String edgeTable = em.table;
						String srcTable = mapping.getTableForNode(varToNodeLabel.get(srcVar));
						String tgtTable = mapping.getTableForNode(varToNodeLabel.get(tgtVar));

						if (edgeTable.equals(srcTable) && edgeTable.equals(tgtTable)) {
							String srcKeyCol = em.source.key;
							String tgtKeyCol = em.target.key;
							String srcRefKey = em.source.ref_key;
							String tgtRefKey = em.target.ref_key;
							if (srcKeyCol.equals(em.source.ref_key)) {
								joinConditions.add(tgtVar + "." + tgtKeyCol + " = " + srcVar + "." + srcRefKey);
							} else {
								joinConditions.add(srcVar + "." + srcKeyCol + " = " + tgtVar + "." + tgtRefKey);
							}
						} else {
							tablesList.add(edgeTable + " AS " + edgeVar);
							joinConditions.add(edgeVar + "." + em.source.key + " = " + srcVar + "." + em.source.ref_key);
							joinConditions.add(edgeVar + "." + em.target.key + " = " + tgtVar + "." + em.target.ref_key);
						}
					}
				} else if (relName.startsWith("SIM_EDGE_") || relName.equals("SIM_EDGE")) {
					String srcVar = a.getTerms().get(1).getVar();
					String tgtVar = a.getTerms().get(2).getVar();
					String op = Util.removeQuotes(a.getTerms().get(3).toString());
					String threshold = a.getTerms().get(4).toString();

					String srcLabel = varToNodeLabel.get(srcVar);
					String tgtLabel = varToNodeLabel.get(tgtVar);

					String srcEmbedCol = "gem_embed";
					String tgtEmbedCol = "gem_embed";

					SchemaMapping.PathQueryOverride override = mapping.getPathQueryOverride(srcLabel, tgtLabel);
					if (override != null) {
						SchemaMapping.EmbeddingInfo srcInfo = mapping.getEmbedding(srcLabel, override.source_embedding, null);
						if (srcInfo != null) srcEmbedCol = srcInfo.column;
						SchemaMapping.EmbeddingInfo tgtInfo = mapping.getEmbedding(tgtLabel, override.target_embedding, null);
						if (tgtInfo != null) tgtEmbedCol = tgtInfo.column;
					} else {
						SchemaMapping.EmbeddingInfo srcInfo = mapping.getEmbedding(srcLabel, null, null);
						if (srcInfo != null) srcEmbedCol = srcInfo.column;
						SchemaMapping.EmbeddingInfo tgtInfo = mapping.getEmbedding(tgtLabel, null, null);
						if (tgtInfo != null) tgtEmbedCol = tgtInfo.column;
					}

					String leftExpr = srcVar + "." + srcEmbedCol;
					String rightExpr = tgtVar + "." + tgtEmbedCol;
					if (dialect.equals("duckdb")) {
						if (op.equals("<=>")) {
							joinConditions.add("array_cosine_similarity(" + leftExpr + ", " + rightExpr + ") > " + threshold);
						} else {
							joinConditions.add("array_distance(" + leftExpr + ", " + rightExpr + ") < " + threshold);
						}
					} else {
						if (op.equals("<=>")) {
							joinConditions.add("1 - (" + leftExpr + " <=> " + rightExpr + ") > " + threshold);
						} else {
							joinConditions.add(leftExpr + " <-> " + rightExpr + " < " + threshold);
						}
					}
				}
			}
		}

		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == true) {
				String op = a.getPredicate().getRelName();
				String lop = a.getTerms().get(0).toString();
				String rop = a.getTerms().get(1).toString();

				String subLop = varToColumnExpr.containsKey(lop) ? varToColumnExpr.get(lop) : lop;
				String subRop = varToColumnExpr.containsKey(rop) ? varToColumnExpr.get(rop) : rop;

				subLop = subLop.replace("\"", "'");
				subRop = subRop.replace("\"", "'");

				joinConditions.add(subLop + " " + op + " " + subRop);
			}
		}

		Atom head = c.getHead();
		ArrayList<String> selects = new ArrayList<String>();
		for (int i = 0; i < head.getTerms().size(); i++) {
			String termVar = head.getTerms().get(i).getVar();
			if (varToColumnExpr.containsKey(termVar)) {
				selects.add(varToColumnExpr.get(termVar) + " AS _" + i);
			} else if (varToNodeLabel.containsKey(termVar)) {
				String pk = mapping.getPrimaryKeyForNode(varToNodeLabel.get(termVar));
				selects.add(termVar + "." + pk + " AS _" + i);
			} else {
				selects.add(termVar + " AS _" + i);
			}
		}

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT DISTINCT ");
		for (int i = 0; i < selects.size(); i++) {
			if (i > 0) sql.append(", ");
			sql.append(selects.get(i));
		}
		sql.append(" FROM ");
		for (int i = 0; i < tablesList.size(); i++) {
			if (i > 0) sql.append(", ");
			sql.append(tablesList.get(i));
		}
		if (joinConditions.size() > 0) {
			sql.append(" WHERE ");
			for (int i = 0; i < joinConditions.size(); i++) {
				if (i > 0) sql.append(" AND ");
				sql.append(joinConditions.get(i));
			}
		}
		return sql.toString();
	}

	public String getSqlForDatalogClause(DatalogClause c) {
		SchemaMapping mapping = Config.getSchemaMapping();
		if (mapping != null) {
			return getSqlForDatalogClauseWithMapping(c, mapping, mapping.target_dialect != null ? mapping.target_dialect : "postgresql");
		}
		/*
		 * 1. Create a query with (multiple) join(s) from positive IDBs and interpreted atoms
		 * 2. For each negative atom, augment the query with EXCEPT or LEFT JOIN 
		 */
		StringBuilder str = new StringBuilder();

		ArrayList<String> selects = new ArrayList<String>();
		HashMap<Integer, String> tables = new HashMap<Integer, String>();
		ArrayList<String> leftjoinTables = new ArrayList<String>();
		ArrayList<String> wheres = new ArrayList<String>();
		
		HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings = new HashMap<String, ArrayList<Pair<Integer, Integer>>>();
		HashMap<String, String> varOnlyInInterpretedAtoms = new HashMap<String, String>();
		HashMap<String, String> substituteVarByVar = new HashMap<String, String>();
		
		// Create SQL
		System.out.println("ccccc: " + c);
		handlePostiveAtoms(c, varBindings, substituteVarByVar, wheres, tables);
		handleInterpretedAtoms(c, varBindings, substituteVarByVar, wheres, varOnlyInInterpretedAtoms); 
		handleHeadAtom(c, varBindings, substituteVarByVar, selects, varOnlyInInterpretedAtoms);
		handleNegativeAtoms(c, str, varBindings, selects, tables, wheres, leftjoinTables);

//		System.out.println("[getSqlForDatalogClause] str: " + str);

		return str.toString();
	}

	private void handlePostiveAtoms(DatalogClause c, HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
			HashMap<String, String> substituteVarByVar, ArrayList<String> wheres, HashMap<Integer, String> tables) {
		// 1. populate varBindigs and substituteVarByVar from positive atoms
		for (int i = 0; i < c.getBody().size(); i++) {
			Atom a = c.getBody().get(i);
			if (a.isNegated() == false && a.isInterpreted() == false) {
				String relName = a.getRelName();
				if (relName.startsWith(Config.relname_gennewid + "_MAP_") == true) {
					int j = a.getTerms().size() - 1;
					String var = a.getTerms().get(j).getVar();
					String alias = "_" + j;
					varBindings.put(var, new ArrayList<Pair<Integer, Integer>>());
					varBindings.get(var).add(Pair.of(i, -1));
//					substituteVarByVar.put(a.getTerms().get(i).getVar(), alias);
				} else {				
					for (int j = 0; j < a.getTerms().size(); j++) {
						Term t = a.getTerms().get(j);
						String var = t.toString();
						var = var.replace("\"", "'");
						if (var.contentEquals("_") == false) {
							if (t.isVariable() == true) {
								if (varBindings.containsKey(var) == false) {
									varBindings.put(var, new ArrayList<Pair<Integer, Integer>>());
								}
								varBindings.get(var).add(Pair.of(i,j));
							} else { // constant
								wheres.add("R" + i + "._" + j + " = " + var);
							}
						}
					}
					tables.put(i, relName);
				}
			}
		}
		
		// 2. populate varBindings from positive atoms
		if (useInnerJoin == false) {
			for (Map.Entry e : varBindings.entrySet()) {
				String var = (String)e.getKey();
				ArrayList<Pair<Integer, Integer>> p = (ArrayList<Pair<Integer, Integer>>) e.getValue();
	
				if (p.size() > 1) {
					String relL = "R" + varBindings.get(var).get(0).getLeft();
					String colL= "_" + varBindings.get(var).get(0).getRight();
					for (int i = 1; i < p.size(); i++) {
						String relR = "R" + varBindings.get(var).get(i).getLeft();
						String colR = "_" + varBindings.get(var).get(i).getRight();
						wheres.add(relL + "." + colL + " = " + relR + "." + colR);
					}
				}
			}
		}
		
//		System.out.println("varBindings: " + varBindings);
//		System.out.println("whereas: " + wheres);
	}

	private void handleInterpretedAtoms(DatalogClause c, HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
			HashMap<String, String> substituteVarByVar, ArrayList<String> wheres, 
			HashMap<String, String> varOnlyInInterpretedAtoms) {
		// 1. interpreted atoms // (var = constant)

		
		for (Atom a : c.getBody()) {
			if (a.isInterpreted() == true && a.getTerms().get(1).isConstant() == true) {
				String var = a.getTerms().get(0).toString();
				String operator = a.getPredicate().getRelName();
				String operand = a.getTerms().get(1).toString();
				
				if (varBindings.containsKey(var) == true) { // N(a,b),a=5
					String rel = "R" + varBindings.get(var).get(0).getLeft();
					String col = "_" + varBindings.get(var).get(0).getRight();
					operand = operand.replace("\"", "'");
					wheres.add(rel + "." + col + " " + operator + " " + operand);
					varOnlyInInterpretedAtoms.put(var, rel + "." + col);
				} else { // N(a,b),c=0 -- will be used with b=c
					// doesn't appear in any other atoms - maybe appear in the head.
					//System.out.println("here: " + a.getPredicate() + " Config.predOpEq: " + Config.predOpEq);
					if (a.isInterpreted() == false) {
						throw new IllegalArgumentException("var[" + var + "] in header is not in the body, and it should be equality. atom: " + a);
					} else {
						varOnlyInInterpretedAtoms.put(var, operand);
					}
				}
			}
		}
		
//		System.out.println("wheres1: " + wheres);

		// 2. interpreted atoms (var = var)
		for (Atom a : c.getBody()) {	
			if (a.isInterpreted() == true && a.getTerms().get(1).isVariable() == true) {
				String var = a.getTerms().get(0).toString();
				String operator = a.getPredicate().getRelName();
				String operand = a.getTerms().get(1).toString();

				if (varOnlyInInterpretedAtoms.containsKey(operand) == true) { // N(a,b),c=5,b=c 
					String val = varOnlyInInterpretedAtoms.get(operand);
					val = val.replace("\"", "'");
					if (varBindings.containsKey(var) == true) {
						String rel = "R" + varBindings.get(var).get(0).getLeft();
						String col = "_" + varBindings.get(var).get(0).getRight();
						wheres.add(rel + "." + col + " " + operator + " " + val);
						varOnlyInInterpretedAtoms.put(var, val);
					} else { // head
						varOnlyInInterpretedAtoms.put(var, val);
					}
				}
				else {
					if (varBindings.containsKey(operand) == true) {
						String rel = "R" + varBindings.get(operand).get(0).getLeft();
						String col = "_" + varBindings.get(operand).get(0).getRight();
						varOnlyInInterpretedAtoms.put(var, rel + "." + col);
					} else { // maybe in the head
						substituteVarByVar.put(operand, var);
					}
				}
			}
		}
		
//		System.out.println("varBindings: " + varBindings);
	}
	
	/*
	 rel1 
	 	inner join rel2 
	 		on rel1.attr1 = rel2.attr2 AND ... // all of rel2's attr and all of attr rel1 (appearing up to now) 
	 	inner join rel3
	 		on ...
	 	for each R0.A = R1.B, store (R0,A,R1,B)	 		
	 	arraylist of rels
	 	
	 
	 */
	
	
	private void handleNegativeAtoms(DatalogClause c, StringBuilder str, HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
			ArrayList<String> selects, HashMap<Integer, String> tables, ArrayList<String> wheres, ArrayList<String> leftjoinTables) {
		// handle negative atoms
		
		System.out.println("WHERES ==> " + wheres);
		System.out.println("varBindings ==> " + varBindings);
				
//		 for (Map.Entry<String, ArrayList<Pair<Integer, Integer>>> entry : varBindings.entrySet()) {
//			ArrayList<Pair<Integer, Integer>> value = entry.getValue();
//			if (value.size() > 1) {
//				System.out.println("varBindings ==> " + varBindings);
//				/*
//				 	(0,1),(2,2),(10,0)
//				 	0 -> 1 -> (2,2),(10,0)
//				 	2 -> 2 -> (0,1), (10,0)
//				 */
//						
//			}
//        }
		
		
		for (int i = 0; i < c.getBody().size(); i++) {
			Atom a = c.getBody().get(i);
			if (a.isNegated() == true && a.isInterpreted() == false) {
				String rel = a.getPredicate().getRelName();
				String alias = "R" + i;
				StringBuilder leftJoinStr = new StringBuilder();
				leftJoinStr.append("LEFT JOIN " + rel + " AS " + alias + " ON ");

				int totalFound = 0;
				for (int j = 0; j < a.getTerms().size(); j++) {
					String var = a.getTerms().get(j).toString();
					if (varBindings.containsKey(var) == true) {
						if (totalFound > 0) {
							leftJoinStr.append(" AND ");
						}
						String relL = "R" + varBindings.get(var).get(0).getLeft();
						String colL = "_" + varBindings.get(var).get(0).getRight();
						String relR = alias;
						String colR= "_" + j;
						leftJoinStr.append(relL).append(".").append(colL).append("=")
						.append(relR).append(".").append(colR);

						wheres.add(relR + "." + colR + " IS NOT NULL");
						totalFound++;
					}
				}
				leftjoinTables.add(leftJoinStr.toString());
			}
		}

		str.append("SELECT DISTINCT ");
		for (int i = 0; i < selects.size(); i++) {
			str.append(selects.get(i));
			if (i + 1 < selects.size()) {
				str.append(", ");
			}
		}
		str.append(" FROM ");
		
		//##############################
		if (useInnerJoin == true) {
			ArrayList<ArrayList<Integer>> relVarSets = new ArrayList<ArrayList<Integer>>();
			
	        for (String var : varBindings.keySet()) {   
	        	ArrayList<Pair<Integer, Integer>> bindings = varBindings.get(var); 
	
	            for (int i = 0; i < bindings.size(); i++) {
	        		Pair<Integer, Integer> p1 = bindings.get(i);
	            	for (int j = i + 1; j < bindings.size(); j++) {
	            		Pair<Integer, Integer> p2 = bindings.get(j);
	            		ArrayList<Integer> arr = new ArrayList<Integer>();
	            		if (p1.getLeft() < p2.getLeft()) {
	            			arr.add(p1.getLeft());
	            			arr.add(p2.getLeft());
	            			arr.add(p1.getRight());
	            			arr.add(p2.getRight());
	            		} else {
	            			arr.add(p2.getLeft());
	            			arr.add(p1.getLeft());
	            			arr.add(p2.getRight());
	            			arr.add(p1.getRight());
	            		}
	        			relVarSets.add(arr);
	            	}
	            }
	        } 
	//        System.out.println("relVarSets: " + relVarSets);
	        
	        HashSet<Integer> checkedRelIDs = new HashSet<Integer>();
	
	        boolean hasInnerJoin = relVarSets.size() > 0;
	        if (relVarSets.size() > 0) {
	            // start with the first join
	    		int r0 = relVarSets.get(0).get(0);
	    		int r1 = relVarSets.get(0).get(1);
	    		String t0 = tables.get(r0);
	    		String t1 = tables.get(r1);
	    		str.append(t0 + " AS R" + r0 + " INNER JOIN " + t1 + " AS R" + r1);
	    		checkedRelIDs.add(r0);
	    		checkedRelIDs.add(r1);
	    		
	        	boolean usedON = false;
		        while(relVarSets.size() > 0) {
		        	boolean checkedAll = true;
		        	for (int i = 0; i < relVarSets.size(); i++) {
		        		// if both rel are already in join, put this them in ON
		        		r0 = relVarSets.get(i).get(0);
		        		r1 = relVarSets.get(i).get(1);
		        		int a0 = relVarSets.get(i).get(2);
		        		int a1 = relVarSets.get(i).get(3);
		        		
		        		if (checkedRelIDs.contains(r0) == true && checkedRelIDs.contains(r1) == true) {
		        			str.append("\n\t");
		        			if (usedON == false) {
		        				str.append("ON ");
		        				usedON = true;
		        			} else {
		        				str.append("AND ");
		        			}
		        			str.append("R" + r0 + "._" + a0 + " = R" + r1 + "._" + a1 + " ");
		        			relVarSets.remove(i);
		        			checkedAll = false;
		        			break;
		        		}
		        	}
		        	if (checkedAll == false) {
		        		continue;
		        	}
	        		usedON = false;
	
		        	if (relVarSets.size() > 0) {
		        		r0 = relVarSets.get(0).get(0);
		        		r1 = relVarSets.get(0).get(1);
		        		t0 = tables.get(r0);
		        		t1 = tables.get(r1);
		        		
		        		if (checkedRelIDs.contains(r0) == false && checkedRelIDs.contains(r1) == false) {
		        			str.append("\nCROSS JOIN " + t0 + " AS R" + r0);
		        			checkedRelIDs.add(r0);
		        		} else if (checkedRelIDs.contains(r0) == false) {
		        			str.append("\nINNER JOIN " + t0 + " AS R" + r0);
		            		checkedRelIDs.add(r0);
		        		} else if (checkedRelIDs.contains(r1) == false) {
		        			str.append("\nINNER JOIN " + t1 + " AS R" + r1);
		        			checkedRelIDs.add(r1);
		        		} 
		        	}
		        }
	        }
	        
			HashSet<Integer> relCrossJoins = new LinkedHashSet<Integer>();
	
	        System.out.println("checkedRelIDs: " + checkedRelIDs);
	
	        for (String var : varBindings.keySet()) {   
	        	ArrayList<Pair<Integer, Integer>> bindings = varBindings.get(var); 
	        	if (bindings.size() == 1 && checkedRelIDs.contains(bindings.get(0).getLeft()) == false) {
	        		if (bindings.get(0).getRight() >= 0) {
	        			relCrossJoins.add(bindings.get(0).getLeft());
	        			continue;
	        		}
	        	}
	        }
	        
	        boolean isFirst = true;
	        System.out.println("relCrossJoins: " + relCrossJoins);
	        System.out.println("tables: " + tables);
	        for (int r0 : relCrossJoins) {
	        	String t0 = tables.get(r0);
	        	if (isFirst == true && hasInnerJoin == false) {
	        		str.append("\n" + t0 + " AS R" + r0 + " ");
	        	} else {
	        		str.append("\nCROSS JOIN " + t0 + " AS R" + r0 + " ");
	        	}
	        	isFirst = false;
	        }
	        str.append("\n");
		} else {
		//##############################	
			int numRels = 0;
			int size = tables.size();
			for (Map.Entry e : tables.entrySet()) {
				int i = (int)e.getKey(); 
				String table = (String)e.getValue();
	
				if (numRels > 0) {
					str.append(" CROSS JOIN ");
				}
				str.append(table).append(" AS ").append("R").append(i);	
	
//				if (numRels > 0) {
//					str.append(" ON 1 = 1\n");
//				}
				numRels++;
			}
		}
		
		for (int i = 0; i < leftjoinTables.size(); i++) {
			str.append(" ");
			str.append(leftjoinTables.get(i));
		}

		if (wheres.size() > 0) {
			str.append(" WHERE ");
			for (int i = 0; i < wheres.size(); i++) {
				str.append(wheres.get(i));
				if (i + 1 < wheres.size()) {
					str.append(" AND ");
				}
			}
		}
	}

	private void handleHeadAtom(DatalogClause c, HashMap<String, ArrayList<Pair<Integer, Integer>>> varBindings,
			HashMap<String, String> substituteVarByVar, ArrayList<String> selects,
			HashMap<String, String> varOnlyInInterpretedAtoms) {
		
		Atom head = c.getHead();
		int size1 = head.getTerms().size();

//		System.out.println(Util.CYAN_BACKGROUND + "[handleHeadAtom] head: " + head + Util.ANSI_RESET);
//		System.out.println("[handleHeadAtom] varBindings: " + varBindings);
//		System.out.println("[handleHeadAtom] varOnlyInInterpretedAtoms: " + varOnlyInInterpretedAtoms);
		

		for (int i = 0; i < size1; i++) {
			String var = head.getTerms().get(i).getVar();
//			System.out.println("rel: " + head.getRelName() + " size1: " + size1 + " i: " +i + " var: " + var);

			if (head.getRelName().startsWith(Config.relname_gennewid + "_") == true) { //&& i == size1-1) {
				//  gennewid_const('v0_1',variadic Array[_0,_1,_2])
//				String const_select = Config.relname_gennewid + "_CONST('v0_1', variadic Array[";
//				for (int j = 0; j < selects.size(); j++) {
//					if (j > 0) {
//						const_select += ",";
//					}
//					const_select += "_" + j;
//				}
//				const_select += "])";
//				selects.add(const_select);
				continue;
			}
			
			if (head.getTerms().get(i).isVariable() == true) {
				if (varBindings.containsKey(var) == false) {
					if (varOnlyInInterpretedAtoms.containsKey(var) == false) {
						if (substituteVarByVar.containsKey(var) == true) {
							String val = varOnlyInInterpretedAtoms.get(substituteVarByVar.get(var));
							val = Util.removeQuotes(val);
							val = "'" + val + "'";
//							System.out.println("CODE 355 - var: " + var + " val: " + val);
							String alias = "_" + i;	
							selects.add(val + " AS " + alias);
						} else {
//							System.out.println("[ERROR] c: " + c);
//							System.out.println("[ERROR] varBindings: " + varBindings);
//							System.out.println("[ERROR] substituteVarByVar: " + substituteVarByVar);
//							System.out.println("[ERROR] varOnlyInInterpretedAtoms: " + varOnlyInInterpretedAtoms);
//							throw new IllegalArgumentException("var[" + var + "] in header is not in the body. [2]");
							System.out.println("1412412 var[" + var + "] in header is not in the body. [2]");
							System.out.println();
							System.out.println();
							System.out.println();
							System.out.println();
							System.out.println();
						}
					} else {
						String val = varOnlyInInterpretedAtoms.get(var);
						if (val.charAt(0) == '"') {
							val = Util.removeQuotes(val);
							val = "'" + val + "'";
						} 
//						System.out.println("CODE 369 - var: " + var + " val: " + val);
						String alias = "_" + i;	
						selects.add(val + " AS " + alias);
					}
				} else {
					ArrayList<Pair<Integer, Integer>> pairs = varBindings.get(var);
					if (pairs.size() == 1 && pairs.get(0).getRight() == -1) {
						int relIndex = pairs.get(0).getLeft();
						Atom udfAtom = c.getBody().get(relIndex);
						
						String udf_arg = udfAtom.getRelName().replace(Config.relname_gennewid + "_MAP_", "");
						String select_udf = Config.relname_gennewid + "_CONST('" + udf_arg + "', VARIADIC Array[";
						
						for (int j = 0; j < udfAtom.getTerms().size()-1; j++) {
							if (j > 0) {
								select_udf += ",";
							}
							String var2 = udfAtom.getTerms().get(j).getVar();
							String rel = "R" + varBindings.get(var2).get(0).getLeft();
							String col = "_" + varBindings.get(var2).get(0).getRight();
							select_udf += rel + "." + col;
						}
						String alias = "_" + i;
						select_udf += "])";
						selects.add(select_udf + " AS " + alias);
//						throw new IllegalArgumentException("udfAtom: " + udfAtom + " pairs: " + pairs) ;
					} else {
						String rel = "R" + varBindings.get(var).get(0).getLeft();
						String col = "_" + varBindings.get(var).get(0).getRight();
						String alias = "_" + i;
						selects.add(rel + "." + col + " AS " + alias);
					}
					
//					if (nodeConstructors.contains(varBindings.get(var).get(0).getLeft()) == false
//							&& edgeConstructors.contains(varBindings.get(var).get(0).getLeft()) == false) {
//						String rel = "R" + varBindings.get(var).get(0).getLeft();
//						String col = "_" + varBindings.get(var).get(0).getRight();
//						String alias = "_" + i;
//						selects.add(rel + "." + col + " AS " + alias);
////					} else {
////						String rel = "R" + varBindings.get(var).get(0).getLeft();
////						String col = "_" + varBindings.get(var).get(0).getRight();
//						String alias = "_" + i;
////						String viewId = Util.getVarDicEncoding("view", viewName);
////						String viewId = Util.getVarDicEncoding("view", viewName);
//						Atom a = c.getBody().get(varBindings.get(var).get(0).getLeft());
////						System.out.println("a: " + a);
//						String[] splits = a.getPredicate().getRelName().split("_");
//						String args = "";
//						for (int j = 0 ; j < a.getTerms().size() - 1; j++) {
//							if (j > 0) {
//								args = args + ",";
//							}
//							String var1 = a.getTerms().get(j).getVar();
//							String rel = "R" + varBindings.get(var1).get(0).getLeft();
//							String col = "_" + varBindings.get(var1).get(0).getRight();
//
//							args = args + (rel + "." + col);
//						}
////						int viewid = Util.getVarDicEncoding("view", splits[2]);
////						Integer.parseInt(splits[3]);
////						int ruleid = Util.getVarDicEncoding("rule", splits[2]+"_"+splits[3]);
//						String viewrule = splits[2] + "_" + splits[3];
//						selects.add(Config.relname_gennewid + "_CONST('" + viewrule+"',VARIADIC Array[" + args + "])" + " AS " + alias);							
//						System.out.println("selects3441: " + selects);
//						//						if (nodeConstructors.contains(varBindings.get(var).get(0).getLeft()) == true) {
////							selects.add("constNode("+viewid+","+ruleid+",VARIADIC Array[" + args + "])" + " AS " + alias);							
////						} else {
////							selects.add("constEdge("+viewid+","+ruleid+",VARIADIC Array[" + args + "])" + " AS " + alias);
////						}
////						selects.add(rel + "." + col + " AS " + alias);
////					}
				}				
			} else { // constant
				String alias = "_" + i;
				var = Util.removeQuotes(var);
				var = "'" + var + "'";
				selects.add(var + " AS " + alias);
			}
		}
	}


	@Override
	public void createView(String name, List<DatalogClause> cs, boolean isMaterialized) {
		HashSet<String> rels = new LinkedHashSet<String>();
		HashMap<String, ArrayList<Integer>> relToIndexes = new HashMap<String, ArrayList<Integer>>();

		if (Config.isUseIVM() == true && isMaterialized == true && name != null && name.startsWith("MATCH_") == false) {
			System.out.println("[###PGIVM] create table " + name + " and insert");
			
			String query = "";
			if (name.startsWith("MAP_") == true) {
				query = "CREATE TABLE " + name + " (_0 integer NOT NULL, _1 varchar(16) NOT NULL, _2 integer NOT NULL, _3 varchar(16) NOT NULL);";
			} else if (name.startsWith("N_") == true) {
				query = "CREATE TABLE " + name + " (_0 integer NOT NULL, _1 varchar(16) NOT NULL);";
			} else if (name.startsWith("E_") == true) {
				query = "CREATE TABLE " + name + " (_0 integer NOT NULL, _1 integer NOT NULL, _2 integer NOT NULL, _3 varchar(16) NOT NULL);";
			} else {
				query = "CREATE TABLE " + name + " (_0 integer NOT NULL, _1 varchar(64) NOT NULL, _2 varchar(64) NOT NULL);";
			}
			
			System.out.println("[###PGIVM] query: " + query);
			postgres.get(dbname).executeUpdate(query);
		}
		
		for (int i = 0; i < cs.size(); i++) {
			String name1 = cs.get(i).getHead().getRelName();
			if (rels.contains(name1) == false) {
				rels.add(name1);
				relToIndexes.put(name1, new ArrayList<Integer>());
			}
			relToIndexes.get(name1).add(i);
		}
		
		String ssr_trigger = "";
		for (String name1 : rels) {
			if (name1.startsWith(Config.relname_gennewid + "_") == true) {
				continue;
			}
			StringBuilder str = new StringBuilder();
			
			
			System.out.println("name1: " + name1 + " rels: " + rels);
	
//			if (Config.isUseIVM() == true && name != null && name.startsWith("INDEX_") == true) {
//				System.out.println("name: " + name);
//				System.exit(0);;
//			}
			HashSet<Integer> edgeRels = new LinkedHashSet<Integer>();
			if (Config.isUseIVM() == true && isMaterialized == true && name1.startsWith("INDEX_") == true) {
				HashSet<String> headVars = cs.get(0).getHead().getVars();
				
				for (int i = 0; i < cs.get(0).getBody().size(); i++) {
					Atom a = cs.get(0).getBody().get(i);
					if (a.getRelName().startsWith("E") == true) {
						edgeRels.add(i);
					}
				}
				
				System.out.println("[###PGIVM] create table ... with " + name1 + " headVars: " + headVars);
				String query = "CREATE TABLE " + name1 + " (";
				if (name1.endsWith("_NP") == false) {
					for (int i = 0; i < headVars.size(); i++) {
						query += "_" + i + " integer NOT NULL";
						if (i +1 < headVars.size()) {
							query += ",\n";
						} else {
						}
					}
				} else {
					query += "_0 integer NOT NULL,\n" +
							"_1 varchar(64) NOT NULL,\n" +
							"_2 varchar(64) NOT NULL\n"; 
				}
				query += ");";				
//				System.out.println("[###PGIVM] query: " + query);
				postgres.get(dbname).executeUpdate(query);
			}
			
			if (Config.isUseIVM() == true && isMaterialized == true && name1.startsWith("MATCH_") == false) {
				str.append("INSERT INTO ")
					.append(name1).append( " (");
//				System.out.println("[###PGIVM] query: " + query);
			} else {
				str.append("CREATE ");
				if (isMaterialized == true) {
					str.append("MATERIALIZED ");
				}
				str.append("VIEW ").append(name1).append(" AS (");
			}
			for (int i = 0; i < relToIndexes.get(name1).size(); i++) {
				DatalogClause c = cs.get(relToIndexes.get(name1).get(i));
//				System.out.println(Util.GREEN_BACKGROUND + "[PGStore-createView] cs[" + i + "]: " + c + Util.ANSI_RESET);		

				if (i > 0) {
					str.append(" UNION ");
				}
				String subQuery = getSqlForDatalogClause(c);
				str.append("(").append(subQuery).append(")");
			}
			str.append(");");

			if (Config.isUseIVM() == true && isMaterialized == true && name1.startsWith("INDEX_") == true && name1.endsWith("_NP") == false) {
				System.out.println("[###PGIVM] create trigger body add... with " + name1);

				String eids = "";
				for (int p : edgeRels) {
					if (eids.contentEquals("") == false) {
						eids += "OR ";
					}
					eids += "R" + p + "._0 = NEW._0 ";
				}
				String addedWhereOr = str.toString().replace(" WHERE ", " WHERE (" + eids + ") AND ");
				ssr_trigger += addedWhereOr + "\n";
			} else {
	//			int tid = Util.startTimer();
				System.out.println("[PostgresStore] createView413: " + str.toString());
				postgres.get(dbname).executeUpdate(str.toString());
	//			System.out.println("[**] tid: " + Util.getElapsedTime(tid) + " queryName: " + name1);
			}
		}
		
		if (ssr_trigger.equals("") == false) {
			System.out.println("[###PGIVM] create trigger function");
			
			String trigger_ssr_function = "CREATE OR REPLACE FUNCTION process_ssr_edge_insertion() RETURNS TRIGGER AS $ivm_ssr_insert$\n" + 
					"DECLARE\n" +
					"BEGIN\n" +
					ssr_trigger +
					"\tRETURN NULL; -- result is ignored since this is an AFTER trigger\n" +
					"END;\n" + 
					"$ivm_ssr_insert$ LANGUAGE plpgsql;";
			
			System.out.println("[###PGIVM] create trigger register trigger_ssr_function: \n" + trigger_ssr_function);
			
			String trigger_ssr_creation = "CREATE TRIGGER ivm_ssr_insert\n" + 
					"AFTER INSERT ON e_g\n" + 
					"\tFOR EACH ROW\n" +
					"\tEXECUTE FUNCTION process_ssr_edge_insertion();\n";

			getPostgres(dbname).executeUpdate(trigger_ssr_function);
			System.out.println("===========trigger_node_function");
			getPostgres(dbname).executeUpdate(trigger_ssr_creation);
			System.out.println("===========trigger_node_creation");
						
		}
		
	}
	
	private void createView(List<DatalogClause> cs, boolean isMaterialized) {
		StringBuilder str = new StringBuilder();
		for (int i = 0; i < cs.size(); i++) {
			str.append("CREATE ");
			if (isMaterialized == true) {
				str.append("MATERIALIZED ");
			}
			String name = cs.get(i).getHead().getRelName();

			if (name.startsWith(Config.relname_gennewid + "_") == true) {
				continue;
			}
			str.append("VIEW ").append(name).append(" AS (");
//			System.out.println(Util.GREEN_BACKGROUND + "[PGStore-createView] cs[" + i + "]: " + cs.get(i) + Util.ANSI_RESET);		
			String subQuery = getSqlForDatalogClause(cs.get(i));
			str.append("(").append(subQuery).append(")");
			str.append(");");
	
			System.out.println("[PostgresStore] createView2: " + str.toString());
			postgres.get(dbname).executeUpdate(str.toString());
		}
	}

	@Override
	public StoreResultSet getQueryResult(List<DatalogClause> cs) {
		String name = cs.get(0).getHead().getPredicate().getRelName();
		
		StringBuilder str = new StringBuilder();

		str.append("(");
		for (int i = 0; i < cs.size(); i++) {
			if (i > 0) {
				str.append(" UNION ");
			}
//			System.out.println(Util.YELLOW_BACKGROUND + "[runQuery] cs: " + cs.get(i) + Util.ANSI_RESET);
			String subQuery = getSqlForDatalogClause(cs.get(i));
			str.append("(").append(subQuery).append(")");
		}
		str.append(");");
		System.out.println("[runQuery] dbname: " + dbname + " str: " + str.toString());

		StoreResultSet rs = getPostgres(dbname).select(str.toString());
		
		return rs;
	}	

	@Override
	public StoreResultSet getQueryResult(DatalogClause c) {
		throw new NotImplementedException();
//		return null;
	}
	
	@Override
	public void printRelation(String relname) {
		// TODO Auto-generated method stub
		StringBuilder str = new StringBuilder();
		str.append("SELECT count(*) FROM " + relname + "");
		getPostgres(dbname).select(str.toString());		
	}

	@Override
	public void disconnect() {
		// TODO Auto-generated method stub

//		// TODO: remove this
//		StringBuilder str = new StringBuilder();
//		str.append("SELECT * FROM n_v0");
//		StoreResultSet rs = getPostgres(dbname).select(str.toString());
//		System.out.println("[disconnect] rs(n_v0): " + rs);
//
//		str = new StringBuilder();
//		str.append("SELECT * FROM e_v0");
//		rs = getPostgres(dbname).select(str.toString());		
//		System.out.println("[disconnect] rs(e_v0): " + rs);
	
		for (Entry<String, Postgres> entry : postgres.entrySet()) {
			entry.getValue().disconnect();
			entry.setValue(null);
		}
	}

	@Override
	public boolean createDatabase(String name) {
		// TODO Auto-generated method stub
//		System.out.println("[PGStore] createDatabase name: " + name);
		name = name.toLowerCase();
		if (getPostgres(default_dbname).createDatabase(name) == false) {
			return false;
		}
		BaseRuleGen.addRule();

		ArrayList<Predicate> preds = BaseRuleGen.getPreds();
		System.out.println("preds: " + preds);
//		ArrayList<Predicate> predsLBOnly = BaseRuleGen.getPredsLBOnly();
		
//		for (Predicate p : preds) {
//			createSchema(name, p);
//		}
//		
//		for (Predicate p : predsLBOnly) {
//			createSchema(name, p);
//		}		
		
//		ArrayList<String> cols;
//			
//		String curDBname = getDBname();
		useDatabase(name);

//		initialize();

		
		
//		Predicate p1 = new Predicate("N_g");
//		p1.addArg("_0", Type.Integer);
//		createSchema(name, p1);
//		
//		cols = new ArrayList<String>();
//		cols.add("_0");
//		addTableIndex(p1, cols);
//		
//		cols = new ArrayList<String>();
//		cols.add("_1");
//		addTableIndex(p1, cols);
//		
//		Predicate p2 = new Predicate("E_g");
//		p2.addArg("_0", Type.Integer);
//		p2.addArg("_1", Type.Integer);
//		p2.addArg("_2", Type.Integer);
//		p2.addArg("_3", Type.String);
//		createSchema(name, p2);
//
//		cols = new ArrayList<String>();
//		cols.add("_0");
//		addTableIndex(p2, cols);
//
//		cols = new ArrayList<String>();
//		cols.add("_1");
//		addTableIndex(p2, cols);
//
//		cols = new ArrayList<String>();
//		cols.add("_2");
//		addTableIndex(p2, cols);
//
//		cols = new ArrayList<String>();
//		cols.add("_3");
//		addTableIndex(p2, cols);

//		useDatabase(curDBname);
		
		return true;
	}

	@Override
	public boolean useDatabase(String name) {
		// TODO Auto-generated method stub
//		System.out.println("[PostgresStore] useDatabase name: " + name + " listDatabases(): " + listDatabases());
		name = name.toLowerCase();
		if (listDatabases().contains(name) == false) {
			return false;
		}
		getPostgres(name); // establish connection as well
		dbname = name;
		return true;
	}

	@Override
	public boolean deleteDatabase(String name) {
		// TODO Auto-generated method stub
		name = name.toLowerCase();
		
		String sql = "DISCARD ALL";
		postgres.get(dbname).executeUpdate(sql);
		System.out.println("[PostgresStore] DISCARD ALL");
		
		
		ResultSet rs = getPostgres(default_dbname).getResultSetFromSelect("SELECT pg_terminate_backend (pid) FROM pg_stat_activity WHERE pg_stat_activity.datname = '" + name + "'");
		
		return getPostgres(default_dbname).dropDatabase(name);
		
//		if (listDatabases().contains(name) == true) {
//			if (postgres.containsKey(name) == true) {
//				postgres.get(name).disconnect();
//				postgres.remove(name);
//			}
//			return getPostgres(default_dbname).dropDatabase(name);
//		} else {
//			return false;
//		}
	}

	@Override
	public long importFromCSV(String relName, String filePath) {
		return getPostgres(dbname).importFromCSV(relName, filePath);
	}

	@Override
	public void initialize() {
		// TODO Auto-generated method stub
		StringBuilder str = new StringBuilder();
		str.append("(SELECT 'truncate N_g, E_g;') ") 
		.append("UNION ( ")
		.append("SELECT 'DROP VIEW ' || table_name || ' CASCADE;' ")
		.append("FROM information_schema.views ")
		.append("WHERE table_schema NOT IN ('pg_catalog', 'information_schema') ")
		.append("AND table_name !~ '^pg_') ")
		.append("UNION ")
		.append("( ")
		.append("select 'DROP MATERIALIZED VIEW ' || matviewname || ' CASCADE;' ")
		.append("from pg_matviews ")
		.append(") ")
		.append("UNION ")
		.append("( ")
		.append("select 'DROP INDEX ' || indexname || ' CASCADE;' ")
		.append("from pg_indexes where indexname !~ '^pg_' ")
		.append(");");

//		System.out.println("[postgres] : " + str.toString());

		ResultSet rs = getPostgres(dbname).getResultSetFromSelect(str.toString());

		ResultSetMetaData rsmd;
		
		ArrayList<String> stmts = new ArrayList<String>();
		try {
			rsmd = rs.getMetaData();
			while ( rs.next() ) {
				for (int i = 1; i <= rsmd.getColumnCount(); i++) {
					String val = rs.getString(i);
					stmts.add(val);
				}
			}
			rs.close();
		} catch (SQLException e) { 
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		for (int i = 0; i < stmts.size(); i++) {
//			System.out.println("[Initialize] " + stmts.get(i));
			postgres.get(dbname).executeUpdate(stmts.get(i));
		}
	}

	@Override
	public String getDBname() {
		// TODO Auto-generated method stub
		return dbname;
	}

	@Override
	public boolean connect() {
		// TODO Auto-generated method stub
		String ip = Config.get("postgres.ip");
		String port = Config.get("postgres.port");
		String username = Config.get("postgres.username");
		String password = Config.get("postgres.password");
		
		if (ip == null || port == null || username == null || password == null) {
			return false;
		}
		
		pg_ip = ip;
		pg_port = Integer.parseInt(port);
		pg_username = username;
		pg_password = password;
		
		Postgres pg = postgres.get(default_dbname);		
		if (pg == null) {
			pg = new Postgres();
			if (pg.connect(pg_ip, pg_port, pg_username, pg_password, default_dbname) == false) {
				return false;
			}
			postgres.put(default_dbname, pg);
		}
		return true; 			
	}

	@Override
	public ArrayList<String> listDatabases() {
		// TODO Auto-generated method stub
		ArrayList<String> list = new ArrayList<String>();
		ResultSet rs = postgres.get(default_dbname).getResultSetFromSelect("SELECT datname FROM pg_database WHERE datistemplate = false");

		try {
			while (rs.next()) {
				String val = rs.getString(1);
				if (val.contentEquals(default_dbname) == false) {
					list.add(val);
				}
			}
			rs.close();	
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return list;
	}


	
	@Override
	public void createView(DatalogProgram p, TransRuleList transRuleList) {
		// TODO Auto-generated method stub
//		if (Config.isUseQuerySubQueryInPostgres() == false) {
			int createdViewStartId = p.getCreatedViewId();
//			System.out.println("headRules: " + p.getHeadRules());
			for (int i = createdViewStartId; i < p.getHeadRules().size(); i++) {
				int tid3 = Util.startTimer();
				List<DatalogClause> rules = p.getRules(p.getHeadRules().get(i));
				String name = rules.get(0).getHead().getPredicate().getRelName();
				boolean isMaterialized = p.getEDBs().contains(name);
				createView(name, rules, isMaterialized);
				System.out.println("[createView] view [" + name + "] Time: " + Util.getElapsedTime(tid3));
				ArrayList<ArrayList<Integer>> idxSet = p.getIndexSet(p.getHeadRules().get(i));
				if (idxSet != null && isMaterialized == true) {
					for (int j = 0; j < idxSet.size(); j++) {
						addTableIndex(name, idxSet.get(j));
					}
				}
				p.incCreatedViewId();
				System.out.println("[createView] view index [" + name + "] Time: " + Util.getElapsedTime(tid3));
			}
//		}	
		
			if (transRuleList.getViewType().contentEquals("virtual") == true || transRuleList.getViewType().contentEquals("asr") == true) {
				return;
			}
			
		System.out.println("[createView] Add trigger on insertion....");
		
		String trigger_node_function = "CREATE OR REPLACE FUNCTION process_ivm_node_insertion() RETURNS TRIGGER AS $ivm_node_insert$\n" + 
				"DECLARE\n" +
				"BEGIN\n" +
				"\tINSERT INTO n_v0 (_0, _1) VALUES (NEW._0, NEW._1);\n" +
				"\tRETURN NULL; -- result is ignored since this is an AFTER trigger\n" +
				"END;\n" + 
				"$ivm_node_insert$ LANGUAGE plpgsql;";
//		getPostgres(dbname).executeUpdate("DROP TRIGGER IF EXISTS ivm_node_insert ON n_g;");
		
		StoreResultSet rs = getPostgres(dbname).select("SELECT version();");
		System.out.println("rsrs: " + rs.getResultSet());
		
		String trigger_node_creation = "CREATE TRIGGER ivm_node_insert\n" + 
				"AFTER INSERT ON n_g\n" + 
				"\tFOR EACH ROW\n" +
				"\tEXECUTE FUNCTION process_ivm_node_insertion();\n";

		if (transRuleList.getViewType().equals("materialized") == true) {
			getPostgres(dbname).executeUpdate(trigger_node_function);
			System.out.println("===========trigger_node_function");
			getPostgres(dbname).executeUpdate(trigger_node_creation);
			System.out.println("===========trigger_node_creation");
		}
		
		HashSet<String> headVars = new HashSet<String>();
		for (int i = 0; i < transRuleList.getTransRuleList().size(); i++) {
			String viewname = transRuleList.getViewName();
			String matchname = "MATCH_" + viewname + "_" + i;
			
			DatalogClause dc_match = p.getRules(matchname).get(0);
			headVars.addAll(dc_match.getHead().getVars());
		}
		// TODO: declare integer variables
		System.out.println("[createView] headVars: " + headVars);

		String trigger_edge_function = "CREATE OR REPLACE FUNCTION process_ivm_edge_insertion() RETURNS TRIGGER AS $ivm_edge_insert$\n" + 
				"DECLARE\n";
//		+ 
//				"        -- variables below depend on rule used.\n";
//		for (String v : headVars) {
//			trigger_edge_function += "\t" + v + " integer;\n";
//		}
		trigger_edge_function += "\t-- variables below are default variables\n" +
				"	cnt integer;\n" +
				"	curs record;\n" +
			"BEGIN\n" + 
			"DROP TABLE IF EXISTS map_temp;\n" +
			"CREATE TEMPORARY TABLE map_temp (\n" +
            "	_0      integer NOT NULL,\n" +
            "	_1      varchar(16) NOT NULL,\n" +
            "	_2      integer NOT NULL,\n" +
            "	_3      varchar(16) NOT NULL\n" + 
        	");\n";
		String viewname = transRuleList.getViewName();

		for (int i = 0; i < transRuleList.getTransRuleList().size(); i++) {
			TransRule tr = transRuleList.getTransRuleList().get(i);
			System.out.println("[createview] viewname: " + viewname);
			String mapname = "MAP_" + viewname;
			String matchname = "MATCH_" + viewname + "_" + i;
			
			DatalogClause dc_match = p.getRules(matchname).get(0);
			
			HashSet<String> matchHeadVars = dc_match.getHead().getVars();
			HashMap<String, Integer> mapVarToIdx = new HashMap<String, Integer>();
			
			int k = 0;
			for (String v : matchHeadVars) {
				mapVarToIdx.put(v,  k);
				k++;
			}
			
			ArrayList<Integer> matchEdgeRels = new ArrayList<Integer>();
			for (int j = 0; j < dc_match.getBody().size(); j++) {
				Atom a = dc_match.getBody().get(j);
				if (a.getRelName().startsWith("E") == true) {
					matchEdgeRels.add(j);
				}
			}
			
			System.out.println("[createView] print dc_match: " + dc_match);
			String sql = getSqlForDatalogClause(dc_match);
			System.out.println("[createView] print p: " + sql);
//			String into_vars = "";
//			for (String v : matchHeadVars) {
//				if (into_vars != "") {
//					into_vars += ", " + v;
//				} else {
//					into_vars += v;
//				}
//			}
//			sql = sql.replace(" FROM ", " INTO " + into_vars + " FROM ");
			String where_eid = "";
			for (int j = 0; j < matchEdgeRels.size(); j++) {
				int rid = matchEdgeRels.get(j);
				if (where_eid != "") {
					where_eid += " OR R" + rid + "._0 = NEW._0 ";
				} else {
					where_eid += " R" + rid + "._0 = NEW._0";
				}
			}
			sql = sql.replace(" WHERE ", " WHERE (" + where_eid + ") AND ");
//			System.out.println("[createView] print p: " + sql);
			
			trigger_edge_function += "FOR curs IN\n" + 
					"\t" + sql + "\n" +
					"LOOP\n" + 
					"\tRAISE NOTICE 'TARGET _0: %', curs._0;\n";
			
			
			
			HashMap<Atom, HashSet<String>> mm = tr.getMapMap();
			for (Atom a : mm.keySet()) {
				HashSet<String> sources = mm.get(a);
				for (String s : sources) {
					String target_label = Util.removeQuotes(a.getTerms().get(1).toString());
					int source_id = mapVarToIdx.get(s);
					String source_label = Util.removeQuotes(tr.getNodeVarToLabelMap().get(s));
					int var_num = matchHeadVars.size();
					String variadic_array = "";
					for (int j = 0; j < var_num; j++) {
						if (j > 0) {
							variadic_array += ",";
						}
						variadic_array += "curs._" + j;
					}
					
//					System.out.println("===> atom: " + target_label + " s: " + source_id + " l: " + source_label);
					trigger_edge_function += "\tINSERT INTO map_temp SELECT curs._" + source_id + ", '" + source_label + "', " +
							"GENNEWID_CONST('" + viewname + "_" + i + "', VARIADIC Array[" + variadic_array + "]), '" + target_label + "';\n";
				}
			}
			
			
//			List<DatalogClause> dcs = p.getRules(mapname);
//			// TODO: if there exists result from select above
//			for (int j = 0; j < dcs.size(); j++) {
//				DatalogClause dc = dcs.get(j);
//				// TODO: insert into TEMP_MAP
//				System.out.println("[createView] print p: " + dc.getHead());
//				
//				trigger_edge_function += "\tINSERT INTO map_temp SELECT curs._0, 'C', " +
//						"GENNEWID_CONST('" + viewname + "_" + i + "', VARIADIC Array[curs._0, curs._1, curs._2, curs._3, curs._4]), 'T';\n";
//			}
			trigger_edge_function += "END LOOP;\n";
		}
		trigger_edge_function += "" +
        	"-- below are common for rules\n" + 
            "\tINSERT INTO map_" + viewname + " SELECT _0, _1, _2, _3 FROM map_temp;\n";

		if (transRuleList.getViewType().contentEquals("materialized") == true) {
			BufferedReader reader;
	
			String fromTemplate = "";
			try {
				reader = new BufferedReader(new FileReader("src/main/resources/templates/pgivm.txt"));
				String line = reader.readLine();
	
				while (line != null) {
					fromTemplate += line.replace("$vpf$", "_" + viewname) + "\n";
	//				System.out.println(line);
					// read next line
					line = reader.readLine();
				}
				reader.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			trigger_edge_function += fromTemplate + "\n";
		}
		trigger_edge_function += "RETURN NULL; -- result is ignored since this is an AFTER trigger\n" +
				"END;\n" + 
				"$ivm_edge_insert$ LANGUAGE plpgsql;\n";

		System.out.println("trigger_edge_function: " + trigger_edge_function);
//		System.exit(0);
		
		String trigger_edge_creation = "CREATE TRIGGER ivm_edge_insert\n" + 
				"AFTER INSERT ON e_g\n" + 
				"	FOR EACH ROW EXECUTE FUNCTION process_ivm_edge_insertion();";
		
		getPostgres(dbname).executeUpdate(trigger_edge_function);
		getPostgres(dbname).executeUpdate(trigger_edge_creation);
		
	}

//	@Override
//	public void createViewIndex(List<String> rules) {
////		System.out.println("rules: " + rules);
//		// TODO Auto-generated method stub		
//		DatalogProgram p = GraphTransServer.getProgram();
//		DatalogParser parser = new DatalogParser(p);
//
//		if (Config.isUseQuerySubQueryInPostgres() == true) { // use QSQ
//			for (int i = 0; i < rules.size(); i++) {
////				System.out.println("[createIndex] rules.get(i): " + rules.get(i));
//				DatalogClause q = parser.ParseQuery(rules.get(i));
//				throw new NotImplementedException("Go");		
////				DatalogProgram rewrittenProgram = DatalogQueryRewriter.getProgramForRewrittenQuery("", p, q, true);
////			
////				for (int j = 0; j < rewrittenProgram.getHeadRules().size(); j++) {
////					List<DatalogClause> c = rewrittenProgram.getRules(rewrittenProgram.getHeadRules().get(j));					
////					String name = c.get(0).getHead().getPredicate().getRelName();
////					
////					if (j + 1 == rewrittenProgram.getHeadRules().size()) {
////						createView(name, c, true);
////					} else {
////						createView(name, c, false);
////					}
////				}
//			}
//		} else { // use Postgres's evaluation for virtual view
//			HashMap<String, List<DatalogClause>> rs = new LinkedHashMap<String, List<DatalogClause>>();
//			for (int i = 0; i < rules.size(); i++) {
//				DatalogClause q = parser.ParseQuery(rules.get(i));
//				String name = q.getHead().getPredicate().getRelName();
//				if (rs.containsKey(name) == false) {
//					rs.put(name, new ArrayList<DatalogClause>());
//				}
//				rs.get(name).add(q);
//			}
//			
//			for (String name : rs.keySet()) {
//				createView(name, rs.get(name), true);
//			}
//		}		
//	}
//	
	@Override
	public void debug() {
		
	}	
	
	@Override
	public ArrayList<String> listRelations(String dbname) {
		return null;
	}	
	
	@Override
	public String getListRelationStr(String dbname) {
		return null;
	}

	@Override
	public void createConstructors() {
		
//		System.out.println("********** LB CONST : " + GraphTransServer.getProgram().getConstructorForLB());
		String query = "CREATE TABLE " + Config.relname_gennewid + "_MAP (\n" + 
				"  NEWID SERIAL PRIMARY KEY NOT NULL,\n" + 
				"  VIEWRULEID varchar(64) NOT NULL,\n" + 
				"  INPUTS integer[]\n" + 
				");\n" + 
				"CREATE INDEX newid_vrm_idx ON " + Config.relname_gennewid + "_MAP (VIEWRULEID, INPUTS);\n" + 
				"ALTER SEQUENCE " + Config.relname_gennewid + "_MAP_NEWID_seq RESTART WITH 100000000 INCREMENT BY 1;\n";
		
		query += "CREATE OR REPLACE FUNCTION " + Config.relname_gennewid + "_CONST(varchar(64), VARIADIC arr int[])\n " +
				"  RETURNS int AS $$\n" + 
				"DECLARE\n" + 
				"  inserted_id integer;\n" + 
				"  existing_id integer;\n" + 
				"BEGIN\n" + 
				"  SELECT NEWID INTO existing_id FROM " + Config.relname_gennewid + "_MAP\n" + 
				"  WHERE VIEWRULEID = $1 AND INPUTS = $2;\n" + 
				"  IF not found THEN\n" + 
				"    INSERT INTO " + Config.relname_gennewid + "_MAP (VIEWRULEID, INPUTS) VALUES ($1,$2) RETURNING NEWID INTO inserted_id;\n" + 
				"    RETURN inserted_id;\n" + 
				"  ELSE\n" + 
				"    RETURN existing_id;\n" + 
				"  END IF;\n" + 
				"END;\n" + 
				"$$ LANGUAGE 'plpgsql';\n";

		System.out.println(query);
		getPostgres(dbname).executeUpdate(query);
		
		for (Entry<String, HashMap<String, Integer>> entry : GraphTransServer.getProgram().getConstructorForLB().entrySet()) {
			String view = entry.getKey();
			HashMap<String, Integer> map = entry.getValue();
			for (String rid : map.keySet()) {
				int inputCount = map.get(rid);
				
				/*
				 CREATE OR REPLACE FUNCTION GENNEWID_MAP_v0_1(int,int,int,int,int,int,int)
				    RETURNS int AS $$
				DECLARE
				    existing_id integer;
				BEGIN
				    SELECT NEWID INTO existing_id FROM (SELECT GENNEWID_CONST('v0_1', VARIADIC Array[$1,$2,$3,$4,$5,$6,$7])) AS T;
				END;
				$$ LANGUAGE 'plpgsql';
				 */
				query = "CREATE OR REPLACE FUNCTION " + Config.relname_gennewid + "_MAP_" + view + "_" + rid + "(";
				for (int i = 0; i < inputCount; i++) {
					if (i > 0) {
						query += ",";
					}
					query += "int";
				}
				query += ")\n";
				query += "\tRETURNS int AS $$\n";
				query += "DECLARE\n";
				query += "\texisting_id integer;\n";
				query += "BEGIN\n";
				query += "SELECT * INTO existing_id FROM " + Config.relname_gennewid + "_CONST('" + view +"_" + rid + "', VARIADIC Array[";
				for (int i = 0; i < inputCount; i++) {
					if (i > 0) {
						query += ",";
					}
					query += "$" + (i+1);
				}
				query += "]) AS T;\n";
				query += "RETURN existing_id;\n" + 
						"END;\n" + 
						"$$ LANGUAGE 'plpgsql';\n";
				getPostgres(dbname).executeUpdate(query);

				
//				System.out.println("QQQ query: " + query);
			}
		}

//		String logic = GraphTransServer.getProgram().getString(true);
//		Response res = LogicBlox.runAddBlock(Config.getWorkspace(), null, logic);

//		System.out.println("createConstructors logic: " + logic + "\nres0: " + res);
		
	}
}

/*
1. create base tables - N,E,NP,EP
2. insert tuples
3. add rules (disj)
4. run query
 */


//public static void main(String[] args) {
//Config.initialize();
//
//String dbname = "test132";
//
//Store store = new PostgresStore();
////store.connect();
//store.useDatabase(dbname);
////		store.createDatabase("test");
////		store.useDatabase("test");
//Predicate p1 = new Predicate("N");
//p1.addArg("_1", Type.Integer);
//p1.addArg("_2", Type.Integer);
//System.out.println("p1: " + p1);
//store.createSchema(p1);
//
//Predicate p2 = new Predicate("S");
//p2.addArg("_1", Type.Integer);
//p2.addArg("_2", Type.Integer);
//System.out.println("p2: " + p2);
//store.createSchema(p2);
//
//Predicate p3 = new Predicate("T");
//p3.addArg("_1", Type.Integer);
//System.out.println("p3: " + p3);
//store.createSchema(p3);
//
//Atom a1 = new Atom(p1);
//a1.appendTerm(new Term("5", false)); // _1
//a1.appendTerm(new Term("15", false)); // _2
//store.addTuple(a1);
//
//Atom a2 = new Atom(p1);
//a2.appendTerm(new Term("15", false)); // _1
//a2.appendTerm(new Term("30", false)); // _2
//store.addTuple(a2);
//
//Atom a3 = new Atom(p2);
//a3.appendTerm(new Term("5", false)); // _1
//a3.appendTerm(new Term("20", false)); // _2
//store.addTuple(a3);
//
//Atom a4 = new Atom(p3);
//a4.appendTerm(new Term("5", false)); // _1
//store.addTuple(a4);
//
//DatalogProgram p = new DatalogProgram();
//DatalogParser parser = new DatalogParser(p);
////		DatalogClause c = parser.ParseQuery("N1(a,c,d) <- N(a,b), c=10, d=\'a\'.");
////		DatalogClause c1 = parser.ParseQuery("N1(a,c) <- N(a,b), N(b,c), !S(a,c), !T(a), a=5, b=15.");
////		DatalogClause c2 = parser.ParseQuery("N1(a,c) <- N(a,b), N(b,c), a=5, b=15.");
//
//DatalogClause c = parser.ParseQuery("N1(a,111) <- N(a,15).");
//ArrayList<DatalogClause> cs = new ArrayList<DatalogClause>();
//cs.add(c);
////		cs.add(c1);
////		cs.add(c2);
//String name = cs.get(0).getHead().getPredicate().getRelName();
//store.createView(name, cs, true);
//
//store.getQueryResult(null);
//store.deleteDatabase(dbname);
//store.disconnect();
//
//System.out.println("Done.");
//}