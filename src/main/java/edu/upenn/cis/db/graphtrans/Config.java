package edu.upenn.cis.db.graphtrans;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.ini4j.Profile.Section;
import org.ini4j.Wini;

import edu.upenn.cis.db.ConjunctiveQuery.Atom;
import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.ConjunctiveQuery.Term;
import edu.upenn.cis.db.ConjunctiveQuery.Type;
import edu.upenn.cis.db.helper.Util;

/**
 * Config.
 * @author sbnet21
 *
 */
public class Config {
	// base names
	public enum IndexType {
		NONE,
		ASR,
		SSR
	}
	
	public final static String lb_workspace_temp = "__temp";
	
	public final static String relname_node = "N";
	public final static String relname_edge = "E";
	public final static String relname_nodeprop = "NP";
	public final static String relname_edgeprop = "EP";
	public final static String relname_match = "MATCH";
	public final static String relname_mapping = "MAP";
	public final static String relname_default_mapping = "DMAP";
	public final static String relname_added = "ADD";
	public final static String relname_deleted = "DEL";
	public final static String relname_index = "IDX";
	public final static String relname_sindex = "SIDX";
	public final static String relname_gennewid = "GENNEWID";
	
	public final static String relname_base_postfix = "_g";
	public final static String relname_catalog_view = "CATALOG_VIEW";
	public final static String relname_catalog_index = "CATALOG_INDEX";
	public final static String relname_catalog_sindex = "CATALOG_SINDEX";
	
	private final static String relname_schema_postfix = "_schema";
	public final static String relname_add_postfix = "_add";
	
	public final static String neo4jdata_basepath = "neo4jdata/"; // used for import
	public final static String neo4jdata_basepath_embedded = neo4jdata_basepath + "databases/"; // used for embedded server
	
	// use to query to schema relation
	public final static String relname_node_schema = relname_node + relname_schema_postfix;
	public final static String relname_edge_schema = relname_edge + relname_schema_postfix;
	public final static String relname_nodeprop_schema = relname_nodeprop + relname_schema_postfix;
	public final static String relname_edgeprop_schema = relname_edgeprop + relname_schema_postfix;
	
	// use to add a tuple to schema relation
//	public final static String relname_node_schema_add = relname_node_schema + relname_add_postfix;
//	public final static String relname_edge_schema_add = relname_edge_schema + relname_add_postfix;
//	public final static String relname_nodeprop_schema_add = relname_nodeprop_schema + relname_add_postfix;
//	public final static String relname_edgeprop_schema_add = relname_edgeprop_schema + relname_add_postfix;
	
	public final static String relname_egd = "EGD";
	
	public final static String relname_coloring = "COLOR";
	public final static String relname_rule_egds = "RULE_EGDS";
	public final static String relname_rule_pair_candidate = "RULE_PAIR_CANDIDATE";
	
	public final static String relname_query = "_"; 
		
	private static boolean typeCheckEnabled = true;
	private static boolean typeCheckPruningEnabled = true;
	private static boolean subQueryPruningEnabled = false;
	private static boolean useQuerySubQueryInPostgres = false;
	private static boolean postgresEnabled = false;
	private static boolean answerEnabled = false;
	private static String platform = "lb";
	private static boolean useUpdatedViewNeo4jGraph = true;
	private static boolean useCopyForUpdatedViewNeo4jGraph = false;
	private static boolean useTypeCheckSimplePruner = true;
	private static boolean useMatchMaterialzation = true;
	private static boolean useMatchSSIndex = false;
	private static boolean useIVM = false;
	private static boolean useSimpleDatalogEngine = true;
	
	public static boolean useSimpleDatalogEngine() {
		return useSimpleDatalogEngine;
	}

	public static void setUseSimpleDatalogEngine(boolean useSimpleDatalogEngine) {
		Config.useSimpleDatalogEngine = useSimpleDatalogEngine;
	}

	public static boolean isUseMatchMaterialzation() {
		return useMatchMaterialzation;
	}

	public static void setUseMatchMaterialzation(boolean useMatchMaterialzation) {
		Config.useMatchMaterialzation = useMatchMaterialzation;
	}

	public static boolean isUseMatchSSIndex() {
		return useMatchSSIndex;
	}

	public static void setUseMatchSSIndex(boolean useMatchSSIndex) {
		Config.useMatchSSIndex = useMatchSSIndex;
	}

	
	private static HashMap<String, String> config = new HashMap<String, String>();
	private static String configFile;

	public static String get(String key) {
		return config.get(key);
	}

	public static boolean isUseCopyForUpdatedViewNeo4jGraph() {
		return useCopyForUpdatedViewNeo4jGraph;
	}

