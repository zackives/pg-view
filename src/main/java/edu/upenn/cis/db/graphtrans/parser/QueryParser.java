package edu.upenn.cis.db.graphtrans.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.upenn.cis.db.ConjunctiveQuery.Atom;
import edu.upenn.cis.db.ConjunctiveQuery.Predicate;
import edu.upenn.cis.db.ConjunctiveQuery.Term;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.graphtrans.Config;
import edu.upenn.cis.db.graphtrans.GraphQueryParser.GraphTransQueryBaseVisitor;
import edu.upenn.cis.db.graphtrans.GraphQueryParser.GraphTransQueryLexer;
import edu.upenn.cis.db.graphtrans.GraphQueryParser.GraphTransQueryParser;
import edu.upenn.cis.db.graphtrans.GraphQueryParser.GraphTransQueryParser.HopContext;
import edu.upenn.cis.db.graphtrans.GraphQueryParser.GraphTransQueryParser.Term_bodyContext;
import edu.upenn.cis.db.helper.Util;

public class QueryParser extends GraphTransQueryBaseVisitor<Void> {
	final static Logger logger = LogManager.getLogger(QueryParser.class);

//	private DatalogProgram program;
	private String query;
	private String from;
	
	private DatalogClause clause;
	
//	private DatalogProgram program;
	
	private HashMap<String, String> nodeVarToLabelMap;
	private HashMap<String, String> edgeVarToLabelMap;
	
	private HashMap<String, Atom> nodeVarToAtomMap;
	private HashMap<String, Atom> edgeVarToAtomMap;
	
	private HashSet<String> returnNodeSet;
	private HashSet<String> returnEdgeSet;
	
	private HashSet<Atom> returnInterpretedSet;
	
	private HashMap<String, HashMap<String, ArrayList<Atom>>> propertyAtoms; // var |-> (prop |-> predicates)

	public QueryParser() {
		nodeVarToLabelMap = new HashMap<String, String>();
		edgeVarToLabelMap = new HashMap<String, String>();
		nodeVarToAtomMap = new HashMap<String, Atom>();
		edgeVarToAtomMap = new HashMap<String, Atom>();
		returnNodeSet = new HashSet<String>();
		returnEdgeSet = new HashSet<String>();
		returnInterpretedSet = new HashSet<Atom>();
		propertyAtoms = new HashMap<String, HashMap<String, ArrayList<Atom>>>();
//		program = new DatalogProgram();
	}
	
	public String getFrom() {
		return from;
	}

	public DatalogClause Parse(String query) {
		this.query = query;
		
		clause = new DatalogClause();

//		System.out.println("[QueryParser] Parse query: " + query);

		CharStream input = CharStreams.fromString(query);
		GraphTransQueryLexer lexer = new GraphTransQueryLexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		GraphTransQueryParser parser = new GraphTransQueryParser(tokens);
		ParseTree tree = parser.user_query();
		visit(tree);
		
//		System.out.println("[QueryParser] Parse clause: " + clause);

//		System.out.println("[QueryParser] clause: " + clause);
		for (Atom a : clause.getBody()) {
			if (a.isInterpreted() == false) {
				String relName = a.getPredicate().getRelName();
				if (from != null) {
					relName += "_" + from; // + "v";
				}
				a.setPredicate(new Predicate(relName));
			}
		}
		
		
//		System.out.println("program===>");
//		System.out.println(program);
		
		return clause;
	}

	private int nodeCounter = 0;
	private int edgeCounter = 0;
	private int funcCounter = 0;
	
	private void parseNodeProperties(String var, GraphTransQueryParser.Term_bodyContext termCtx) {
		if (termCtx.properties() != null) {
			for (GraphTransQueryParser.Property_pairContext pair : termCtx.properties().property_pair()) {
				String propName = pair.prop().getText();
				String propVal = pair.propValue().getText();
				ParserHelper.processWhereCondition(var, propName, "=", propVal, false, clause.getBody(), propertyAtoms, null, null);
			}
		}
	}
	
