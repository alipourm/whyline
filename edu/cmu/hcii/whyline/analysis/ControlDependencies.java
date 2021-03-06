package edu.cmu.hcii.whyline.analysis;

import java.util.*;

import edu.cmu.hcii.whyline.bytecode.CodeAttribute;
import edu.cmu.hcii.whyline.bytecode.Instruction;

import gnu.trove.TIntHashSet;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TObjectIntHashMap;

/**
 * @author Andrew J. Ko
 *
 */
public final class ControlDependencies {

	private final CodeAttribute code;

	private final TIntObjectHashMap<Set<Instruction>> controlDependenciesByInstructionIndex;
	
	public ControlDependencies(CodeAttribute code) {

		this.code = code;
		
		controlDependenciesByInstructionIndex = new TIntObjectHashMap<Set<Instruction>>(code.getNumberOfInstructions() / 2);
		
		// Visit the instructions, treating them as a tree composed of branch instruction nodes.
		Instruction last = code.getInstruction(code.getNumberOfInstructions() - 1);

		ArrayList<Instruction> instructionsInPostOrderTraversalOfReverseControlFlowGraph = new ArrayList<Instruction>(code.getNumberOfInstructions());

		TObjectIntHashMap<Instruction> postOrderNumbers = visitPostOrderIterative(last, instructionsInPostOrderTraversalOfReverseControlFlowGraph);

		// Determine the post dominance frontier sets for each instruction.
		Instruction[] immediatePostDominators = determinePostDominanceFrontiers(postOrderNumbers, instructionsInPostOrderTraversalOfReverseControlFlowGraph);
		
		// For each instruction with multiple successors, add the instruction as a control dependency for its immediate post dominators.
		addControlDependencies(immediatePostDominators);
		
	}
	
	public Set<Instruction> getControlDependenciesOf(Instruction inst) {
		
		Set<Instruction> controlDependencies = controlDependenciesByInstructionIndex.get(inst.getIndex());
		return controlDependencies == null ? Collections.<Instruction>emptySet() : controlDependencies;

	}
		
	// Do a depth first traversal of the reverse control flow graph, numbering each instruction.
	// This can't be recursive, because we have some methods that are > 6000 instructions, and a typical JVM can't make it through that many method calls.
	private TObjectIntHashMap<Instruction> visitPostOrderIterative(Instruction start, ArrayList<Instruction> instructionsInPostOrderTraversalOfReverseControlFlowGraph) {
		
		TObjectIntHashMap<Instruction>postOrderNumbers = new TObjectIntHashMap<Instruction>();
		
		ArrayList<Instruction> stack = new ArrayList<Instruction>();
		TIntHashSet visitedIndices = new TIntHashSet(code.getNumberOfInstructions());
		TIntHashSet processedIndices = new TIntHashSet(code.getNumberOfInstructions());
		
		stack.add(start);
		while(stack.size() > 0) {
			Instruction top = stack.get(stack.size() - 1);
			if(visitedIndices.contains(top.getIndex())) {
				stack.remove(stack.size() - 1);
				if(!processedIndices.contains(top.getIndex())) {
					processedIndices.add(top.getIndex());
					
					instructionsInPostOrderTraversalOfReverseControlFlowGraph.add(0, top);
					postOrderNumbers.put(top, instructionsInPostOrderTraversalOfReverseControlFlowGraph.size());

				}
			}
			// Remember that we were here, then add the predecessors in reverse order to the stack.
			else {
				visitedIndices.add(top.getIndex());
				int insertionIndex = 0;
				for(Instruction predecessor : top.getOrderedPredecessors()) {
					if(!visitedIndices.contains(predecessor.getIndex())) {
						stack.add(stack.size() - insertionIndex, predecessor);
						insertionIndex++;
					}
				}
			}

		}

		return postOrderNumbers;
		
	}