	public static void setUseCopyForUpdatedViewNeo4jGraph(boolean useCopyForUpdatedViewNeo4jGraph) {
		Config.useCopyForUpdatedViewNeo4jGraph = useCopyForUpdatedViewNeo4jGraph;
	}

	public static Predicate predN;
	public static Predicate predE;
	public static Predicate predNP;
	public static Predicate predEP;
	public static Predicate predOpEq;
	public static Predicate predOpGt;
	public static Predicate predOpLt;
	public static Predicate predOpGe;
	public static Predicate predOpLe;
	public static Predicate predOpNeq;
	// wide
//	public static Predicate predN_w; 
//	public static Predicate predE_w;
//	public static Predicate predNP_w;
//	public static Predicate predEP_w;
	
	public static Atom nodeAtom;
	public static Atom edgeAtom;
	
	private static String workspace = null;
	
	public static boolean isSimpleDatalog() {
		return platform.contentEquals("sd");
	}
	
	public static boolean isNeo4j() {
		return platform.contentEquals("n4");
	}
	
	public static boolean isPostgres() {
		return platform.contentEquals("pg");
	}
	
	public static boolean isLogicBlox() {
		return platform.contentEquals("lb");
	}

	public static boolean isDuckDB() {
		return platform.contentEquals("duck");
	}
	
	public static boolean isAnswerEnabled() {
		return answerEnabled;
	}
	
	public static void setAnswerEnabled(boolean flag) {
		Config.answerEnabled = flag;
	}	
	
	public static boolean isPostgresEnabled() {
		return postgresEnabled;
	}
	public static void setPostgresEnabled(boolean flag) {
		Config.postgresEnabled = flag;
	}
	
	public static String getConfigFile() {
		return configFile;		
	}

	public static void load(String filename) throws IOException {
		configFile = filename;
		File iniFile = new File(filename);
		if (iniFile.exists() == true) {
			Wini ini = new Wini(new File(filename));
			for (String key : ini.keySet()) {
				for (Map.Entry<String,String> entry : ini.get(key).entrySet()) {
					String newkey = key + "." + entry.getKey();
					config.put(newkey, entry.getValue());				
				}
			}
			System.out.println("config: " + config);
		} else {
			Util.Console.errln("Configuratio file [" + filename + "] doesn't exist.");
			System.exit(0);
		}
	}
	
	public static void initialize() {
		// Initialize Predicates
		predN = new Predicate(relname_node);
		predN.addArg("id", Type.Integer);
		predN.addArg("label", Type.String);

		predE = new Predicate(relname_edge);
		predE.addArg("id", Type.Integer);
		predE.addArg("from", Type.Integer);
		predE.addArg("to", Type.Integer);
		predE.addArg("label", Type.String);

		predNP = new Predicate(relname_nodeprop);
		predNP.addArg("id", Type.Integer);
		predNP.addArg("property", Type.String);
		predNP.addArg("value", Type.String);
		
		predEP = new Predicate(relname_edgeprop);
		predEP.addArg("id", Type.Integer);
		predEP.addArg("property", Type.String);
		predEP.addArg("value", Type.String);

		// Currently, we assume every value (but ids) is String 
		predOpEq = new Predicate("=", true);
		predOpEq.addArg("lop", Type.String);
		predOpEq.addArg("rop", Type.String);
		
		predOpGt = new Predicate(">", true);
		predOpGt.addArg("lop", Type.String);
		predOpGt.addArg("rop", Type.String);
		
		predOpLt = new Predicate("<", true);
		predOpLt.addArg("lop", Type.String);
		predOpLt.addArg("rop", Type.String);

		predOpGe = new Predicate(">=", true);
		predOpGe.addArg("lop", Type.String);
		predOpGe.addArg("rop", Type.String);
		
		predOpLe = new Predicate("<=", true);
		predOpLe.addArg("lop", Type.String);
		predOpLe.addArg("rop", Type.String);
		
		predOpNeq = new Predicate("!=", true);
		predOpNeq.addArg("lop", Type.String);
		predOpNeq.addArg("rop", Type.String);
		
		// Wide
//		predN_w = new Predicate(relname_node);
//		predN_w.addArg("id", Type.Integer);
//		predN_w.addArg("id_l", Type.Integer);
//		predN_w.addArg("id_r", Type.Integer);
//		predN_w.addArg("label", Type.String);
//
//		predE_w = new Predicate(relname_edge);
//		predE_w.addArg("id", Type.Integer);
//		predE_w.addArg("id_l", Type.Integer);
//		predE_w.addArg("id_r", Type.Integer);
//		predE_w.addArg("from", Type.Integer);
//		predE_w.addArg("from_l", Type.Integer);
//		predE_w.addArg("from_r", Type.Integer);
//		predE_w.addArg("to", Type.Integer);
//		predE_w.addArg("to_l", Type.Integer);
//		predE_w.addArg("to_r", Type.Integer);
//		predE_w.addArg("label", Type.String);
//
//		predNP_w = new Predicate(relname_nodeprop);
//		predNP_w.addArg("id", Type.Integer);
//		predNP_w.addArg("id_l", Type.Integer);
//		predNP_w.addArg("id_r", Type.Integer);
//		predNP_w.addArg("property", Type.String);
//		predNP_w.addArg("value", Type.String);
//		
//		predEP_w = new Predicate(relname_edgeprop);
//		predEP_w.addArg("id", Type.Integer);
//		predEP_w.addArg("id_l", Type.Integer);
//		predEP_w.addArg("id_r", Type.Integer);
//		predEP_w.addArg("property", Type.String);
//		predEP_w.addArg("value", Type.String);		
		
		nodeAtom = new Atom(new Predicate("ANS_N"));
		nodeAtom.appendTerm(new Term("a", true));
		nodeAtom.appendTerm(new Term("a_label", true));

		edgeAtom = new Atom(new Predicate("ANS_E"));
		edgeAtom.appendTerm(new Term("a", true));
		edgeAtom.appendTerm(new Term("b", true));
		edgeAtom.appendTerm(new Term("c", true));
		edgeAtom.appendTerm(new Term("label", true));
	}