	private void parseEdgeProperties(String var, GraphTransQueryParser.Edge_term_bodyContext edgeCtx) {
		if (edgeCtx.properties() != null) {
			for (GraphTransQueryParser.Property_pairContext pair : edgeCtx.properties().property_pair()) {
				String propName = pair.prop().getText();
				String propVal = pair.propValue().getText();
				ParserHelper.processWhereCondition(var, propName, "=", propVal, false, clause.getBody(), propertyAtoms, null, null);
			}
		}
	}

	private String resolveFuncArg(GraphTransQueryParser.FuncArgContext ctx) {
		if (ctx.lop() != null) {
			return resolveLop(ctx.lop());
		} else if (ctx.rop() != null) {
			return resolveRop(ctx.rop());
		} else {
			return ctx.getText();
		}
	}

	private String resolveLop(GraphTransQueryParser.LopContext ctx) {
		if (ctx.funcCall() != null) {
			return parseFuncCall(ctx.funcCall());
		} else {
			String var = ctx.var().getText();
			if (ctx.prop() != null) {
				String prop = ctx.prop().getText();
				if (!propertyAtoms.containsKey(var)) {
					propertyAtoms.put(var, new HashMap<String, ArrayList<Atom>>());
				}
				if (!propertyAtoms.get(var).containsKey(prop)) {
					propertyAtoms.get(var).put(prop, new ArrayList<Atom>());
				}
				return var + "_" + prop + "_val";
			} else {
				return var;
			}
		}
	}

	private String resolveRop(GraphTransQueryParser.RopContext ctx) {
		if (ctx.funcCall() != null) {
			return parseFuncCall(ctx.funcCall());
		} else if (ctx.propValue() != null) {
			return ctx.propValue().getText();
		} else if (ctx.var() != null) {
			String var = ctx.var().getText();
			if (ctx.prop() != null) {
				String prop = ctx.prop().getText();
				if (!propertyAtoms.containsKey(var)) {
					propertyAtoms.put(var, new HashMap<String, ArrayList<Atom>>());
				}
				if (!propertyAtoms.get(var).containsKey(prop)) {
					propertyAtoms.get(var).put(prop, new ArrayList<Atom>());
				}
				return var + "_" + prop + "_val";
			} else {
				return var;
			}
		} else {
			return ctx.getText();
		}
	}

	private String parseFuncCall(GraphTransQueryParser.FuncCallContext ctx) {
		String retVar = "f_" + (funcCounter++);
		StringBuilder fullName = new StringBuilder();
		for (int i = 0; i < ctx.ID().size(); i++) {
			if (i > 0) {
				fullName.append(".");
			}
			fullName.append(ctx.ID(i).getText());
		}
		Atom a = new Atom(new Predicate(fullName.toString()));
		for (GraphTransQueryParser.FuncArgContext argCtx : ctx.funcArg()) {
			String argVal = resolveFuncArg(argCtx);
			boolean isVar = !argVal.startsWith("\"") && !argVal.startsWith("'") && !argVal.matches("-?\\d+(\\.\\d+)?");
			a.appendTerm(new Term(argVal, isVar));
		}
		a.appendTerm(new Term(retVar, true));
		clause.addAtomToBody(a);
		return retVar;
	}