	// From Cooper, Harvey, and Kennedy, "A Simple Fast Dominance Algorithm", but instead of using
	// a CFG and immediate dominators, it uses a reverse CFG (using predecessors as edges) and
	// immediate post dominators. This allows us to determine control dependencies.
	private Instruction[] determinePostDominanceFrontiers(TObjectIntHashMap<Instruction> postOrderNumbers, ArrayList<Instruction> instructionsInPostOrderTraversalOfReverseControlFlowGraph) {
				
		// Initialize the dominance array, with null represents the end node of the control flow graph.
		Instruction[] immediatePostDominators = new Instruction[code.getNumberOfInstructions()];

		// Keep an array tracking with instructions have post dominators set. We need this because
		// "null" represents "end node" and not "undefined" in the immediate post dominators array.
		// All of this is because we don't have an end node to point to.
		final boolean[] postDominatorIsSet = new boolean[code.getNumberOfInstructions()];

		boolean changed = true;
		while(changed) {
		
			changed = false;
			for(Instruction currentInstruction : instructionsInPostOrderTraversalOfReverseControlFlowGraph) {
			
				Set<Instruction> successors = currentInstruction.getOrderedSuccessors();
				
				// If this has no successor, then we still mark it as set so that it can be chosen as a post dominator.
				// All of this yuckiness is because we don't have an end node!
				if(successors.isEmpty()) {
					
					postDominatorIsSet[currentInstruction.getIndex()] = true;
					
				}
				else {
					
					// Pick a successor to start with, and then search for other successors that are a better choice.
					Iterator<Instruction> succIterator = successors.iterator();
					Instruction newImmediatePostDominator = succIterator.next();
					
					while(succIterator.hasNext()) {
						
						Instruction successor = succIterator.next();
						// As long as we've assigned an immediate post dominator for this successor, intersect the successor and the current selection for post dominator
						// The trick here is that null could also mean "end node" and not "undefined".
						if(postDominatorIsSet[successor.getIndex()]) {
							
							Instruction finger1 = successor;
							Instruction finger2 = newImmediatePostDominator;

							while(finger1 != null && finger2 != null && finger1 != finger2) {

								while(finger1 != null && finger2 != null && postOrderNumbers.get(finger1) < postOrderNumbers.get(finger2))
									finger1 = immediatePostDominators[finger1.getIndex()];

								while(finger1 != null && finger2 != null && postOrderNumbers.get(finger2) < postOrderNumbers.get(finger1))
									finger2 = immediatePostDominators[finger2.getIndex()];

							}
						
							newImmediatePostDominator = finger1;
							
						}
	
					}
						
					if(immediatePostDominators[currentInstruction.getIndex()] != newImmediatePostDominator) {

						immediatePostDominators[currentInstruction.getIndex()] = newImmediatePostDominator;
						postDominatorIsSet[currentInstruction.getIndex()] = true;
						changed = true;
						
					}
					
				}
				
			}
						
		}
		
		return immediatePostDominators;
				
	}
	
	// For each instruction with more than one possible successor, add the instruction as a control dependency
	// for each successor and its immediate post dominators. Here's the psuedocode given in Cooper (note that
	// we're going the opposite direction from this algorithm, so we're looking at successors).
	//
	//		for all nodes, b 
	//			if the number of predecessors of b >= 2 
	//				for all predecessors, p, of b 
	//					runner = p 
	//					while runner ! = doms[b] 
	//						add b to runner�s dominance frontier set 
	//						runner = doms[runner]
	//
	private void addControlDependencies(Instruction[] immediatePostDominators) {
		
		for(Instruction branch : code.getInstructions()) {
						
			Set<Instruction> successors = branch.getOrderedSuccessors();
			if(successors.size() >= 2) {

				for(Instruction successor : successors) {
					
					Instruction runner = successor;
					while(runner != null && runner != immediatePostDominators[branch.getIndex()]) {
						
						boolean wasNew = addControlDependency(runner, branch, successor);
						runner = immediatePostDominators[runner.getIndex()];
						
						if(!wasNew) break;
						
					}

				}

			}
			
		}

	}

	// Returns true if the dependency was a new one for this instruction.
	private boolean addControlDependency(Instruction inst, Instruction dependency, Instruction target) {
		
		Set<Instruction> dependencies = controlDependenciesByInstructionIndex.get(inst.getIndex());
		if(dependencies == null) {
			dependencies = new HashSet<Instruction>(1);
			controlDependenciesByInstructionIndex.put(inst.getIndex(), dependencies);
		}
		
		// If the dependencies already had this pair, return true.
		return dependencies.add(dependency);
		
	}
	
}