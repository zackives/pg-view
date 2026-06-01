package edu.upenn.cis.db.Z3Solver;

import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Fixedpoint;
import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Params;
import com.microsoft.z3.Quantifier;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Sort;
import com.microsoft.z3.Status;

import edu.upenn.cis.db.helper.Util;

public class Z3Tester {
	final static Logger logger = LogManager.getLogger(Z3Tester.class);

	public static void check() {
		/**
		 * How to set up dyld in OSX
		 * https://stackoverflow.com/questions/18855488/eclipse-on-mac-dyld-library-not-loaded-image-not-found
		 * 
		 * /Users/sbnet21/Tools/z3-4.8.7-x64-osx-10.14.6/bin
		 */		
	    HashMap<String, String> cfg = new HashMap<>();
	    cfg.put("smtlib2_compliant",  "true");

		@SuppressWarnings("resource")
		Context ctx = new Context(cfg);
		
		String name = "rels";
		Sort I = ctx.getIntSort();
		Sort[] domain = new Sort[]{I, I};
		Sort range = ctx.getBoolSort();
		FuncDecl f = ctx.mkFuncDecl(name, domain, range);

		Util.Console.logln(f.toString());
		
        Params p = ctx.mkParams();
	
        p.add("engine", "datalog");
		p.add("print_fixedpoint_extensions", true);
//		p.add("datalog.default_relation", "external_relation");
//		p.add("fixedpoint.print_answer", true);
		Fixedpoint fix = ctx.mkFixedpoint();
		fix.setParameters(p);
		
		Fixedpoint fp = ctx.mkFixedpoint();
		FuncDecl rel = ctx.mkFuncDecl("rrs", domain, range);
		fp.registerRelation(rel);
//		fp.setPredicateRepresentation(rel, new Symbol()[]);
		Util.Console.logln(fp.getParameterDescriptions().toString());
		Util.Console.logln(fp.getHelp().toString());
		Util.Console.logln(fp.getStatistics().toString());
		Util.Console.logln("[" + fp.toString() + "]");
		
//		FuncDecl init = ctx.MkFuncDecl("init", new Sort[] {B, B}, T);
//		s.RegisterRelation(phi);
//		s.RegisterRelation(init);
		Expr[] relBound = new Expr[2];
		relBound[0] = ctx.mkConst("r" + 0, rel.getDomain()[0]);
		relBound[1] = ctx.mkConst("r" + 1, rel.getDomain()[1]);
		
		Expr initExpr = ctx.mkImplies(
			      ctx.mkAnd(ctx.mkEq(relBound[0], ctx.mkInt(0))), ctx.mkAnd(ctx.mkEq(relBound[1], ctx.mkInt(2))));

		Quantifier q = ctx.mkForall(relBound, initExpr, 1, null, relBound, null, null);

		fp.addRule(q, null);
		
		IntExpr int1 = (IntExpr)(Expr<?>)ctx.mkBound(1, I);
		IntExpr int2 = (IntExpr)(Expr<?>)ctx.mkBound(2, I);
		fp.addRule(ctx.mkImplies((BoolExpr)rel.apply(int1, int2), (BoolExpr)rel.apply(int1, int2)), 
				ctx.mkSymbol("GOGO"));

		Util.Console.logln("[" + fp.toString() + "]");

		Solver s = ctx.mkSolver();
		s.add(fp.getRules());
	    System.out.println("final string: [" + s.toString() + "]");

		Status st = s.check();
	
		
		Util.Console.logln("st: " + st.toString());
		for (String k : fp.getStatistics().getKeys()) {
			Util.Console.logln(k);
		}
			
	}
	
	public static void main(String args[]) {
		Z3Tester.check();
		
		Util.Console.logln("END.");
	}

}
