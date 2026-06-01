package edu.upenn.cis.db.graphtrans;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//import org.apache.commons.lang.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.logicblox.connect.BloxCommand.Column;
import com.logicblox.connect.BloxCommand.Relation;

import edu.upenn.cis.db.ConjunctiveQuery.Atom;
import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.Neo4j.Neo4jServerThread;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.datalog.DatalogParser;
import edu.upenn.cis.db.datalog.DatalogProgram;
import edu.upenn.cis.db.datalog.MagicSetRewriter;
import edu.upenn.cis.db.datalog.QueryRewriterSubstitution;
import edu.upenn.cis.db.datalog.SSR;
import edu.upenn.cis.db.datalog.rewriter.Rewriter;
import edu.upenn.cis.db.datalog.simpleengine.IntegerSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.SimpleDatalogEngine;
import edu.upenn.cis.db.datalog.simpleengine.SimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.Tuple;
import edu.upenn.cis.db.graphtrans.Config.IndexType;
import edu.upenn.cis.db.graphtrans.catalog.Catalog;
import edu.upenn.cis.db.graphtrans.catalog.Schema;
import edu.upenn.cis.db.graphtrans.catalog.SchemaEdge;
import edu.upenn.cis.db.graphtrans.catalog.SchemaNode;
import edu.upenn.cis.db.graphtrans.datastructure.Egd;
import edu.upenn.cis.db.graphtrans.datastructure.TransRule;
import edu.upenn.cis.db.graphtrans.datastructure.TransRuleList;
import edu.upenn.cis.db.graphtrans.graphdb.datalog.BaseRuleGen;
import edu.upenn.cis.db.graphtrans.graphdb.datalog.ViewRule;
import edu.upenn.cis.db.graphtrans.parser.CommandParser;
import edu.upenn.cis.db.graphtrans.parser.EgdParser;
import edu.upenn.cis.db.graphtrans.parser.QueryParser;
import edu.upenn.cis.db.graphtrans.store.Store;
import edu.upenn.cis.db.graphtrans.store.StoreFactory;
import edu.upenn.cis.db.graphtrans.store.StoreResultSet;
import edu.upenn.cis.db.graphtrans.store.logicblox.LogicBloxStore;
import edu.upenn.cis.db.graphtrans.store.neo4j.Neo4jStore;
import edu.upenn.cis.db.graphtrans.store.simpledatalog.SimpleDatalogStore;
import edu.upenn.cis.db.graphtrans.typechecker.OutputViewCheck;
import edu.upenn.cis.db.graphtrans.typechecker.RuleOverlapCheck;
import edu.upenn.cis.db.helper.Performance;
import edu.upenn.cis.db.helper.Util;
import edu.upenn.cis.db.logicblox.LogicBlox;
import edu.upenn.cis.db.postgres.Postgres;

/**
 * Command Executor class. Each method is called in the CommandParser class. 
 * @author sbnet21
 */
public class CommandExecutor {
	final static Logger logger = LogManager.getLogger(CommandExecutor.class);

	private static Console console = null;
	private static Status status = Status.NONE;
	private static boolean isQuit = false;
	
	private static Store store = null;
	private static Store storeLB = null;
	
	private enum Status {
	    NONE,	/* Not connected */
	    CONNECT, /* Connected but not use any graph */
	    USE	/* Connected and use a graph */
	};
	
	public static String getPerformance() {
		if (Performance.getJSON() == null) {
			return "";
		}
		return Performance.getJSON().toString();
	}
	
	/**
	 * Call a parser that parses and executes the command.
	 * 
	 * @param cmd Command
	 */
	public static void run(String cmd) {
//		System.out.println("[CommandExecutor] run cmd: " + cmd);
		CommandParser parser = new CommandParser();
		parser.Parse(cmd);		
	}
	
