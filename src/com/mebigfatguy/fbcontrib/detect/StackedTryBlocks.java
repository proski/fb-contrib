package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.CodeException;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantPool;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.XMethod;

/**
 * looks for two or more try catch blocks that are consecutive and catch the
 * same kind of exception, and throw the same exception always. These blocks can
 * be coalesced into one.
 */

public class StackedTryBlocks extends BytecodeScanningDetector {

	private final BugReporter bugReporter;
	private List<TryBlock> blocks;
	private List<TryBlock> inBlocks;
	private OpcodeStack stack;

	public StackedTryBlocks(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
	}

	@Override
	public void visitClassContext(ClassContext classContext) {
		try {
			stack = new OpcodeStack();
			super.visitClassContext(classContext);
		} finally {
			stack = null;
		}
	}

	@Override
	public void visitCode(Code obj) {

		try {
			XMethod xMethod = getXMethod();
			String[] tes = xMethod.getThrownExceptions();
			Set<String> thrownExceptions = new HashSet<String>(Arrays.<String> asList((tes == null) ? new String[0]
					: tes));

			blocks = new ArrayList<TryBlock>();
			inBlocks = new ArrayList<TryBlock>();

			CodeException[] ces = obj.getExceptionTable();
			for (CodeException ce : ces) {
				TryBlock tb = new TryBlock(ce);
				int existingBlock = blocks.indexOf(tb);
				if (existingBlock >= 0) {
					tb = blocks.get(existingBlock);
					tb.addCatchType(ce);
				} else {
					blocks.add(tb);
				}
			}

			Iterator<TryBlock> it = blocks.iterator();
			while (it.hasNext()) {
				TryBlock block = it.next();
				if (block.hasMultipleHandlers() || block.isFinally()
						|| block.catchIsThrown(getConstantPool(), thrownExceptions)) {
					it.remove();
				}
			}

			if (blocks.size() > 1) {
				stack.resetForMethodEntry(this);
				super.visitCode(obj);

				if (blocks.size() > 1) {
					TryBlock firstBlock = blocks.get(0);
					for (int i = 1; i < blocks.size(); i++) {
						TryBlock secondBlock = blocks.get(i);

						if ((firstBlock.getCatchType() == secondBlock.getCatchType())
								&& (firstBlock.getThrowSignature().equals(secondBlock.getThrowSignature()))) {
							bugReporter.reportBug(new BugInstance(this, "STB_STACKED_TRY_BLOCKS", NORMAL_PRIORITY)
									.addClass(this).addMethod(this)
									.addSourceLineRange(this, firstBlock.getStartPC(), firstBlock.getEndHandlerPC())
									.addSourceLineRange(this, secondBlock.getStartPC(), secondBlock.getEndHandlerPC()));

						}

						firstBlock = secondBlock;
					}
				}
			}
		} finally {
			blocks = null;
			inBlocks = null;
		}
	}

	@Override
	public void sawOpcode(int seen) {

		try {
			int pc = getPC();
			TryBlock block = findBlockWithStart(pc);
			if (block != null) {
				inBlocks.add(block);
				block.setState(TryBlock.State.IN_TRY);
			}

			if (inBlocks.size() > 0) {
				TryBlock innerBlock = inBlocks.get(inBlocks.size() - 1);

				int nextPC = getNextPC();
				if (innerBlock.atHandlerPC(nextPC)) {
					if ((seen == GOTO) || (seen == GOTO_W)) {
						innerBlock.setEndHandlerPC(getBranchTarget());
					} else {
						inBlocks.remove(innerBlock);
						blocks.remove(innerBlock);
					}
				} else if (innerBlock.atHandlerPC(pc)) {
					innerBlock.setState(TryBlock.State.IN_CATCH);
				} else if (innerBlock.atEndHandlerPC(pc)) {
					inBlocks.remove(inBlocks.size() - 1);
					innerBlock.setState(TryBlock.State.AFTER);
				}

				if (innerBlock.inCatch()) {
					if (((seen >= Constants.IFEQ) && ((seen <= Constants.RET)))
							|| ((seen >= Constants.IRETURN) && (seen <= Constants.RETURN)) || (seen == GOTO_W)) {
						blocks.remove(innerBlock);
						inBlocks.remove(inBlocks.size() - 1);
					} else if (seen == ATHROW) {
						if (stack.getStackDepth() > 0) {
							OpcodeStack.Item item = stack.getStackItem(0);
							innerBlock.setThrowSignature(item.getSignature());
						} else {
							inBlocks.remove(inBlocks.size() - 1);
							innerBlock.setState(TryBlock.State.AFTER);
						}
					}
				}
			}
		} finally {
			stack.sawOpcode(this, seen);
		}
	}

	private TryBlock findBlockWithStart(int pc) {

		for (TryBlock block : blocks) {
			if (block.atStartPC(pc)) {
				return block;
			}
		}

		return null;
	}

	static class TryBlock {

		public enum State {
			BEFORE, IN_TRY, IN_CATCH, AFTER
		};

		int startPC;
		int endPC;
		int handlerPC;
		int endHandlerPC;
		BitSet catchTypes;
		String throwSig;
		State state;

		public TryBlock(CodeException ce) {
			startPC = ce.getStartPC();
			endPC = ce.getEndPC();
			handlerPC = ce.getHandlerPC();
			endHandlerPC = -1;
			catchTypes = new BitSet();
			catchTypes.set(ce.getCatchType());
			state = State.BEFORE;
		}

		public void addCatchType(CodeException ce) {
			catchTypes.set(ce.getCatchType());
		}

		public void setState(State executionState) {
			state = executionState;
		}

		public boolean inCatch() {
			return state == State.IN_CATCH;
		}

		public boolean hasMultipleHandlers() {
			int bit = catchTypes.nextSetBit(0);
			return catchTypes.nextSetBit(bit + 1) >= 0;
		}

		public boolean isFinally() {
			return catchTypes.get(0);
		}

		public boolean catchIsThrown(ConstantPool pool, Set<String> thrownExceptions) {
			if (thrownExceptions.size() > 0) {
				int exIndex = catchTypes.nextSetBit(0);
				String exName = ((ConstantClass) pool.getConstant(exIndex)).getBytes(pool);
				return thrownExceptions.contains(exName);
			}
			return false;
		}

		public void setEndHandlerPC(int end) {
			endHandlerPC = end;
		}

		public void setThrowSignature(String sig) {
			throwSig = sig;
		}

		public String getThrowSignature() {
			return (throwSig == null) ? String.valueOf(System.identityHashCode(this)) : throwSig;
		}

		public int getStartPC() {
			return startPC;
		}

		public int getEndHandlerPC() {
			return endHandlerPC;
		}

		public boolean atStartPC(int pc) {
			return startPC == pc;
		}

		public boolean atHandlerPC(int pc) {
			return handlerPC == pc;
		}

		public boolean atEndHandlerPC(int pc) {
			return (endHandlerPC >= 0) && (endHandlerPC == pc);
		}

		public int getCatchType() {
			return catchTypes.nextSetBit(0);
		}

		@Override
		public int hashCode() {
			return startPC ^ endPC;
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof TryBlock) {
				TryBlock that = (TryBlock) o;
				return (startPC == that.startPC) && (endPC == that.endPC);
			}

			return false;
		}

		@Override
		public String toString() {
			return "{" + startPC + " -> " + endPC + "} (catch " + catchTypes.nextSetBit(0) + ") {" + handlerPC + " -> "
					+ endHandlerPC + "}";
		}
	}
}
