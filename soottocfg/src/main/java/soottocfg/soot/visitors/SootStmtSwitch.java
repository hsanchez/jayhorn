/*
 * jimple2boogie - Translates Jimple (or Java) Programs to Boogie
 * Copyright (C) 2013 Martin Schaef and Stephan Arlt
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package soottocfg.soot.visitors;

import java.util.Iterator;
import java.util.LinkedList;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;

import soot.ArrayType;
import soot.Body;
import soot.IntType;
import soot.Local;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.AnyNewExpr;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.BreakpointStmt;
import soot.jimple.DefinitionStmt;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.EnterMonitorStmt;
import soot.jimple.ExitMonitorStmt;
import soot.jimple.FieldRef;
import soot.jimple.GotoStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.LengthExpr;
import soot.jimple.LookupSwitchStmt;
import soot.jimple.NewArrayExpr;
import soot.jimple.NopStmt;
import soot.jimple.RetStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StmtSwitch;
import soot.jimple.TableSwitchStmt;
import soot.jimple.ThrowStmt;
import soot.toolkits.graph.CompleteUnitGraph;
import soottocfg.cfg.SourceLocation;
import soottocfg.cfg.expression.BinaryExpression;
import soottocfg.cfg.expression.BinaryExpression.BinaryOperator;
import soottocfg.cfg.expression.Expression;
import soottocfg.cfg.expression.UnaryExpression;
import soottocfg.cfg.expression.UnaryExpression.UnaryOperator;
import soottocfg.cfg.method.CfgBlock;
import soottocfg.cfg.method.Method;
import soottocfg.cfg.statement.AssertStatement;
import soottocfg.cfg.statement.AssignStatement;
import soottocfg.cfg.statement.AssumeStatement;
import soottocfg.cfg.statement.CallStatement;
import soottocfg.cfg.statement.Statement;
import soottocfg.soot.util.MethodInfo;
import soottocfg.soot.util.SootTranslationHelpers;

/**
 * @author schaef
 */
public class SootStmtSwitch implements StmtSwitch {

	private final SootMethod sootMethod;
	private final Body sootBody;

	private final MethodInfo methodInfo;
	private final SootValueSwitch valueSwitch;

	private final PatchingChain<Unit> units;
	private final CompleteUnitGraph unitGraph;

	private CfgBlock currentBlock, entryBlock, exitBlock;
	private boolean insideMonitor = false;

	private Stmt currentStmt;

	protected SourceLocation loc;

	public SootStmtSwitch(Body body, MethodInfo mi) {
		this.methodInfo = mi;
		this.sootBody = body;
		this.sootMethod = sootBody.getMethod();

		this.valueSwitch = new SootValueSwitch(this);

		units = body.getUnits();
		Unit head = units.getFirst();
		unitGraph = new CompleteUnitGraph(sootBody);
		// check if the block is empty.
		if (head != null) {
			this.entryBlock = methodInfo.lookupCfgBlock(head);
			this.currentBlock = this.entryBlock;
			Iterator<Unit> iterator = units.iterator();
			while (iterator.hasNext()) {
				Unit u = iterator.next();
				u.apply(this);
			}
		} else {
			this.entryBlock = new CfgBlock(methodInfo.getMethod());
			this.currentBlock = this.entryBlock;
		}

		if (this.currentBlock != null) {
			this.exitBlock = this.currentBlock;
		} else {
			this.exitBlock = null;
		}
		// TODO: connect stuff to exit.
	}

	public CfgBlock getEntryBlock() {
		return this.entryBlock;
	}

	public CfgBlock getExitBlock() {
		return this.exitBlock;
	}

	public MethodInfo getMethodInto() {
		return this.methodInfo;
	}

	public SootMethod getMethod() {
		return this.sootMethod;
	}

	public Stmt getCurrentStmt() {
		return this.currentStmt;
	}

	public SourceLocation getCurrentLoc() {
		return this.loc;
	}

	/**
	 * Checks if the current statement is synchronized or inside a monitor
	 * 
	 * @return True if the current statement is inside a monitor or synchronized
	 *         and false, otherwise.
	 */
	public boolean isSynchronizedOrInsideMonitor() {
		return this.insideMonitor || this.sootMethod.isSynchronized();
	}