	/**
	 * Create input stream reader (from console or file)
	 * @param filepath
	 * @return
	 */
	private static InputStreamReader getInputStreamReader(String filepath) {
		InputStreamReader isr = null;

		if (filepath == null) {
			Util.Console.logln("To quit, enter quit; (including ; in the end).");			
			isr = new InputStreamReader (System.in);			
		} else {
			try {
				Util.Console.logln("Read scripts from [" + filepath + "].");
				console.setEnabled(false);
				Util.Console.setEnable(true); // FIXME: false
				FileInputStream fis = new FileInputStream(filepath);
				isr = new InputStreamReader(fis);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		return isr;
	}
	
	/**
	 * Read command line from input stream (console or file)
	 * @param isr
	 */
	// InputStreamReader isr
	public static void readCommand(String filePath) {
		String lineBuf = "";
		BufferedReader reader = new BufferedReader(getInputStreamReader(filePath));
		String line;
		String workspace = "";
		String workspaceSpace = "";

		console.write("GQL> ");
		
		try {
			while((line = reader.readLine()) != null) {
				if (line.trim().length() > 1) { // comment out
					if (line.trim().substring(0,1).contentEquals("#") == true) {
						continue;
					}
				}
				lineBuf = lineBuf + line + " ";
				if (line.equals("")) {
					console.write("  " + workspaceSpace + "-> ");
					continue;
				}
				if (line.substring(line.length()-1, line.length()).equals(";")) {
					run(lineBuf.substring(0, lineBuf.length()-2));
					if (isQuit == true) {
						break;
					}
					lineBuf = "";
					
					if (Config.getWorkspace() == null) {
						workspace = "";
					} else {
						workspace = " " + Config.getWorkspace();
						for (int i = 0; i < workspace.length(); i++) { 
							workspaceSpace += " ";
						}
					}
					console.write("GQL" + workspace + "> ");
				} else {
					console.write("  " + workspaceSpace + "-> ");
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
	}
	
	/**
	 * Connect to platform. (set the platform to use)
	 * @param platform
	 */
	public static void connect(String platform) {
		if (status != Status.NONE) {
			Util.Console.logln("Connection to [" + Config.getPlatform() + "] exists.");
			return;
		}
		
		Config.setPlatform(platform);
		StoreFactory storeFactory = new StoreFactory();

		// LogicBlox requires a secondary internal connection in addition to the
		// primary store connection. Skip this block entirely when not using LB
		// so that missing LB binaries do not prevent other backends from starting.
		if (platform.contentEquals("lb") == true) {
			try {
				storeLB = storeFactory.getStore("lb");
				if (storeLB.connect() == true) {
					Util.Console.logln("Use LB internally.");
					status = Status.CONNECT;
				} else {
					Util.Console.errln("Platform [LB] is unavailable.");
					Util.Console.errln("Check your configuration file ["
							+ Config.getConfigFile() + "] and the server.");
				}
			} catch (Exception e) {
				Util.Console.errln("LogicBlox connection failed: " + e.getMessage());
			}
		}
		
		store = storeFactory.getStore(platform);
		
		if (store.connect() == true) {
			Util.Console.logln("Use platform [" + platform + "].");
			status = Status.CONNECT;
		} else {
			Util.Console.errln("Platform [" + platform + "] is unavailable.");
			Util.Console.errln("Check your configuration file [" 
					+ Config.getConfigFile() + "] and the server.");
		}		
	}
	
	/**
	 * Disconnect to LogicBlox
	 */
	public static void disconnect() {
		if (canExecuteCommand(Status.CONNECT) == false) {
			Util.Console.errln("No connection exists.");	
		} else {
			store.disconnect();
			status = Status.NONE;
			Config.setWorkspace(null);
			Util.Console.logln("Disconnected.");
		}
	}

	/**
	 * Quit.
	 */
	public static void quit() {
		if (Config.getPlatform().contentEquals("n4") == true) {
			if (status != Status.NONE) {
				store.disconnect();
			}
		}			
		Util.Console.logln("Quit.");
		isQuit = true;
	}
	
	/**
	 * Print Schema.
	 */
	public static void printSchema() {
		if (canExecuteCommand(Status.USE) == false) return;	
		
		Util.Console.logln("=== [BEGIN] PRINT SCHEMA ===");
		Util.Console.log(Schema.getString());
		Util.Console.logln("=== [END] PRINT SCHEMA ===");
	}
	
	/**
	 * Print EGDs.
	 */
	public static void printEgds() {
		if (canExecuteCommand(Status.USE) == false) return;
		
		Util.Console.logln("=== [BEGIN] PRINT EGDs ===");
		for (Egd egd: GraphTransServer.getEgdList()) {
			Util.Console.logln(egd.toString());		
		}
		Util.Console.logln("=== [END] PRINT EGDs ===");
	}
	
	/**
	 * Add graph schema node
	 * @param label
	 */
	public static void addSchemaNode(boolean isLoading, String label) {
		if (canExecuteCommand(Status.USE) == false) return;
		if (isLoading == false) {
			store.addTuple(Config.relname_node_schema, 
					new ArrayList<SimpleTerm>(Arrays.asList(new StringSimpleTerm(label))));  
		}
		Schema.addSchemaNode(label);
		Util.Console.logln("Add graph schema node [" + label + "].");
	}

	/**
	 * Add graph schema edge
	 * @param label
	 * @param from
	 * @param to
	 */
	public static void addSchemaEdge(boolean isLoading, String label, String from, String to) {
		if (canExecuteCommand(Status.USE) == false) return;
		if (isLoading == false) {
			store.addTuple(Config.relname_edge_schema, 
					new ArrayList<SimpleTerm>(Arrays.asList(new StringSimpleTerm(from),
							new StringSimpleTerm(to),
							new StringSimpleTerm(label))));  
		}
		Schema.addSchemaEdge(label, from, to);		
		Util.Console.logln("Add graph schema edge [" + label + " (" + from + " -> " + to + ")].");
	}
	
	/**
	 * Create a graph.
	 * @param graphName
	 */
	public static void createGraph(String graphName) {
		createGraph(graphName, true);
	}
		
	public static void createGraph(String graphName, boolean realExecution) {
		if (canExecuteCommand(Status.CONNECT) == false) return;	

   		Schema.clear();
   		if (realExecution == true) {
   			if (store.createDatabase(graphName) == true) {
				// TODO Auto-generated method stub
				Util.Console.logln("Create graph [" + graphName + "].");
			} else {
				Util.Console.errln("Failed to create graph [" + graphName + "]. It may exist.");
			}
		}
		BaseRuleGen.getPreds().clear();
		BaseRuleGen.addRule();
		ArrayList<Predicate> preds = BaseRuleGen.getPreds();
		if (realExecution == true) {
			for (Predicate p : preds) {
	//			System.out.println("before createShcema: " + p);
				store.createSchema(graphName, p);
			}
		}
	}
	
	/**
	 * Use a graph. Load existing system catalog.
	 * @param graphName
	 */
	public static void useGraph(String graphName) {
		if (canExecuteCommand(Status.CONNECT) == false) return;
		
//		System.out.println("[CommandExecutor] useGraph name: " + graphName);
		
		if (store.useDatabase(graphName) == true) {
			Config.setWorkspace(graphName);		
			status = Status.USE;
			if (Config.isNeo4j() == false) {
//				Catalog.load(store); // FIXME: disable load() temporarily
			}
			Util.resetVarDicEncoding("view");
			Util.resetVarDicEncoding("ruleid");
			Util.Console.logln("Use graph [" + graphName + "].");
		} else {
			Util.Console.errln("Failed to use graph [" + graphName + "]. It may not exist.");
		}	
	}
	
	/*
	 * Type check rules
	 */
	public static boolean checkRuleValidity(String viewName) {
		boolean isValid = true;

		int tc1 = Util.startTimer();
    	long tcInput = 0;
    	long tcOutput = 0;
    	isValid = RuleOverlapCheck.check(viewName);
    	tcInput = Util.getElapsedTime(tc1);
    	Util.Console.logln("[createView] Elapsed time - CheckWellBehaved[TC1]: " + tcInput + " isValid: " + isValid);
    	
    	if (isValid == true) {
			isValid = OutputViewCheck.check(viewName);
			tcOutput = Util.getElapsedTime(tc1);
			Util.Console.logln("[createView] Elapsed time - CheckOutputSchema[TC2]: " + tcOutput + " isValid: " + isValid);
			
//			System.out.println("Pruning? " + Config.isTypeCheckPruningEnabled() + " tcIn: " + tcInput + " tcOut: " + tcOutput);
			if (isValid == false) {
		    	Performance.addTypeCheck(true, false, tcInput, tcOutput); 
    			Util.Console.logln("[ERROR] OUTPUT SCHEMA IS NOT SATISFIED.");
			} else {
				Performance.addTypeCheck(true, true, tcInput, tcOutput); 
			}
    	} else {
    		Performance.addTypeCheck(false, false, tcInput, tcOutput);
    		Util.Console.logln("[ERROR] RULES CONFLICT.");
    	}
    	return isValid;
	}
	
//	public static DatalogProgram getDatalogProgramForView(TransRuleList transRuleList) {
//		return getDatalogProgramForView(transRuleList, true);
//	}
	
	public static void populateDatalogProgramForView(TransRuleList transRuleList) {//, boolean addToProgram) {
    	String viewName = transRuleList.getViewName();
    	DatalogProgram p = GraphTransServer.getProgram();
    	
//    	if (addToProgram == false) {
//    		p = new DatalogProgram();
//    	} else {
//    		p = GraphTransServer.getProgram();
//    	}

		ViewRule.addViewRuleToProgram(p, transRuleList, true, true);
		
		store.createConstructors();
//    	} else {
//    		String viewRuleWithAll = ViewRuleGen.getRule(transRuleList, true);
///    		
//    		DatalogParser parser = new DatalogParser(p);
//    		parser.ParseAndAddRules(viewRuleWithAll);
//    	}
		
		populateIndexSet(p, viewName);
		populateEDBs(p, transRuleList, viewName);

//		return p;
	}		
	
	private static void populateEDBs(DatalogProgram p, TransRuleList transRuleList, String viewName) {
//		p.addEDB(Config.relname_gennewid + "_" + transRuleList.getViewName() + "_" + );
		if (transRuleList.getViewType().contentEquals("materialized") == true) {
			for (int i = 0; i < transRuleList.getTransRuleList().size(); i++) {
				p.addEDB(Config.relname_match + "_"+ viewName + "_" + i);
			}
			p.addEDB(Config.relname_mapping + "_"+ viewName);
			p.addEDB(Config.relname_node + "_" + Config.relname_added + "_" + viewName);
			p.addEDB(Config.relname_edge + "_" + Config.relname_added + "_" + viewName);
			p.addEDB(Config.relname_node + "_" + Config.relname_deleted + "_" + viewName);
			p.addEDB(Config.relname_edge + "_" + Config.relname_deleted + "_" + viewName);
			p.addEDB(Config.relname_node + "_" + viewName);
			p.addEDB(Config.relname_edge + "_" + viewName);
			p.addEDB(Config.relname_edgeprop + "_" + viewName);
			p.addEDB(Config.relname_nodeprop + "_" + viewName);
			// Add N', E'
		} else if (transRuleList.getViewType().contentEquals("hybrid") == true) {
			// Add ND, ED
			for (int i = 0; i < transRuleList.getTransRuleList().size(); i++) {
				p.addEDB(Config.relname_match + "_"+ viewName + "_" + i);
			}
			p.addEDB(Config.relname_mapping + "_" + viewName);
//			p.addEDB(Config.relname_node + "_delta_" + Config.relname_added + "_" + viewName);
//			p.addEDB(Config.relname_edge + "_delta_" + Config.relname_added + "_" + viewName);
//			p.addEDB(Config.relname_node + "_delta_" + Config.relname_deleted + "_" + viewName);
//			p.addEDB(Config.relname_edge + "_delta_" + Config.relname_deleted + "_" + viewName);
		} else if (transRuleList.getViewType().contentEquals("asr") == true) {
			// Add nothing
			for (int i = 0; i < transRuleList.getTransRuleList().size(); i++) {
				p.addEDB(Config.relname_match + "_"+ viewName + "_" + i);
			}
		}		
	}

	private static void populateIndexSet(DatalogProgram p, String viewName) {
		ArrayList<Integer> indexSet;
		
		for (String rel : p.getEDBs()) {
			if (rel.startsWith(Config.relname_match + "_") == true) {
				indexSet = new ArrayList<Integer>();
			}
		}
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_mapping + "_"+ viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(2);
		p.addIndexSet(Config.relname_mapping + "_"+ viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_node + "_" + Config.relname_added + "_" + viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_node + "_" + Config.relname_added + "_" + viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_added + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_added + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(2);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_added + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(3);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_added + "_" + viewName, indexSet);		

		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_node + "_" + Config.relname_deleted + "_" + viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_node + "_" + Config.relname_deleted + "_" + viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_deleted + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_deleted + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(2);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_deleted + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(3);
		p.addIndexSet(Config.relname_edge + "_" + Config.relname_deleted + "_" + viewName, indexSet);
		
		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_node + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_node + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(0);
		p.addIndexSet(Config.relname_edge + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(1);
		p.addIndexSet(Config.relname_edge + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(2);
		p.addIndexSet(Config.relname_edge + "_" + viewName, indexSet);

		indexSet = new ArrayList<Integer>();
		indexSet.add(3);
		p.addIndexSet(Config.relname_edge + "_" + viewName, indexSet);
	}

	/**
	 * Create a view.
	 */
	public static void createView(boolean isLoad, String query, TransRuleList transRuleList) {
		if (canExecuteCommand(Status.USE) == false) return;	

		GraphTransServer.addTransRuleList(transRuleList);
				
		if (isLoad == false && checkRuleValidity(transRuleList.getViewName()) == false) {
	    	return;
		}

		if (Config.isNeo4j() == true) {
			// create index
			for (SchemaNode s : Schema.getSchemaNodes()) {
				((Neo4jStore)store).execute("CREATE POINT INDEX node_index_" + s.getLabel() + " IF NOT EXISTS FOR (n:" + s.getLabel() + ") ON (n.uid);");				
			}
			for (SchemaEdge s : Schema.getSchemaEdges()) {
				((Neo4jStore)store).execute("CREATE POINT INDEX edge_index_" + s.getLabel() + " IF NOT EXISTS FOR ()-[r:" + s.getLabel() + "]-() ON (r.uid);");				
			}
			((Neo4jStore)store).execute("CALL db.awaitIndexes(300)");
		}
		
		int tid = Util.startTimer();

    	String viewName = transRuleList.getViewName();
    	String baseName = transRuleList.getBaseName();
    	String viewType = transRuleList.getViewType();
	
    	long level = 1;
    	if (baseName.contentEquals("g") == false) {
    		level = GraphTransServer.getNumOfTransRuleListList(); // Catalog.loadViewCatalog().get(baseName).getLevel() + 1;
    	}
    	
		populateDatalogProgramForView(transRuleList);
		
		if (Config.isNeo4j() == true) {
			if (viewType.contentEquals("materialized") == true) { // copy and update (CU)
				Config.setUseUpdatedViewNeo4jGraph(true);
				Config.setUseCopyForUpdatedViewNeo4jGraph(true);
			} else if (viewType.contentEquals("hybrid") == true) { // directly update (DU)
				Config.setUseUpdatedViewNeo4jGraph(true);
				Config.setUseCopyForUpdatedViewNeo4jGraph(false);
			} else if (viewType.contentEquals("virtual") == true) { // overlay (OL)					
				Config.setUseUpdatedViewNeo4jGraph(false);
				Config.setUseCopyForUpdatedViewNeo4jGraph(false);
			}
		}
		DatalogProgram p = GraphTransServer.getProgram();
		
//		System.out.println("code 4124 program p : " + p);
		store.createView(p, transRuleList);

		Util.getVarDicEncoding("view", viewName);
		long et = Util.getElapsedTime(tid);
		Util.Console.logln("Create " + viewType.toUpperCase() + " view [" + viewName + "] etime[" + et + "].");
		
		if (Config.getPlatform().equals("sd") == true) {
			SimpleDatalogStore sdStore = (SimpleDatalogStore)store;
			sdStore.debug();
		}
		
//		Performance.setLastViewName(viewName);
		Performance.addBuildViewTime(et);
	}
	
	/**
	 * Insert a tuple (node or edge) into graph
	 */
	public static void insert(String relName, ArrayList<String> args) {
		if (canExecuteCommand(Status.USE) == false) return;	

		int tid = Util.startTimer();
		
		ArrayList<SimpleTerm> newArgs = new ArrayList<SimpleTerm>();
		int num = args.size();
		int lastIndexToBeWiden = 0;
		if (relName.contentEquals("N")) {
			lastIndexToBeWiden = 0;
		} else if (relName.contentEquals("E")) {
			lastIndexToBeWiden = 2;
		} else { // property relations
			lastIndexToBeWiden = 0;
		}
		
		for (int i = 0; i < args.size(); i++) {
			if (i <= lastIndexToBeWiden) { 
				newArgs.add(new LongSimpleTerm(Long.parseLong(args.get(i))));
			} else {
				newArgs.add(new StringSimpleTerm(Util.removeQuotes(args.get(i))));
			}
		}
		
		store.addTuple(relName + "_g", newArgs);
		long et = Util.getElapsedTime(tid);
//		System.out.println("[CommandExecutor] insert et[" + et + "]"); 
		Performance.addUpdateTime(et);
	}
	
	/**
	 * Import data from CSV file.
	 */
	public static void importFromCSV(String relName, String filePath) {
		if (canExecuteCommand(Status.USE) == false) return;	

		int tid = Util.startTimer();
		long numRows = store.importFromCSV(relName, filePath);

		if (numRows > 0) {
			Util.Console.logln("Import " + numRows + " row(s) from CSV file [" + filePath + "]. Time: " + Util.getElapsedTime(tid));
		} else {
			Util.Console.logln("Import from CSV file [" + filePath + "]. Time: " + Util.getElapsedTime(tid));
		}
	}

	/**
	 * List views.
	 */
	public static void printViews() {
		if (canExecuteCommand(Status.USE) == false) return;	

		// TODO Auto-generated method stub
		Util.Console.logln("=== [BEGIN] PRINT VIEWS ===");
		for (int i = 0; i < GraphTransServer.getNumTransRuleList(); i++) {
			Util.Console.logln("i: " + i + " => " +  GraphTransServer.getTransRuleList(i).toString());
		}
		Util.Console.logln("=== [END] PRINT VIEWS ===");
	}
	
	/**
	 * Add EGD to catalog.
	 */
	public static void addEgd(String egd) {
		if (canExecuteCommand(Status.USE) == false) return;	

		// TODO: insert into DB
		String egdSlashed = Util.addSlashes(egd);
		store.addTuple(Config.relname_egd, new ArrayList<SimpleTerm>(Arrays.asList(new StringSimpleTerm(egdSlashed))));
		GraphTransServer.getEgdList().add(EgdParser.Parse(egd));
		
		Util.Console.logln("Add a graph constraint (EGD).");
	}

	/**
	 * Print datalog program of the current graph
	 */
	public static void printProgram() {
		if (canExecuteCommand(Status.USE) == false) return;	

		DatalogProgram p = GraphTransServer.getProgram();
	
		Util.Console.logln("=== [BEGIN] PRINT PROGRAM ===");
		Util.Console.log(p.toString());
		Util.Console.logln("=== [END] PRINT PROGRAM ===");
		
		if (Config.isSimpleDatalog() == true) {
//			System.out.println("[SimpleDatalog debug]");
//			store.debug();;
		}
	}
	
	public static void outputStoreResultSet(StoreResultSet rs) {
		ArrayList<Tuple<SimpleTerm>> result = rs.getResultSet();
		ArrayList<Integer> types = new ArrayList<Integer>();
		int numRows = result.size();
		int numCols = rs.getColumns().size();
		
		if (Config.isAnswerEnabled() == true) {
			if (numRows > 0) {
				for (int i = 0; i < numCols; i++) {
					Util.Console.log(rs.getColumns().get(i) + "\t");
				}
				Util.Console.log("\n");
			}
			Util.Console.log("===============================\n");
			if (numRows > 0) {
				SimpleTerm st;
				for (int i = 0; i < numCols; i++) {
					st = result.get(0).getTuple().get(i);
					if ((st instanceof IntegerSimpleTerm) == true) {
						types.add(0);
					} else if ((st instanceof LongSimpleTerm) == true) {
						types.add(1);
					} else if ((st instanceof StringSimpleTerm) == true) {
						types.add(2);
					}
				}
			}
			for (int i = 0; i < numRows; i++) {
				for (int j = 0; j < types.size(); j++) {
					if (types.get(j) == 0) {
						Util.Console.log(Integer.toString(result.get(i).getTuple().get(j).getInt())+"\t");
					} else if (types.get(j) == 1) {
						Util.Console.log(Long.toString(result.get(i).getTuple().get(j).getLong())+"\t");
					} else if (types.get(j) == 2) {
						Util.Console.log(result.get(i).getTuple().get(j).getString()+"\t");
					}
				}
				Util.Console.log("\n");
			}
			Util.Console.log("===============================\n");
		}
		Util.Console.logln("(" + numRows + " row(s) " + numCols + " col(s))");		
	}
	
	public static void outputAnswer(Relation ans) {
		List<Column> columns = ans.getColumnList();
		int numRows = 0;
		int numCols = ans.getColumnCount();
		
		Util.Console.logln("=== [BEGIN] QUERY ANSWER ===");
//		System.out.println("ans: " + ans);
		
		if (columns.size() > 0) {
			if (columns.get(0).hasStringColumn() == true) {
				numRows = columns.get(0).getStringColumn().getValuesCount(); 
			} else {
				numRows = columns.get(0).getInt64Column().getValuesCount();
			}
		}
		if (Config.isAnswerEnabled() == true) {			
			for (int i = 0; i < numRows; i++) {
				for (int j = 0; j < numCols; j++) {
					if (columns.get(j).hasStringColumn() == true) {
						String valueS = ans.getColumn(j).getStringColumn().getValues(i);
						Util.Console.log(valueS + " ");	
					} else {
						long valueI = ans.getColumn(j).getInt64Column().getValues(i);
						Util.Console.log(valueI + " ");
					}		
				}
				Util.Console.log("\n");
			}
		}
		Util.Console.logln("(" + numRows + " row(s) " + numCols + " col(s))");		
//		System.out.println("(" + numRows + " row(s) " + numCols + " col(s))");
	}
	
	/**
	 * Run commands from a script file.
	 */
	public static void loadScript(String filePath) {
		console.setEnabled(false);
		readCommand(filePath);
		console.setEnabled(true);
	}

	public static void setConsole(Console c) {
		console = c;
	}

	/**
	 * Drop a graph.
	 */
	public static void dropGraph(String graphName) {
		if (canExecuteCommand(Status.CONNECT) == false) return;	
		

//		try {
//			Thread.sleep(120000);
//		} catch (InterruptedException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		
		
		if (store.deleteDatabase(graphName) == true) {
			Util.Console.logln("Drop graph [" + graphName + "].");
			if (Config.getWorkspace() != null && Config.getWorkspace().contentEquals(graphName) == true) {
				Config.setWorkspace(null);
			}
		} else {
			Util.Console.errln("Failed to drop [" + graphName + "]. Possibly there exists a connection to it.");
		}
	}
	
	private static boolean canExecuteCommand(Status requiredStatus) {
		ArrayList<Integer> levels = new ArrayList<Integer>();
		for (int i = 0; i < 2; i++) {
			levels.add(-1);
			Status s = (i == 0) ? requiredStatus : status;	
			switch(s) {
			case NONE:
				levels.set(i, 0);
				break;
			case CONNECT:
				levels.set(i, 1);
				break;
			case USE:
				levels.set(i, 2);
				break;		
			}
		}
		if (levels.get(0) <= levels.get(1)) {
			return true;
		} else {
			if (requiredStatus == Status.NONE) { 		
				Util.Console.logln("ALREADY HAS CONNECTED.");
			} else if (status == Status.NONE) {
				Util.Console.logln("NO CONNECTION.");
			} else {
				Util.Console.logln("NO GRAPH IN USE.");
			}
		}
		return false;
	}
	
	public static void prepareDatabase(String dirPath, String platform) {
		// TODO Auto-generated method stub
   		Schema.clear();

   		Performance.setGraph(dirPath);
   		
		Util.Console.logln("[Executor] prepareDatabase dirPath: " + dirPath + " platform: " + platform);
		int tid = Util.startTimer();
		
		if (platform.contentEquals("n4") == true) {
			if (dirPath.equals("ivm") == true || dirPath.equals("null") == true) {
				Neo4jServerThread.prepareDatabase("data");
			} else {
//				Neo4jServerThread.loadDatabase(dirPath + "/neo4j.db");
				String filePath = "experiment/dataset/snapshots/neo4j/"+ dirPath + "/neo4j.db";
				Neo4jServerThread.loadDatabase(filePath);
			}
		} else if (platform.contentEquals("pg") == true) {
			System.out.println("[CommandExecutor] prepareDatabase PG");
			String filePath = "experiment/dataset/snapshots/postgres/"+ dirPath + ".sql";
			Postgres.loadDBFromSql("exp", filePath);
		} else if (platform.contentEquals("lb") == true) {
			System.out.println("[CommandExecutor] prepareDatabase LB");
			String filePath = "experiment/dataset/snapshots/logicblox/"+ dirPath;
			LogicBlox.loadDBFromBackup("exp", filePath);
		} else if (platform.contentEquals("duck") == true) {
			System.out.println("[CommandExecutor] prepareDatabase DuckDB");
			// DuckDB snapshots are pre-built .duckdb files. Copy it into the
			// working database directory so the store opens a fresh copy.
			String snapshotPath = "experiment/dataset/snapshots/duckdb/" + dirPath + ".duckdb";
			String destPath = Config.get("duckdb.dbdir") != null
					? Config.get("duckdb.dbdir").trim() + java.io.File.separator + "exp.duckdb"
					: "duckdbdata" + java.io.File.separator + "exp.duckdb";
			try {
				java.nio.file.Files.createDirectories(
						java.nio.file.Paths.get(destPath).getParent());
				java.nio.file.Files.copy(
						java.nio.file.Paths.get(snapshotPath),
						java.nio.file.Paths.get(destPath),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				store.useDatabase("exp");
			} catch (java.io.IOException e) {
				Util.Console.errln("[DuckDB] Could not load snapshot [" + snapshotPath + "]: " + e.getMessage());
			}
		}

		long et = Util.getElapsedTime(tid);
		Performance.setT_loading(et);

		Util.Console.logln("Prepare graph from [" + dirPath + "] on platform " + platform + " etime[" + et + "]");
	}

	public static void listGraphs() {
		// TODO Auto-generated method stub
		Util.Console.logln("List of graphs: " + store.listDatabases());
	}

	/*
	 * Create Indexes (SSR)
	 */
	public static void createIndex(IndexType type, String viewName) {
		if (canExecuteCommand(Status.USE) == false) return;
		int tid = Util.startTimer();
		
		List<DatalogClause> rulesToExecute = null;
		List<DatalogClause> rulesForRewriting = null;

		TransRuleList tr = GraphTransServer.getTransRuleList(viewName);
		if (tr == null) {
			throw new IllegalArgumentException("[createIndex] viewName[" + viewName + "] does not exist. type[" + type + "]");
		}
		tr.setIndexType(type);		

		// Generate the Datalog rules to create indexes
		SSR.populateSSRRulesForAll(tr);
		rulesToExecute = SSR.getRulesForCreation();

		// Generate and store the Datalog rules for rewriting
		rulesForRewriting = SSR.getRulesForRewriting();

		System.out.println("[CommandExecutor] rulesToExecute: " + rulesToExecute); //Util.arrayToString(rulesToExecute));
		System.out.println("[CommandExecutor] rulesForRewriting: " + rulesForRewriting); // Util.arrayToString(rulesForRewriting));

		for (int i = 0;i < rulesForRewriting.size(); i++) {
			DatalogClause q = rulesForRewriting.get(i); //.ParseQuery(rulesForRewriting.get(i));
			tr.addIndexRuleList(q);
			GraphTransServer.getProgram().addEDB(q.getHead().getPredicate().getRelName());
		}
		for (int i = 0; i < rulesToExecute.size(); i++) {
			DatalogClause q = rulesToExecute.get(i); //.ParseQuery(rulesForRewriting.get(i));
//			System.out.println("[CommandExecutor] rulesToExecute i[" + i + "]: " + q); //Util.arrayToString(rulesToExecute));
			
			GraphTransServer.getProgram().addEDB(q.getHead().getPredicate().getRelName());
		}

//		for (int i = 0; i < rulesForRewriting.size(); i++) {
//			DatalogClause q = rulesForRewriting.get(i); //.ParseQuery(rulesForRewriting.get(i));
//			System.out.println("[CommandExecutor] rulesForRewriting i[" + i + "]: " + q); //Util.arrayToString(rulesToExecute));
//		}
		
		// Execute the Datalog rules to create indexes.
		// createConstructors() sets up the GENNEWID infrastructure (skolem IDs);
		// call it for every SQL-backed store, not just LogicBlox.
		int tid2= Util.startTimer();
		store.createConstructors();
		store.createView(null, rulesToExecute, true);
		
		long et2 = Util.getElapsedTime(tid2);
		long et = Util.getElapsedTime(tid);
		Performance.addBuildIndexTime(et);
//		Performance.addBuildViewTime(et);
		Util.Console.logln("Create " + type +" on [" + viewName +"] etime: " + et + " etime2: " + et2);		
	}
	
	/**
	 * Answering Query
	 */
	public static void query(String query) {
		if (canExecuteCommand(Status.USE) == false) return;	
		int tid = Util.startTimer();
		StoreResultSet rs = null;
		
		int numberOfRules = 0;
//		System.out.println("[code 340987] query: " + query);
		
		if (Config.isNeo4j() == true) { // for testing purpose only
			rs = queryInNeo4j(query);			
		} else {
			DatalogClause rewriting = getQueryRewriting(query);		
			DatalogClause rewritingConstantFreeAtoms = getQueryRewritingConstantFreeAtoms(rewriting);
			DatalogProgram rewrittenProgram = getUnfoldedProgram(rewritingConstantFreeAtoms);
			
			boolean useMST = false;
			
			if (useMST == true) {			
				int tid2 = Util.startTimer();
				DatalogProgram mstProgram = MagicSetRewriter.rewrite(GraphTransServer.getProgram(), rewritingConstantFreeAtoms);
				System.out.println("mstProgram: " + mstProgram);
				rs = executeQueryProgram(mstProgram);
				long et2 = Util.getElapsedTime(tid2);
				if (rs != null) {
					System.out.println("MST Query result time[" + et2 + "] rs: " + rs);
				}
				
//				System.out.println("5235 rewriting: " + rewriting);
//				System.out.println("5325 rewritingConstantFreeAtoms: " + rewritingConstantFreeAtoms);
				printProgram();
//				System.out.println("1432 rewrittenProgram: " + rewrittenProgram);
			} else {
				numberOfRules = rewrittenProgram.getRuleCount();
				if (rewrittenProgram.getRuleSize() > 0) {
	//				Util.Console.logln("rewrittenProgram rules: " + rewrittenProgram.getRuleSize() + " count: " + rewrittenProgram.getRuleCount());
					
//					System.out.println("[query:24341] rewrittenProgram rule#: " + rewrittenProgram.getRuleCount());
	
					
					if (rewrittenProgram.getRuleCount() > 10000) {
						throw new IllegalArgumentException("[WARNING!!!] # of rules is too many, so stop here. #: " + rewrittenProgram.getRuleCount());
					} else {
						rs = executeQueryProgram(rewrittenProgram);
					}
				}
			}
//			rs = queryInSimpleDatalog(query);
//		} else if (Config.isLogicBlox() == true) {
//			rs = queryInLogicBlox(query);
//		} else if (Config.isPostgres() == true) {
//			if (Config.isUseQuerySubQueryInPostgres() == true) {
//				rs = queryInPostgresQSQ(query);
//			} else if (Config.isUseQuerySubQueryInPostgres() == false) {
//				queryInPostgres();
//			}
		}
		long et = Util.getElapsedTime(tid);
		if (rs != null) {
			System.out.println("query result #: " + rs.getResultSet().size() + " etime[" + et + "] #ofRules: " + numberOfRules);
		} else {
			System.out.println("query rs is null etime[" + et + "]");
		}
//		store.debug(); 
		
		Performance.addQueryResult(rs.getResultSet().size());
		Performance.addQueryTime(et);
	}
	
//	private static StoreResultSet queryInSimpleDatalog(String query) {
//		StoreResultSet rs = null;
//		
//		DatalogClause rewriting = getQueryRewriting(query);		
//		DatalogClause rewritingConstantFreeAtoms = getQueryRewritingConstantFreeAtoms(rewriting);
//		DatalogProgram rewrittenProgram = getUnfoldedProgram(rewritingConstantFreeAtoms);
//
//		if (rewrittenProgram.getRuleSize() > 0) {
//			Util.Console.logln("rewrittenProgram rules: " + rewrittenProgram.getRuleSize() + " count: " + rewrittenProgram.getRuleCount());
//			
////			System.out.println("rewrittenProgram: " + rewrittenProgram.getRuleCount());
//
//			if (rewrittenProgram.getRuleCount() > 1000) {
//				System.out.println("[WARNING!!!] # of rules is too many, so stop here. #: " + rewrittenProgram.getRuleCount());
//			} else {
//				rs = executeQueryProgram(rewrittenProgram);
//			}
//		}
//		return rs;
//	}
//
//	private static StoreResultSet queryInLogicBlox(String query) {
//		StoreResultSet rs = null;
//		
//		DatalogClause rewriting = getQueryRewriting(query);		
//		DatalogClause rewritingConstantFreeAtoms = getQueryRewritingConstantFreeAtoms(rewriting);
//		DatalogProgram rewrittenProgram = getUnfoldedProgram(rewritingConstantFreeAtoms);
//
//		if (rewrittenProgram.getRuleSize() > 0) {
//			Util.Console.logln("rewrittenProgram rules: " + rewrittenProgram.getRuleSize() + " count: " + rewrittenProgram.getRuleCount());
//			
////			System.out.println("rewrittenProgram: " + rewrittenProgram);
//			
////			if (rewrittenProgram.getRuleCount() > 100) {
////				System.out.println("[WARNING!!!] # of rules is too many, so stop here. #: " + rewrittenProgram.getRuleCount() + " query: " + query);
////			} else {
//				rs = executeQueryProgram(rewrittenProgram);
////			}
//		}
//		return rs;		
//	}

//	private static void queryInPostgresQSQ(String query) {
//		StoreResultSet rs = null;
//		
//		DatalogClause rewriting = getQueryRewriting(query);		
//		DatalogClause rewritingConstantFreeAtoms = getQueryRewritingConstantFreeAtoms(rewriting);
//		DatalogProgram rewrittenProgram = getUnfoldedProgram(rewritingConstantFreeAtoms);
//		
//		if (rewrittenProgram.getRuleSize() > 0) {
//			Util.Console.logln("rewrittenProgram rules: " + rewrittenProgram.getRuleSize() + " count: " + rewrittenProgram.getRuleCount());
//
//			executeProgramForQueryInPostgres(rewriting, rewrittenProgram);
//
////			System.out.println("rewrittenProgram: " + rewrittenProgram);
//			
////			if (rewrittenProgram.getRuleCount() > 100) {
////				System.out.println("[WARNING!!!] # of rules is too many, so stop here. #: " + rewrittenProgram.getRuleCount() + " query: " + query);
////			} else {
//				rs = executeQueryProgram(rewrittenProgram);
////			}
//		}
//		return rs;
//	}

//	private static void queryInPostgres() {
////		executeProgramForQueryInPostgres(rewriting, rewrittenProgram);
//	}

	private static StoreResultSet queryInNeo4j(String query) {
//		((Neo4jStore)store).debug();
		StoreResultSet rs = ((Neo4jStore)store).getQueryResult(query);
//		System.out.println("[queryInNeo4j] query: " + query);
//		throw new NotImplementedException("Querying Neo4j");
		return rs;
	}
	
	private static String from = null;
	
	public static String getFrom() {
		return from;
	}
	
	private static DatalogClause getQueryRewriting(String query) {
		int tid = Util.startTimer();
		
		QueryParser parser = new QueryParser();
		DatalogClause q = parser.Parse(query);
		from = parser.getFrom();
		
		if (from.equals("g") == true) {
			return q;
		} else {
			TransRuleList tr = GraphTransServer.getTransRuleList(from);
	
//			System.out.println("CODE 14512 from: " + from + " tr: " + tr);
			
			DatalogClause newQuery = q;
			if (tr.getIndexType() == IndexType.SSR) { 
				newQuery = QueryRewriterSubstitution.rewrite(q, tr.getIndexRuleList());
			}
	//		Util.Console.logln("Query: " + query);
			System.out.println("newQuery " + ((tr.getIndexType() == IndexType.SSR) ? "(with available SSR)" : "")
					+ ": " + newQuery);
			
			Util.Console.logln("[Timing] Query rewriting: " + Util.getElapsedTime(tid));		

			return newQuery;
		}
	}
	
	private static DatalogClause getQueryRewritingConstantFreeAtoms(DatalogClause newQuery) {
		DatalogClause newQuery2 = new DatalogClause();
		newQuery2.addAtomToHeads(newQuery.getHead());
		for (int i = 0; i < newQuery.getBody().size(); i++) {
			Atom a = newQuery.getBody().get(i);
			newQuery2.getBody().addAll(a.getAtomBodyStrWithInterpretedAtoms(""));
		}
		return newQuery2;
	}

	private static DatalogProgram getUnfoldedProgram(DatalogClause newQuery) {
		DatalogProgram rewrittenProgram = null;
		if (Config.isLogicBlox() == true || Config.isSimpleDatalog() == true
				|| Config.isUseQuerySubQueryInPostgres() == true) {
			DatalogProgram p = GraphTransServer.getProgram();
			rewrittenProgram = Rewriter.getProgramForRewrittenQuery(p, newQuery);
		} else if ((Config.isPostgres() == true || Config.isDuckDB() == true)
				&& Config.isUseQuerySubQueryInPostgres() == false) {
			// For relational SQL backends: pass the single query clause through directly.
			rewrittenProgram = new DatalogProgram();
			rewrittenProgram.addRule(newQuery);
		}
		return rewrittenProgram;
	}

//	private static void executeProgramForQueryInPostgres(DatalogClause newQuery, DatalogProgram rewrittenProgram) {
//		int qtid = Util.startTimer();			
//
//		if (Config.isUseQuerySubQueryInPostgres() == true) {
//			Util.Console.logln("rewrittenProgram rules: " + rewrittenProgram.getRuleSize() + " count: " + rewrittenProgram.getRuleCount());
//			
//			for (int i = 0; i < rewrittenProgram.getHeadRules().size(); i++) {
//				String name = rewrittenProgram.getHeadRules().get(i);
//				List<DatalogClause> cs = rewrittenProgram.getRules(name);
//				
//				System.out.println("Will create view [" + name + "] with cs: " +cs);
//				store.createView(name, cs, true);
//			}
//		}
//		
//		StoreResultSet result = store.getQueryResult(newQuery);
//		outputStoreResultSet(result);
//		
//		Util.Console.logln("[Timing] Query execution time: " + Util.getElapsedTime(qtid));
//	}
//
	private static StoreResultSet executeQueryProgram(DatalogProgram p) {
//		System.out.println("rewrittenProgram: " + p);
		int qtid = Util.startTimer();

		// Execute query in LogicBlox
//		String queryBlockName = DatalogQueryProcessor.runQuery(store, rewrittenProgram); // execute query to retrieve answers
		
		ArrayList<DatalogClause> cs = new ArrayList<DatalogClause>();
		for (int i = 0; i < p.getHeadRules().size(); i++) {
			String name = p.getHeadRules().get(i);
			cs.addAll(p.getRules(name));
			
//			System.out.println("Will create view [" + name + "] with cs: " +cs);
		}
		StoreResultSet rs = store.getQueryResult(cs);
//		StoreResultSet rs = DatalogQueryProcessor.runQuery(store, rewrittenProgram); // execute query to retrieve answers
//		System.out.println("[code 490] rs: " + rs.getResultSet());
		
//		if (Config.isLogicBlox() == true) {
////
////			// Print answers (or not)
////			if (Config.isAnswerEnabled() == true) {
////				
////				Atom head = rewrittenProgram.getRules(Config.relname_query).get(0).getHead();
////				Relation ans = DatalogQueryProcessor.getQueryAnswer(head);
////				outputAnswer(ans);
////
//////						Relation ans = DatalogQueryProcessor.getQueryAnswer(Config.nodeAtom);
//////						outputAnswer(ans);
//////						
//////						ans = DatalogQueryProcessor.getQueryAnswer(Config.edgeAtom);
//////						outputAnswer(ans);
////			} else { // print head only
////				Atom head = rewrittenProgram.getRules(Config.relname_query).get(0).getHead();
////				Relation ans = DatalogQueryProcessor.getQueryAnswer(head);
////				outputAnswer(ans);
////			}
////			Util.Console.logln("[Timing] Query execution time: " + Util.getElapsedTime(qtid));
////			LogicBlox.runRemoveBlock(Config.getWorkspace(), queryBlockName);
//		} else {
////			StoreResultSet result = store.runQuery(rewrittenProgram.r);
////			.getQueryResult(newQuery);
////			outputStoreResultSet(result);
//		}
		
		return rs;
	}
//
//	private static void getEhandleEmptyRewrittenProgramForQuery() {
//		if (Config.isSubQueryPruningEnabled() == true) {
//			// if SubQueryPruning is enabled, it is already executed
//			Util.Console.logln("=====");
//			Util.Console.logln("Answer is empty");
//			Util.Console.logln("=====");
//		} else {
//			throw new IllegalArgumentException("rewrittenProgram should not be null");
//		}
//	}
	
}
