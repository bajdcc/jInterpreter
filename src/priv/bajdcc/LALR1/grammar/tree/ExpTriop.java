package priv.bajdcc.LALR1.grammar.tree;

import priv.bajdcc.LALR1.grammar.codegen.ICodegen;
import priv.bajdcc.LALR1.grammar.semantic.ISemanticRecorder;
import priv.bajdcc.LALR1.grammar.type.TokenTools;
import priv.bajdcc.util.lexer.token.Token;
import priv.bajdcc.util.lexer.token.TokenType;

/**
 * 【语义分析】三元表达式
 *
 * @author bajdcc
 */
public class ExpTriop implements IExp {

	/**
	 * 前操作符
	 */
	private Token firstToken = null;

	/**
	 * 后操作符
	 */
	private Token secondToken = null;

	/**
	 * 第一操作数
	 */
	private IExp firstOperand = null;

	/**
	 * 第二操作数
	 */
	private IExp secondOperand = null;

	/**
	 * 第三操作数
	 */
	private IExp thirdOperand = null;

	public Token getFirstToken() {
		return firstToken;
	}

	public void setFirstToken(Token firstToken) {
		this.firstToken = firstToken;
	}

	public Token getSecondToken() {
		return secondToken;
	}

	public void setSecondToken(Token secondToken) {
		this.secondToken = secondToken;
	}

	public IExp getFirstOperand() {
		return firstOperand;
	}

	public void setFirstOperand(IExp firstOperand) {
		this.firstOperand = firstOperand;
	}

	public IExp getSecondOperand() {
		return secondOperand;
	}

	public void setSecondOperand(IExp secondOperand) {
		this.secondOperand = secondOperand;
	}

	public IExp getThirdOperand() {
		return thirdOperand;
	}

	public void setThirdOperand(IExp thirdOperand) {
		this.thirdOperand = thirdOperand;
	}

	@Override
	public boolean isConstant() {
		return firstOperand.isConstant();
	}

	@Override
	public IExp simplify(ISemanticRecorder recorder) {
		if (!isConstant()) {
			return this;
		}
		if (firstOperand instanceof ExpValue) {
			if (firstToken.kToken == TokenType.OPERATOR
					&& secondToken.kToken == TokenType.OPERATOR) {
				int triop = TokenTools.triop(recorder, this);
				switch (triop) {
				case 0:
					return this;
				case 1:
					return secondOperand;
				case 2:
					return thirdOperand;
				default:
					break;
				}
			}
		}
		return this;
	}

	@Override
	public void analysis(ISemanticRecorder recorder) {

	}

	@Override
	public void genCode(ICodegen codegen) {

	}

	@Override
	public String toString() {
		return "( " + firstOperand.toString() + " " + firstToken.toRealString()
				+ " " + secondOperand.toString() + " "
				+ secondToken.toRealString() + " " + thirdOperand.toString()
				+ " )";
	}

	@Override
	public String print(StringBuilder prefix) {
		return toString();
	}
}