	public void push(Statement stmt) {
		this.currentBlock.addStatement(stmt);
	}

	private void connectBlocks(CfgBlock from, CfgBlock to) {
		Preconditions.checkArgument(!methodInfo.getMethod().containsEdge(from, to));
		this.methodInfo.getMethod().addEdge(from, to);
	}

	private void connectBlocks(CfgBlock from, CfgBlock to, Expression label) {
		Preconditions.checkArgument(!methodInfo.getMethod().containsEdge(from, to));
		this.methodInfo.getMethod().addEdge(from, to).setLabel(label);
	}

	private void precheck(Stmt st) {
		this.currentStmt = st;
		loc = SootTranslationHelpers.v().getSourceLocation(currentStmt);

		if (currentBlock != null) {
			// first check if we already created a block
			// for this statement.
			CfgBlock block = methodInfo.findBlock(st);
			if (block != null) {
				if (block != currentBlock) {
					connectBlocks(currentBlock, block);
					currentBlock = block;
				} else {
					// do nothing.
				}
			} else {
				if (unitGraph.getPredsOf(st).size() > 1) {
					// then this statement might be reachable via a back edge
					// and we have to create a new block for it.
					CfgBlock newBlock = methodInfo.lookupCfgBlock(st);
					connectBlocks(currentBlock, newBlock);
					currentBlock = newBlock;
				} else {
					// do nothing.
				}
			}
		} else {
			// If not, and we currently don't have a block,
			// create a new one.
			currentBlock = methodInfo.lookupCfgBlock(st);
		}
	}

	/*
	 * Below follow the visitor methods from StmtSwitch
	 * 
	 */

	@Override
	public void caseAssignStmt(AssignStmt arg0) {
		precheck(arg0);
		translateDefinitionStmt(arg0);
	}

	@Override
	public void caseBreakpointStmt(BreakpointStmt arg0) {
		precheck(arg0);
	}

	@Override
	public void caseEnterMonitorStmt(EnterMonitorStmt arg0) {
		precheck(arg0);
		arg0.getOp().apply(this.valueSwitch);
		this.valueSwitch.popExpression();
		this.insideMonitor = true;
		// TODO Havoc stuff
	}

	@Override
	public void caseExitMonitorStmt(ExitMonitorStmt arg0) {
		precheck(arg0);
		arg0.getOp().apply(this.valueSwitch);
		this.valueSwitch.popExpression();
		this.insideMonitor = false;
		// TODO:
	}

	@Override
	public void caseGotoStmt(GotoStmt arg0) {
		precheck(arg0);
		CfgBlock target = this.methodInfo.lookupCfgBlock(arg0.getTarget());
		connectBlocks(currentBlock, target);
		this.currentBlock = null;
	}

	@Override
	public void caseIdentityStmt(IdentityStmt arg0) {
		precheck(arg0);
		translateDefinitionStmt(arg0);
	}

	@Override
	public void caseIfStmt(IfStmt arg0) {
		precheck(arg0);
		arg0.getCondition().apply(valueSwitch);
		Expression cond = valueSwitch.popExpression();
		// apply the switch twice. Otherwise the conditional and its negation
		// are aliased.
		arg0.getCondition().apply(valueSwitch);
		Expression negCond = new UnaryExpression(loc, UnaryOperator.LNot, valueSwitch.popExpression());

		/*
		 * In jimple, conditionals are of the form if (x) goto y; So we end the
		 * current block and create two new blocks for then and else branch. The
		 * new currenBlock becomes the else branch.
		 */

		Unit next = units.getSuccOf(arg0);
		/*
		 * In rare cases of empty If- and Else- blocks, next and
		 * arg0.getTraget() are the same. For these cases, we do not generate an
		 * If statement, but still translate the conditional in case it may
		 * throw an exception.
		 */
		if (next == arg0.getTarget()) {
			// ignore the IfStmt.
			return;
		}

		CfgBlock thenBlock = methodInfo.lookupCfgBlock(arg0.getTarget());
		connectBlocks(currentBlock, thenBlock, cond);
		if (next != null) {
			CfgBlock elseBlock = methodInfo.lookupCfgBlock(next);
			connectBlocks(currentBlock, elseBlock, negCond);
			this.currentBlock = elseBlock;
		} else {
			connectBlocks(currentBlock, methodInfo.getSink(), negCond);
			this.currentBlock = null;
		}
	}