	@Override 
	public Void visitMatch_clause(GraphTransQueryParser.Match_clauseContext ctx) 
	{
		for (int i = 0; i < ctx.hop_or_terms().hop_or_term().size(); i++) {
			if (ctx.hop_or_terms().hop_or_term(i).term() == null) {				
				HopContext hopCtx = ctx.hop_or_terms().hop_or_term(i).hop();
				
				GraphTransQueryParser.Term_bodyContext fromCtx = hopCtx.term(0).term_body();
				GraphTransQueryParser.Term_bodyContext toCtx = hopCtx.term(1).term_body();
				
				// from
				String from = (fromCtx.var() != null) ? fromCtx.var().getText() : "n_" + (nodeCounter++);
				if (fromCtx.label() != null) {
					String label = fromCtx.label().getText();
					nodeVarToLabelMap.put(from, Util.addQuotes(label));
				}
				parseNodeProperties(from, fromCtx);
	
				// to
				String to = (toCtx.var() != null) ? toCtx.var().getText() : "n_" + (nodeCounter++);
				if (toCtx.label() != null) {
					String label = toCtx.label().getText();	
					nodeVarToLabelMap.put(to, Util.addQuotes(label));
				}
				parseNodeProperties(to, toCtx);
	
				// via
				GraphTransQueryParser.Edge_term_bodyContext edgeCtx = hopCtx.edge_term().edge_term_body();
				String var = (edgeCtx.var() != null) ? edgeCtx.var().getText() : "e_" + (edgeCounter++);
				
				if (edgeCtx.SIM_OP() != null) {
					String op = edgeCtx.SIM_OP().getText();
					String threshold = "0.0";
					if (edgeCtx.properties() != null) {
						for (GraphTransQueryParser.Property_pairContext pair : edgeCtx.properties().property_pair()) {
							if (pair.prop().getText().equals("threshold")) {
								threshold = pair.propValue().getText();
							}
						}
					}
					Atom a = new Atom(Config.predSimEdge);
					a.appendTerm(new Term(var, true));
					a.appendTerm(new Term(from, true));
					a.appendTerm(new Term(to, true));
					a.appendTerm(new Term(Util.addQuotes(op), false));
					a.appendTerm(new Term(threshold, false));
					clause.addAtomToBody(a);
				} else {
					String label = (edgeCtx.labelRegEx() != null) ? edgeCtx.labelRegEx().getText() : "";
					
					Atom a = new Atom(Config.predE);
					a.appendTerm(new Term(var, true));
					a.appendTerm(new Term(from, true));
					a.appendTerm(new Term(to, true));
					a.appendTerm(new Term(Util.addQuotes(label), false));
				
					clause.addAtomToBody(a);
					parseEdgeProperties(var, edgeCtx);
				}
			} else { // term
				GraphTransQueryParser.Term_bodyContext termCtx = ctx.hop_or_terms().hop_or_term(i).term().term_body();
				
				String var = (termCtx.var() != null) ? termCtx.var().getText() : "n_" + (nodeCounter++);
				if (termCtx.label() != null) {
					String label = termCtx.label().getText();
					nodeVarToLabelMap.put(var, Util.addQuotes(label));
				} else {
					throw new IllegalArgumentException("Single node[" + var + "] should have a label");	
				}
				parseNodeProperties(var, termCtx);
			}
		}

		// node
		for (HashMap.Entry<String,String> e : nodeVarToLabelMap.entrySet()) {
			Atom a = new Atom(Config.predN);
			String var = e.getKey();
			String label = e.getValue();
			
			a.appendTerm(new Term(var, true));
			a.appendTerm(new Term(label, false));
			
			nodeVarToAtomMap.put(var, a);
			
			clause.addAtomToBody(a);
		}
		
		return visitChildren(ctx); 
	}

	@Override public Void visitFrom_clause(GraphTransQueryParser.From_clauseContext ctx) {
		from = ctx.ID().getText();
//		System.out.println("[adsfsd3432] from: " + from);
		return visitChildren(ctx); 
	}
	
	@Override public Void visitWhere_clause(GraphTransQueryParser.Where_clauseContext ctx) {
		visitChildren(ctx);
		ParserHelper.processWhereClause(nodeVarToAtomMap, clause.getBody(), propertyAtoms, null);

		return null;
	}