	public static String getWorkspace() {
		return workspace;
	}

	public static void setWorkspace(String workspace) {
		Config.workspace = workspace;
	}
	
	public static boolean isTypeCheckEnabled() {
		return typeCheckEnabled;
	}

	public static void setTypeCheckEnabled(boolean typeCheckEnabled) {
		Config.typeCheckEnabled = typeCheckEnabled;
	}

	public static boolean isTypeCheckPruningEnabled() {
		return typeCheckPruningEnabled;
	}

	public static void setTypeCheckPruningEnabled(boolean typeCheckPruningEnabled) {
		Config.typeCheckPruningEnabled = typeCheckPruningEnabled;
	}

	public static boolean isSubQueryPruningEnabled() {
		return subQueryPruningEnabled;
	}

	public static void setSubQueryPruningEnabled(boolean subQueryPruningEnabled) {
		Config.subQueryPruningEnabled = subQueryPruningEnabled;
	}

	public static boolean isUseQuerySubQueryInPostgres() {
		return useQuerySubQueryInPostgres;
	}

	public static void setUseQuerySubQueryInPostgres(boolean useQuerySubQueryInPostgres) {
		Config.useQuerySubQueryInPostgres = useQuerySubQueryInPostgres;
	}

	public static String getPlatform() {
		return platform;
	}

	public static void setPlatform(String platform) {
		Config.platform = platform;
	}

	public static boolean isUseUpdatedViewNeo4jGraph() {
		return useUpdatedViewNeo4jGraph;
	}

	public static void setUseUpdatedViewNeo4jGraph(boolean useUpdatedViewNeo4jGraph) {
		Config.useUpdatedViewNeo4jGraph = useUpdatedViewNeo4jGraph;
	}

	/**
	 * Rule type (schema, egd, transrule).
	 * @author sbnet21
	 */
	public enum RULE_TYPE {
        SCHEMA(1000), 
        EGD(2000),
		TRANSRULE(3000);
        private int name;
        private RULE_TYPE(int n) {
            this.name = n;
        }
        @Override
        public String toString(){
            return Integer.toString(name);
        }
    }
	
	/**
	 * Pattern type (none, before, affected, after).
	 * @author sbnet21
	 */
	public enum PATTERN_TYPE {
		NONE(0), BEFORE(0), AFFECTED(1), AFTER(2);
        private int type;
        private PATTERN_TYPE(int t) {
            this.type = t;
        }
        @Override
        public String toString(){
            return Integer.toString(type);
        }
        public int getType() {
        	return type;
        }
    }

	public static void setUseTypeCheckSimplePruner(boolean u) {
		useTypeCheckSimplePruner = u;
	}
	
	public static boolean useTypeCheckSimplePruner() {
		// TODO Auto-generated method stub
		return useTypeCheckSimplePruner;
	}

	public static boolean isUseIVM() {
		return useIVM;
	}

	public static void setUseIVM(boolean useIVM) {
		Config.useIVM = useIVM;
	}
}