	@Override
	public void caseInvokeStmt(InvokeStmt arg0) {
		precheck(arg0);
		translateMethodInvokation(arg0, null, arg0.getInvokeExpr());
	}

	@Override
	public void caseLookupSwitchStmt(LookupSwitchStmt arg0) {
		throw new RuntimeException("Should have been eliminated by SwitchStatementRemover");
	}

	@Override
	public void caseNopStmt(NopStmt arg0) {
		precheck(arg0);
	}

	@Override
	public void caseRetStmt(RetStmt arg0) {
		throw new RuntimeException("Not implemented " + arg0);
	}

	@Override
	public void caseReturnStmt(ReturnStmt arg0) {
		precheck(arg0);
		arg0.getOp().apply(valueSwitch);
		Expression returnValue = valueSwitch.popExpression();
		currentBlock.addStatement(new AssignStatement(SootTranslationHelpers.v().getSourceLocation(arg0),
				methodInfo.getReturnVariable(), returnValue));
		connectBlocks(currentBlock, methodInfo.getSink());
		currentBlock = null;
	}

	@Override
	public void caseReturnVoidStmt(ReturnVoidStmt arg0) {
		precheck(arg0);
		connectBlocks(currentBlock, methodInfo.getSink());
		currentBlock = null;
	}

	@Override
	public void caseTableSwitchStmt(TableSwitchStmt arg0) {
		throw new RuntimeException("Should have been eliminated by SwitchStatementRemover");
	}

	@Override
	public void caseThrowStmt(ThrowStmt arg0) {
		precheck(arg0);
		throw new RuntimeException("Apply the ExceptionRemover first.");
		// arg0.getOp().apply(valueSwitch);
		// Expression exception = valueSwitch.popExpression();
		// currentBlock.addStatement(new
		// AssignStatement(SootTranslationHelpers.v().getSourceLocation(arg0),
		// methodInfo.getExceptionVariable(), exception));
		// connectBlocks(currentBlock, methodInfo.getSink());
		// currentBlock = null;
	}

	@Override
	public void defaultCase(Object arg0) {
		throw new RuntimeException("Case not implemented");
	}

	/**
	 * Translate method invokation. This assumes that exceptions and virtual
	 * calls have already been removed.
	 * 
	 * @param u
	 * @param optionalLhs
	 * @param call
	 */
	private void translateMethodInvokation(Unit u, Value optionalLhs, InvokeExpr call) {
		if (isHandledAsSpecialCase(u, optionalLhs, call)) {
			return;
		}
		// translate the expressions in the arguments first.
		LinkedList<Expression> args = new LinkedList<Expression>();
		for (int i = 0; i < call.getArgs().size(); i++) {
			call.getArg(i).apply(valueSwitch);
			args.add(valueSwitch.popExpression());
		}

		Expression baseExpression = null;
		// List of possible virtual methods that can be called at this point.
		// Order matters here.
		if (call instanceof InstanceInvokeExpr) {
			InstanceInvokeExpr iivk = (InstanceInvokeExpr) call;
			iivk.getBase().apply(valueSwitch);
			baseExpression = valueSwitch.popExpression();
			// add the "this" variable to the list of args
			args.addFirst(baseExpression);
			// this include Interface-, Virtual, and SpecialInvokeExpr
		} else if (call instanceof StaticInvokeExpr) {
			// no need to handle the base.
		} else if (call instanceof DynamicInvokeExpr) {
			// DynamicInvokeExpr divk = (DynamicInvokeExpr) call;
			System.err.println("Dynamic invoke translation is only a stub. Will be unsound!");
		} else {
			throw new RuntimeException("Cannot compute instance for " + call.getClass().toString());
		}

		Optional<Expression> receiver = Optional.absent();
		if (optionalLhs != null) {
			optionalLhs.apply(valueSwitch);
			receiver = Optional.of(valueSwitch.popExpression());
		}

		Method method = SootTranslationHelpers.v().lookupOrCreateMethod(call.getMethod());
		CallStatement stmt = new CallStatement(SootTranslationHelpers.v().getSourceLocation(u), method, args, receiver);
		this.currentBlock.addStatement(stmt);
	}

