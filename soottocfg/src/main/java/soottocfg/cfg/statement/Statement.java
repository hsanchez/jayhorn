/**
 * 
 */
package soottocfg.cfg.statement;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import soottocfg.cfg.Node;
import soottocfg.cfg.SourceLocation;
import soottocfg.cfg.Variable;
import soottocfg.cfg.expression.IdentifierExpression;

/**
 * @author schaef
 *
 */
public abstract class Statement implements Node, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 4810592044342837988L;

	public SourceLocation getSourceLocation() {
		return sourceLocation;
	}

	private final SourceLocation sourceLocation;

	public Statement(SourceLocation loc) {
		this.sourceLocation = loc;

	}

	public Set<IdentifierExpression> getIdentifierExpressions() {
		Set<IdentifierExpression> res = new HashSet<>();
		res.addAll(getUseIdentifierExpressions());
		res.addAll(getDefIdentifierExpressions());
		return res;
	}
	
	public abstract Set<IdentifierExpression> getUseIdentifierExpressions();
	
	public abstract Set<IdentifierExpression> getDefIdentifierExpressions();

	public Set<Variable> getUseVariables() {
		Set<Variable> res = new HashSet<Variable>();
		for (IdentifierExpression ie : getUseIdentifierExpressions()) {
			res.add(ie.getVariable());
		}
		return res;
	}

	public Set<Variable> getDefVariables() {
		Set<Variable> res = new HashSet<Variable>();
		for (IdentifierExpression ie : getDefIdentifierExpressions()) {
			res.add(ie.getVariable());
		}
		return res;
	}

	public Set<Variable> getAllVariables() {
		Set<Variable> res = new HashSet<Variable>();
		for (IdentifierExpression ie : getIdentifierExpressions()) {
			res.add(ie.getVariable());
		}
		return res;
	}
	
	public int getJavaSourceLine() {
		if (sourceLocation == null) {
			return -1;
		}
		return this.sourceLocation.getLineNumber();
	}
	
	public abstract Statement deepCopy();
}