	@Override public Void visitWhere_condition(GraphTransQueryParser.Where_conditionContext ctx) {
		String op = ctx.operator().getText();
		
		String lopVal;
		if (ctx.lop().funcCall() != null) {
			lopVal = parseFuncCall(ctx.lop().funcCall());
		} else {
			String var = ctx.lop().var().getText();
			if (ctx.lop().prop() != null) {
				String prop = ctx.lop().prop().getText();
				if (!propertyAtoms.containsKey(var)) {
					propertyAtoms.put(var, new HashMap<String, ArrayList<Atom>>());
				}
				if (!propertyAtoms.get(var).containsKey(prop)) {
					propertyAtoms.get(var).put(prop, new ArrayList<Atom>());
				}
				lopVal = var + "_" + prop + "_val";
			} else {
				lopVal = var;
			}
		}

		String ropVal;
		boolean ropIsVar = false;
		if (ctx.rop().funcCall() != null) {
			ropVal = parseFuncCall(ctx.rop().funcCall());
			ropIsVar = true;
		} else if (ctx.rop().propValue() != null) {
			ropVal = ctx.rop().propValue().getText();
			ropIsVar = false;
		} else {
			String var = ctx.rop().var().getText();
			if (ctx.rop().prop() != null) {
				String prop = ctx.rop().prop().getText();
				if (!propertyAtoms.containsKey(var)) {
					propertyAtoms.put(var, new HashMap<String, ArrayList<Atom>>());
				}
				if (!propertyAtoms.get(var).containsKey(prop)) {
					propertyAtoms.get(var).put(prop, new ArrayList<Atom>());
				}
				ropVal = var + "_" + prop + "_val";
				ropIsVar = true;
			} else {
				ropVal = var;
				ropIsVar = true;
			}
		}

		ParserHelper.processWhereCondition(lopVal, "", op, ropVal, ropIsVar, clause.getBody(), propertyAtoms, null, null);
		return null;
	}
	
	@Override public Void visitReturn_clause(GraphTransQueryParser.Return_clauseContext ctx) {
		for (int i = 0; i < ctx.hop_or_terms().hop_or_term().size(); i++) {
			if (ctx.hop_or_terms().hop_or_term(i).term() == null) {				
				HopContext hopCtx = ctx.hop_or_terms().hop_or_term(i).hop();
				String from = hopCtx.term(0).term_body().var().getText();
				String to = hopCtx.term(2).term_body().var().getText();
				String var = hopCtx.term(1).term_body().var().getText();
				
				returnNodeSet.add(from);
				returnNodeSet.add(to);
				returnEdgeSet.add(var);
			} else { // term
				Term_bodyContext termCtx = ctx.hop_or_terms().hop_or_term(i).term().term_body();
				
				String var = termCtx.var().getText();
				returnNodeSet.add(var);
			}
		}
		
//		System.out.println("@@@returnNodeSet: " + returnNodeSet);
//		System.out.println("@@@returnEdgeSet: " + returnEdgeSet);
//		
//		System.out.println("@@@nodeVarToAtomMap: " + nodeVarToAtomMap);
		
//		System.out.println("returnNodeSet: " + returnNodeSet);
//		System.out.println("returnEdgeSet: " + returnEdgeSet);

		Atom h = new Atom(new Predicate(Config.relname_query));
		for (String a : returnNodeSet) {
			h.appendTerm(new Term(a, true));
//			h.appendTerm(new Term(a + "_l", true));
//			h.appendTerm(new Term(a + "_r", true));
//			h.appendTerm(new Term(nodeVarToLabelMap.get(a), false));
		}
		for (String a : returnEdgeSet) {
			h.appendTerm(new Term(a, true));
//			h.appendTerm(new Term(a + "_l", true));
//			h.appendTerm(new Term(a + "_r", true));
//			h.appendTerm(new Term(edgeVarToLabelMap.get(a), false));
		}		
		clause.addAtomToHeads(h);

//		try {
//			for (String a : returnNodeSet) {
//				Atom b = nodeVarToAtomMap.get(a);
//				Atom h = (Atom)b.clone();
//				String relName = "ANS_N";
//				h.setPredicate(new Predicate(relName));
//				clause.addAtomToHeads(h);
//				
////				program.addRule(clause);
////				System.out.println("h1: " + h);
//			}
//			for (String a : returnEdgeSet) {
//				Atom b = edgeVarToAtomMap.get(a);
//				Atom h = (Atom)b.clone();
//				String relName = "ANS_E";
//				h.setPredicate(new Predicate(relName));
//				clause.addAtomToHeads(h);
//				
////				program.addRule(clause);
////				System.out.println("h2: " + h);
//			}
//		} catch (Exception e) {
//			throw new IllegalArgumentException();
//		}
		
		return visitChildren(ctx); 
	}

}