	/**
	 * Check if the call is a special case such as System.exit. If so, translate
	 * it and return true. Otherwise, ignore it and return false.
	 * 
	 * @param u
	 * @param optionalLhs
	 * @param call
	 * @return true, if its a special method that is handled by the procedure,
	 *         and false, otherwise.
	 */
	private boolean isHandledAsSpecialCase(Unit u, Value optionalLhs, InvokeExpr call) {
		if (call.getMethod().getSignature().equals(SootTranslationHelpers.v().getAssertMethod().getSignature())) {
			Verify.verify(optionalLhs == null);
			Verify.verify(call.getArgCount() == 1);
			call.getArg(0).apply(valueSwitch);
			currentBlock.addStatement(
					new AssertStatement(SootTranslationHelpers.v().getSourceLocation(u), valueSwitch.popExpression()));
			return true;
		}
		if (call.getMethod().getSignature().contains("<java.lang.String: int length()>")) {
			assert (call instanceof InstanceInvokeExpr);
			Expression rhs = SootTranslationHelpers.v().getMemoryModel()
					.mkStringLengthExpr(((InstanceInvokeExpr) call).getBase());
			if (optionalLhs != null) {
				optionalLhs.apply(valueSwitch);
				Expression lhs = valueSwitch.popExpression();
				currentBlock
						.addStatement(new AssignStatement(SootTranslationHelpers.v().getSourceLocation(u), lhs, rhs));
			}
			return true;
		}
		if (call.getMethod().getSignature().contains("<java.lang.System: void exit(int)>")) {
			// TODO: this is not sufficient for interprocedural analysis.
			currentBlock = null;
			return true;
		}

		if (call.getMethod().getDeclaringClass().getName().contains("org.junit.Assert")) {
			// TODO: this should not be hard coded!
			if (call.getMethod().getName().equals("fail")) {
				Stmt ret = SootTranslationHelpers.v().getDefaultReturnStatement(sootMethod.getReturnType(),
						currentStmt);
				ret.apply(this);
				return true;
			}
			// if (call.getMethod().getName().equals("assertTrue")) {
			// Preconditions.checkArgument(optionalLhs == null);
			// int idx = 0;
			// if (call.getArgCount() > 1) {
			// idx = 1;
			// }
			// call.getArg(idx).apply(valueSwitch);
			// Expression guard = valueSwitch.popExpression();
			// currentBlock.addStatement(new
			// AssertStatement(SootTranslationHelpers.v().getSourceLocation(u),
			// guard));
			// return true;
			// }
			// if (call.getMethod().getName().equals("assertEquals")) {
			// int idx = 0;
			// if (call.getArgCount() > 2) {
			// idx = 1;
			// }
			//
			// Preconditions.checkArgument(optionalLhs == null);
			// call.getArg(idx).apply(valueSwitch);
			// Expression left = valueSwitch.popExpression();
			// call.getArg(idx + 1).apply(valueSwitch);
			// Expression right = valueSwitch.popExpression();
			// currentBlock.addStatement(new
			// AssertStatement(SootTranslationHelpers.v().getSourceLocation(u),
			// new BinaryExpression(loc, BinaryOperator.Eq, left, right)));
			// return true;
			// }

			// System.err.println("We should hardcode JUnit stuff " +
			// call.getMethod().getDeclaringClass().getName()
			// + " method " + call.getMethod().getName());
		}

		if (call.getMethod().getDeclaringClass().getName().contains("com.google.common.base.")) {
			if (call.getMethod().getSignature().contains("void checkArgument(boolean)")) {
				Preconditions.checkArgument(optionalLhs == null);
				call.getArg(0).apply(valueSwitch);
				Expression guard = valueSwitch.popExpression();
				currentBlock.addStatement(new AssertStatement(SootTranslationHelpers.v().getSourceLocation(u), guard));
				return true;
			}
			// System.err.println("TODO: handle Guava properly: " +
			// call.getMethod().getSignature());
		}

		if (call.getMethod().getSignature().equals("<java.lang.Class: boolean isAssignableFrom(java.lang.Class)>")) {
			InstanceInvokeExpr iivk = (InstanceInvokeExpr) call;
			Verify.verify(call.getArgCount() == 1);
			if (optionalLhs != null) {
				optionalLhs.apply(valueSwitch);
				Expression lhs = valueSwitch.popExpression();
				iivk.getBase().apply(valueSwitch);
				Expression binOpRhs = valueSwitch.popExpression();
				call.getArg(0).apply(valueSwitch);
				Expression binOpLhs = valueSwitch.popExpression();
				Expression instOf = new BinaryExpression(this.getCurrentLoc(), BinaryOperator.PoLeq, binOpLhs,
						binOpRhs);
				currentBlock.addStatement(
						new AssignStatement(SootTranslationHelpers.v().getSourceLocation(u), lhs, instOf));
				return true;
			} // otherwise ignore.
		} else if (call.getMethod().getSignature().equals("<java.lang.Object: java.lang.Class getClass()>")) {
			InstanceInvokeExpr iivk = (InstanceInvokeExpr) call;
			Verify.verify(call.getArgCount() == 0);
			if (optionalLhs != null) {
				Value objectToGetClassFrom = iivk.getBase();
				soot.Type t = objectToGetClassFrom.getType();
				SootField typeField = null;
				if (t instanceof RefType) {
					// first make a heap-read of the type filed.
					typeField = ((RefType) t).getSootClass().getFieldByName(SootTranslationHelpers.typeFieldName);
				} else if (t instanceof ArrayType) {
					typeField = Scene.v().getSootClass("java.lang.Object")
							.getFieldByName(SootTranslationHelpers.typeFieldName);
				} else {
					throw new RuntimeException("Not implemented. " + t + ", " + t.getClass());
				}
				// now get the dynamic type
				SootTranslationHelpers.v().getMemoryModel().mkHeapReadStatement(getCurrentStmt(),
						Jimple.v().newInstanceFieldRef(objectToGetClassFrom, typeField.makeRef()), optionalLhs);
				return true;
			}
		} else if (call.getMethod().getSignature().equals("<java.lang.Class: java.lang.Object cast(java.lang.Object)>")) {
			//TODO: we have to check if we have to throw an exception or add			
			// E.g, String.<java.lang.Class: java.lang.Object cast(java.lang.Object)>(x); means (String)x
			InstanceInvokeExpr iivk = (InstanceInvokeExpr) call;
			Verify.verify(call.getArgCount() == 1);
			if (optionalLhs != null) {
				//TODO
				optionalLhs.apply(valueSwitch);
				Expression lhs = valueSwitch.popExpression();
				iivk.getBase().apply(valueSwitch);
				Expression binOpRhs = valueSwitch.popExpression();
				call.getArg(0).apply(valueSwitch);
				Expression binOpLhs = valueSwitch.popExpression();

				Expression instOf = new BinaryExpression(this.getCurrentLoc(), BinaryOperator.PoLeq, binOpLhs,
						binOpRhs);
				currentBlock.addStatement(
						new AssertStatement(SootTranslationHelpers.v().getSourceLocation(u), instOf));

				call.getArg(0).apply(valueSwitch);
				Expression asgnRhs = valueSwitch.popExpression();
				
				currentBlock.addStatement(
						new AssignStatement(SootTranslationHelpers.v().getSourceLocation(u), lhs, asgnRhs));
				return true;

			}
		} else if (call.getMethod().getSignature().equals("<java.lang.Class: boolean isInstance(java.lang.Object)>")) {
			/* E.g, 
			 *  $r2 = class "java/lang/String";
			 *  $z0 = virtualinvoke $r2.<java.lang.Class: boolean isInstance(java.lang.Object)>(r1);
			 *  checks if r1 instancof String
			 */
			InstanceInvokeExpr iivk = (InstanceInvokeExpr) call;
			Verify.verify(call.getArgCount() == 1);
			if (optionalLhs != null) {
				//TODO
				optionalLhs.apply(valueSwitch);
				Expression lhs = valueSwitch.popExpression();
				iivk.getBase().apply(valueSwitch);
				Expression binOpRhs = valueSwitch.popExpression();
				call.getArg(0).apply(valueSwitch);
				Expression binOpLhs = valueSwitch.popExpression();

				Expression instOf = new BinaryExpression(this.getCurrentLoc(), BinaryOperator.PoLeq, binOpLhs,
						binOpRhs);
				currentBlock.addStatement(
						new AssignStatement(SootTranslationHelpers.v().getSourceLocation(u), lhs, instOf));
				return true;

			}

		}
			

		return false;
	}

