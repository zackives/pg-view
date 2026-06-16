package edu.upenn.cis.db.ConjunctiveQuery;

import edu.upenn.cis.db.datalog.simpleengine.IntegerSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.LongSimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.SimpleTerm;
import edu.upenn.cis.db.datalog.simpleengine.StringSimpleTerm;
import edu.upenn.cis.db.helper.Util;

/**
 * Constructor of Term
 * @author sbnet21
 *
 */
public class Term implements Cloneable {
	private boolean isVariable;
	private boolean isConstructor = false;
	private boolean isGenId = false;
	
	/**
	 * Term (variable or constant)
	 */
	private String var;
	private SimpleTerm sterm; // FIXME:
	
	@Override
    public Object clone() throws CloneNotSupportedException {
		Term t = (Term)super.clone();
		
		return t;
	}
	
	public Term(String v, boolean isVariable) {
		this.isVariable = isVariable;
		this.var = v;
	}

	public Term(String v, boolean isVariable, boolean isConstructor) {
		this.isVariable = isVariable;
		this.isConstructor = isConstructor;
		this.var = v;
	}

	public boolean isVariable() {
		return isVariable;
	}
	
	public boolean isConstant() {
		return !isVariable;
	}
	
	public void setConstant(String c) {
		var = c;
		isVariable = false;
	}
	
	public void setVar(String v) {
		var = v;
		isVariable = true;		
	}
	
	public String getVar() {
		return var;
	}
	
	public SimpleTerm getSimpleTerm() {
		if (sterm == null && isVariable() == false) {
//			System.out.println("var: " + var);
			String first = var.substring(0,1);
			if (first.contentEquals("\"") == true || first.contentEquals("'") == true) {
				sterm = new StringSimpleTerm(Util.removeQuotes(var));
			} else if (var.contains(".")) {
				sterm = new StringSimpleTerm(var);
			} else {
				System.out.println("var: " + var);
				sterm = new LongSimpleTerm(Long.parseLong(var));
			}
		}
		return sterm;
	}
	
	public String toString() {
		if (isVariable == true) {
			return var;
		} else {
			return var; //Util.removeQuotes(var); //  "\"" + Util.removeQuotes(var) + "\"";
		}
	}

	public boolean isConstructor() {
		return isConstructor;
	}

	public void setConstructor(boolean isConstructor) {
		this.isConstructor = isConstructor;
	}

	public boolean isGenId() {
		return isGenId;
	}

	public void setGenId(boolean isGenId) {
		this.isGenId = isGenId;
	}
}
