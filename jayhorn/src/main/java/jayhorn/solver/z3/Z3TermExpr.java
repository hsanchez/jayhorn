package jayhorn.solver.z3;

import java.math.BigInteger;

import com.microsoft.z3.Expr;
import com.microsoft.z3.enumerations.Z3_lbool;

import jayhorn.solver.ProverExpr;
import jayhorn.solver.ProverType;

class Z3TermExpr implements ProverExpr {

	protected final ProverType type;
	protected final Expr term;

	Z3TermExpr(Expr expr, ProverType type) {
		this.term = expr;
		this.type = type;
	}

	public String toString() {
		return term.toString();
	}

	public ProverType getType() {
		return type;
	}

	/**
	 * Unpack the Z3 Expr 
	 * @return Z3 Expr for this TermExpr
	 */
	public Expr getExpr() {
		return this.term;
	}
	
	public BigInteger getIntLiteralValue() {
		throw new RuntimeException();
	}

	public boolean getBooleanLiteralValue() {
		return this.term.getBoolValue()==Z3_lbool.Z3_L_TRUE;
	}

	public int hashCode() {
		return term.hashCode();
	}

	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Z3TermExpr other = (Z3TermExpr) obj;
		if (term == null) {
			if (other.term != null)
				return false;
		} else if (!term.equals(other.term))
			return false;
		return true;
	}
}