	private void translateDefinitionStmt(DefinitionStmt def) {

		if (def.containsInvokeExpr()) {
			translateMethodInvokation(def, def.getLeftOp(), def.getInvokeExpr());
			return;
		}

		Value lhs = def.getLeftOp();
		Value rhs = def.getRightOp();

		if (def.containsFieldRef()) {
			// panic programming
			Verify.verify(!(lhs instanceof FieldRef && rhs instanceof FieldRef));

			if (lhs instanceof FieldRef) {
				SootTranslationHelpers.v().getMemoryModel().mkHeapWriteStatement(def, def.getFieldRef(), rhs);
			} else if (rhs instanceof FieldRef) {
				SootTranslationHelpers.v().getMemoryModel().mkHeapReadStatement(def, def.getFieldRef(), lhs);
			} else {
				throw new RuntimeException("what?");
			}
		} else if (def.containsArrayRef()) {
			if (lhs instanceof ArrayRef) {
				SootTranslationHelpers.v().getMemoryModel().mkArrayWriteStatement(def, (ArrayRef)lhs, rhs);
			} else {
				SootTranslationHelpers.v().getMemoryModel().mkArrayReadStatement(def, (ArrayRef)rhs, lhs);
			}
		} else if (rhs instanceof LengthExpr) {
			LengthExpr le = (LengthExpr)rhs;
			SootField lengthField = SootTranslationHelpers.v().getFakeArrayClass((ArrayType)le.getOp().getType())
					.getFieldByName(SootTranslationHelpers.lengthFieldName);
			FieldRef lengthFieldRef = Jimple.v().newInstanceFieldRef(le.getOp(), lengthField.makeRef());			
			SootTranslationHelpers.v().getMemoryModel().mkHeapReadStatement(def, lengthFieldRef, lhs);
		} else {
			// local to local assignment.
			lhs.apply(valueSwitch);
			Expression left = valueSwitch.popExpression();
			rhs.apply(valueSwitch);
			Expression right = valueSwitch.popExpression();

			currentBlock
					.addStatement(new AssignStatement(SootTranslationHelpers.v().getSourceLocation(def), left, right));

			if (rhs instanceof AnyNewExpr) { // TODO: this should be implemented
												// in the memory model.
				AnyNewExpr anyNew = (AnyNewExpr) rhs;
				soot.Type t = anyNew.getType();
				SootField dynTypeField = null;
				if (t instanceof RefType) {
					// first make a heap-read of the type filed.
					dynTypeField = ((RefType) t).getSootClass().getFieldByName(SootTranslationHelpers.typeFieldName);
				} else if (t instanceof ArrayType) {
					dynTypeField = SootTranslationHelpers.v().getFakeArrayClass((ArrayType)t)
							.getFieldByName(SootTranslationHelpers.typeFieldName);
				} else {
					throw new RuntimeException("Not implemented. " + t + ", " + t.getClass());
				}
				FieldRef fieldRef = Jimple.v().newInstanceFieldRef(lhs, dynTypeField.makeRef());
				SootTranslationHelpers.v().getMemoryModel().mkHeapWriteStatement(getCurrentStmt(), fieldRef,
						SootTranslationHelpers.v().getClassConstant(t));
								
				if (rhs instanceof NewArrayExpr) {
					//assert the length of the new array.
					Local sizeLocal = Jimple.v().newLocal("$arrSizeLocal"+this.sootMethod.getActiveBody().getLocalCount(), IntType.v());
					this.sootMethod.getActiveBody().getLocals().add(sizeLocal);
					//create a temporary lengthof expression and translate it.
					translateDefinitionStmt(Jimple.v().newAssignStmt(sizeLocal, Jimple.v().newLengthExpr(lhs)));
					
					((NewArrayExpr)rhs).getSize().apply(valueSwitch);
					Expression arraySizeExpr = valueSwitch.popExpression();
					sizeLocal.apply(valueSwitch);
					Expression arraySizeLhs = valueSwitch.popExpression();
					currentBlock.addStatement(new AssumeStatement(getCurrentLoc(), new BinaryExpression(getCurrentLoc(), BinaryOperator.Eq, arraySizeLhs, arraySizeExpr)));
				}
			}
		}
	}

}
