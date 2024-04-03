package sjdb;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class Estimator implements PlanVisitor {


	int cost;
	public Estimator() {
		// empty constructor
	}

	/* 
	 * Create output relation on Scan operator
	 *
	 * Example implementation of visit method for Scan operators.
	 */
	public void visit(Scan op) {
		Relation input = op.getRelation();
		Relation output = new Relation(input.getTupleCount());
		
		Iterator<Attribute> iter = input.getAttributes().iterator();
		while (iter.hasNext()) {
			output.addAttribute(new Attribute(iter.next()));
		}

		cost += output.getTupleCount();

		op.setOutput(output);


		System.out.println("Scan cost: " + output.getTupleCount());
		System.out.println("Total cost: " + cost);
	}

	public void visit(Project op) {
		Relation input = op.getInput().getOutput();
		List<Attribute> projectedAttributes = op.getAttributes();

		Relation output = new Relation(input.getTupleCount());

		Iterator<Attribute> iter = input.getAttributes().iterator();
		while (iter.hasNext()) {
			Attribute attrPlan = iter.next();

			Iterator<Attribute> iter2 = projectedAttributes.iterator();
			while (iter2.hasNext()) {
				Attribute attrList = iter2.next();

				if (attrPlan.getName().equals(attrList.getName())) {
					output.addAttribute(new Attribute(attrPlan));
				}
			}

		}

		cost += output.getTupleCount();
		op.setOutput(output);

		System.out.println("Project cost: " + output.getTupleCount());
		System.out.println("Total cost: " + cost);
	}
	
	public void visit(Select op) {
		Relation input = op.getInput().getOutput();
		Relation output;
		Predicate pred = op.getPredicate();
		Attribute attLeftPred = pred.getLeftAttribute();
		Attribute attRightPred = null;
		String val;
		int newMaxCount;

		//get Left predicate attribute with value count
		Iterator<Attribute> iter = input.getAttributes().iterator();
		while (iter.hasNext()) {
			Attribute att = iter.next();
			if (att.getName().equals(attLeftPred.getName())) {
				attLeftPred = new Attribute(att.getName(), att.getValueCount());
			}
		}

		if (pred.equalsValue()) {
			// attr==value

			//cost
			newMaxCount = input.getTupleCount() / attLeftPred.getValueCount();

		} else {
			//attr==attr
			attRightPred = pred.getRightAttribute();

			Iterator<Attribute> iter2 = input.getAttributes().iterator();
			while (iter2.hasNext()) {
				Attribute att = iter2.next();
				if (att.getName().equals(attRightPred.getName())) {
					attRightPred = new Attribute(att.getName(), att.getValueCount());
				}
			}


			//get number of unique values in left and right, and take the minimum of this for the cost.
			//also reduce the attribute tuple counts to match this.
			newMaxCount = input.getTupleCount() / Math.max(attRightPred.getValueCount(), attLeftPred.getValueCount());

		}

		output = new Relation(newMaxCount);
		Iterator<Attribute> iter3 = input.getAttributes().iterator();
		while (iter3.hasNext()) {
			Attribute att = iter3.next();
			if (att.getValueCount() > newMaxCount) {
				output.addAttribute(new Attribute(att.getName(), newMaxCount));
			} else {
				output.addAttribute(new Attribute(att));
			}
		}

		cost += output.getTupleCount();

		op.setOutput(output);

		System.out.println("Select cost: " + output.getTupleCount());
		System.out.println("Total cost: " + cost);
	}
	
	public void visit(Product op) {

		Relation inputLeft = op.getLeft().getOutput();
		Relation inputRight = op.getRight().getOutput();

		Relation output = new Relation(inputLeft.getTupleCount() * inputRight.getTupleCount());

		Iterator<Attribute> iter = inputLeft.getAttributes().iterator();
		while (iter.hasNext()) {
			output.addAttribute(new Attribute(iter.next()));
		}

		Iterator<Attribute> iter2 = inputRight.getAttributes().iterator();
		while (iter2.hasNext()) {
			output.addAttribute(new Attribute(iter2.next()));
		}

		op.setOutput(output);

		cost += output.getTupleCount();

		System.out.println("Product cost: " + output.getTupleCount());
		System.out.println("Total cost: " + cost);

	}
	
	public void visit(Join op) {
		Relation inputLeft = op.getLeft().getOutput();
		Relation inputRight = op.getRight().getOutput();

		Predicate predicate = op.getPredicate();

		//effectively a product of both and a select for common attributes being equal


		//product
		Relation output = new Relation(inputLeft.getTupleCount() * inputRight.getTupleCount());
		Iterator<Attribute> iter = inputLeft.getAttributes().iterator();
		while (iter.hasNext()) {
			output.addAttribute(new Attribute(iter.next()));
		}
		Iterator<Attribute> iter2 = inputRight.getAttributes().iterator();
		while (iter2.hasNext()) {
			output.addAttribute(new Attribute(iter2.next()));
		}

		//select
		//get the predicate attributes
		Attribute attLeftPred = predicate.getLeftAttribute();
		Attribute attRightPred = predicate.getRightAttribute();
		Iterator<Attribute> iter3 = inputLeft.getAttributes().iterator();
		while (iter3.hasNext()) {
			Attribute att = iter3.next();
			if (att.getName().equals(attLeftPred.getName())) {
				attLeftPred = new Attribute(att.getName(), att.getValueCount());
			}
		}
		Iterator<Attribute> iter4 = inputRight.getAttributes().iterator();
		while (iter4.hasNext()) {
			Attribute att = iter4.next();
			if (att.getName().equals(attRightPred.getName())) {
				attRightPred = new Attribute(att.getName(), att.getValueCount());
			}
		}

		int newMaxCount = output.getTupleCount() / Math.max(attRightPred.getValueCount(), attLeftPred.getValueCount());

		//change the counts
		Relation output2 = new Relation(newMaxCount);
		Iterator<Attribute> iter5 = output.getAttributes().iterator();
		while (iter5.hasNext()) {
			Attribute att = iter5.next();
			if (att.getValueCount() > newMaxCount) {
				output2.addAttribute(new Attribute(att.getName(), newMaxCount));
			} else {
				output2.addAttribute(new Attribute(att));
			}
		}

		op.setOutput(output2);

		cost += output2.getTupleCount();

		System.out.println("Join cost: " + output2.getTupleCount());
		System.out.println("Total cost: " + cost);

	}
}
