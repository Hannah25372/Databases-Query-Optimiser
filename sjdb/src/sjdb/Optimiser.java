package sjdb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Optimiser implements PlanVisitor {

    /**
     * Original canonical plan
     */
    Operator canonicalPlan;

    /**
     * new optimised plan
     */
    Operator newPlan;

    /**
     * true if canonical from begins with project
     * if no project, will be none elsewhere in the tree
     */
    boolean topProject = false;

    /**
     * List of all operators in canonical plan
     */
    List<Operator> allOps = new ArrayList<>();

    /**
     * List of all selects of form attr=val
     */
    List<Select> selectsListVal = new ArrayList<>();

    /**
     * List of all selects of form attr=attr
     */
    List<Select> selectsListAtts = new ArrayList<>();

    /**
     * List of all scans
     */
    List<Scan> scanList = new ArrayList<>();


    /**
     * List of all attributes used in predicates
     */
    List<Attribute> predAtts = new ArrayList<>();

    /**
     * List of plans which get built up
     */
    List<OpAtts> buildUp = new ArrayList<>();

    /**
     * Estimator used to evaluate best join ordering
     */
    Estimator estimator = new Estimator();


    /**
     * Constructor
     *
     * @param catalogue defines the relations and attributes
     */
    public Optimiser(Catalogue catalogue) {

    }


    /**
     * Takes a canonical query plan (operator) and optimises it
     *
     * @param plan the original plan
     * @return a new optimised plan
     */
    public Operator optimise(Operator plan) {

        canonicalPlan = plan;

        //constructs a list of all operators, selects(att=val), selects(att=att), scans, predicates, and attributes in predicates
        getOps(canonicalPlan);

        //if begins with project
        //only add projects throughout if you project atts at top, otherwise you want the whole thing
        if (canonicalPlan instanceof Project) {
            topProject = true;
        }

        //Starts the list of built-up plans. List of all scans and the attributes with them to begin.
        //Check if a project can be added after each one. No removing attributes from checking list
        setUpBuildUpLeaves();

        //Optimise the whole plan
        getOptimisedPlan();

        //System.out.println(newPlan);

        //choose the least cost plan between new and original
        Estimator est = new Estimator();
        canonicalPlan.accept(est);
        int costOG = est.cost;
        est = new Estimator();
        newPlan.accept(est);
        int costNW = est.cost;


        if (costOG < costNW) {
            //System.out.println("Original plan was better");
            return canonicalPlan;
        } else {
            return newPlan;
        }

    }

    /**
     * Gets all operators from canonical plan into a list
     * @param plan the canonical plan
     */
    public void getOps(Operator plan) {
        if (plan instanceof Scan) {
            allOps.add(plan);
            scanList.add((Scan) plan);
        } else if (plan instanceof Select) {
            allOps.add(plan);
            predAtts.add(((Select)plan).getPredicate().getLeftAttribute());
            if (((Select)plan).getPredicate().equalsValue()) {
                selectsListVal.add((Select) plan);
            } else {
                selectsListAtts.add((Select) plan);
                predAtts.add(((Select)plan).getPredicate().getRightAttribute());
            }
            getOps(((Select) plan).getInput());
        } else if (plan instanceof Product) {
            allOps.add(plan);
            getOps(((Product) plan).getLeft());
            getOps(((Product) plan).getRight());
        } else if (plan instanceof Project) {
            allOps.add(plan);
            predAtts.addAll(((Project)plan).getAttributes());
            getOps(((Project) plan).getInput());
        } else if (plan instanceof Join) {
            allOps.add(plan);
            predAtts.add(((Join)plan).getPredicate().getLeftAttribute());
            predAtts.add(((Join)plan).getPredicate().getRightAttribute());
            getOps(((Join) plan).getLeft());
            getOps(((Join) plan).getRight());
        }
    }


    /**
     * generates new operations for all the scans and puts them in the buildUp(OpAtt) list to build plans
     */
    public void setUpBuildUpLeaves() {
        for (Scan scan : scanList ) {

            List<Attribute> opAttributes = new ArrayList<>();
            opAttributes.addAll(scan.getRelation().getAttributes());
            Operator newOp = buildNewScan(scan);

            //do not need to remove predAtts here as only adding a scan

            //check if want a project above this
            // attsInSubTree:  opAttributes
            // global atts:    predAtts
            newOp = tryAddingProject(opAttributes,newOp);
            if (newOp instanceof Project) {
                opAttributes = ((Project) newOp).getAttributes();
            }
            buildUp.add(new OpAtts(newOp, opAttributes));
        }


        /*System.out.println("1. plans so far: ");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " : " + item.getAtts());
        }*/
    }

    /**
     * Method which handles fully rebuilding the tree from the bottom up, creating new operations.
     * Builds tree with the idea of, push selects down, introduce joins in place of product selects, reordering joins
     * Results in an optimised plan
     */
    public void getOptimisedPlan() {

        //For each select(attr=val) find the scan it applied to and combine them, replacing that scan in buildUp list
        //Check if a project can be added after each select, and remove attributes from checking list
        while (!selectsListVal.isEmpty()) {
            Select select = selectsListVal.removeFirst();
            Attribute selectAttribute = select.getPredicate().getLeftAttribute();

            boolean completedSelect = false;

            for (int i = 0; i < buildUp.size(); i++) {
                Operator op = buildUp.get(i).getOp();
                List<Attribute> opAttributes = new ArrayList<>();
                opAttributes.addAll(buildUp.get(i).getAtts());
                for (Attribute scanAtt : opAttributes) {
                    if (scanAtt.getName().equals(selectAttribute.getName())) {

                        //found the scan with that attribute
                        Operator newOp = addSelect(select, op);

                        //remove predAtt from list
                        predAtts.remove(selectAttribute);

                        //check if you want a project above this
                        newOp = tryAddingProject(opAttributes,newOp);
                        if (newOp instanceof Project) {
                            opAttributes = ((Project) newOp).getAttributes();
                        }

                        buildUp.remove(i);
                        buildUp.add(i, new OpAtts(newOp, opAttributes));
                        completedSelect = true;

                        break;
                    }
                }
                if (completedSelect) break;
            }
        }
        /*System.out.println("2. plans so far: ");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " : " + item.getAtts());
        }*/


        //Introduce the select(attr=att) by adding joins (select+product).
        //Checks that the two attributes are not already in same subtree, in which case just adds the select
        //Runs until all select(attr=attr) added, or until all subtrees combined
        //Checks all select(attr=attr) to choose the one with the least cost to add first (join ordering)
        while (!selectsListAtts.isEmpty() && buildUp.size() > 1) {
            int cost = 1000000000;
            Select chosenSelect = null;
            Operator newOp = null;
            int chosenOne = 0;
            int chosenTwo = 0;

            //choosing the least cost select attr=attr to add from the list
            for (int i = 0; i < selectsListAtts.size(); i++) {
                Select select = selectsListAtts.get(i);
                Attribute selectAttr1 = select.getPredicate().getLeftAttribute();
                Attribute selectAttr2 = select.getPredicate().getRightAttribute();

                int one = findOpIndexInBuildUpForAtt(selectAttr1);
                int two = findOpIndexInBuildUpForAtt(selectAttr2);
                Operator checkOp;
                if (one==two) {
                    checkOp = addSelect(select, buildUp.get(one).getOp());
                } else {
                    checkOp = addJoin(buildUp.get(one).getOp(),buildUp.get(two).getOp(),select.getPredicate());
                }

                estimator = new Estimator();
                checkOp.accept(estimator);
                if(estimator.cost < cost) {
                    cost = estimator.cost;
                    newOp = checkOp;
                    chosenOne = one;
                    chosenTwo = two;
                    chosenSelect = select;
                }
            }

            //acc add the chosen select as either a select or join
            selectsListAtts.remove(chosenSelect);
            if (chosenOne==chosenTwo) {
                List<Attribute> opAttributes = new ArrayList<>();
                opAttributes.addAll(buildUp.get(chosenOne).getAtts());

                buildUp.remove(chosenOne);

                //remove predAtt from list
                predAtts.remove(chosenSelect.getPredicate().getLeftAttribute());
                predAtts.remove(chosenSelect.getPredicate().getRightAttribute());

                //check if want a project above this
                newOp = tryAddingProject(opAttributes,newOp);
                if (newOp instanceof Project) {
                    opAttributes = ((Project) newOp).getAttributes();
                }

                buildUp.add(new OpAtts(newOp, opAttributes));

            } else {

                List<Attribute> opAttributes = new ArrayList<Attribute>();
                opAttributes.addAll(buildUp.get(chosenOne).getAtts());
                opAttributes.addAll(buildUp.get(chosenTwo).getAtts());

                //removal via index, must do the larger one first
                if(chosenOne > chosenTwo) {
                    buildUp.remove(chosenOne);
                    buildUp.remove(chosenTwo);
                } else {
                    buildUp.remove(chosenTwo);
                    buildUp.remove(chosenOne);
                }

                //remove predAtt from list
                predAtts.remove(chosenSelect.getPredicate().getLeftAttribute());
                predAtts.remove(chosenSelect.getPredicate().getRightAttribute());

                //check if want a project above this
                newOp = tryAddingProject(opAttributes,newOp);
                if (newOp instanceof Project) {
                    opAttributes = ((Project) newOp).getAttributes();
                }

                buildUp.add(new OpAtts(newOp, opAttributes));
            }
        }
        /*System.out.println("3. plans so far: ");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " : " + item.getAtts());
        }*/


        //Join any last subtrees in the buildUp list by a product until only one tree left. Add the smallest subtrees together first.
        //Check if a project can be added after each one. No attributes removed from checking list.
        while ((buildUp.size() > 1)) {
            int firstIndex = smallestTupleCount();
            OpAtts first = buildUp.get(firstIndex);
            buildUp.remove(firstIndex);
            int secondIndex = smallestTupleCount();
            OpAtts second = buildUp.get(secondIndex);
            buildUp.remove(secondIndex);
            Operator newOp = addProduct(first.getOp(), second.getOp());
            List<Attribute> opAttributes = new ArrayList<>();
            opAttributes.addAll(first.getAtts());
            opAttributes.addAll(second.getAtts());

            //do not need to remove predAtts here as adding a product rather than a select/join
            //check if want a project above this
            newOp = tryAddingProject(opAttributes,newOp);
            if (newOp instanceof Project) {
                opAttributes = ((Project) newOp).getAttributes();
            }

            buildUp.add(new OpAtts(newOp, opAttributes));
        }
        /*System.out.println("4. plans so far: ");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " : " + item.getAtts());
        }*/

        //Sequentially add any last select(attr=attr) to the top.
        //Check if a project can be added after each one, and remove attributes from checking list
        while (!selectsListAtts.isEmpty()) {
            Select select = selectsListAtts.removeFirst();
            Operator newOp = addSelect(select, buildUp.getFirst().getOp());
            List<Attribute> opAttributes = new ArrayList<>();
            opAttributes.addAll(buildUp.getFirst().getAtts());

            //try remove predAtt from list
            predAtts.remove(select.getPredicate().getLeftAttribute());
            predAtts.remove(select.getPredicate().getRightAttribute());

            //check if want a project above this
            newOp = tryAddingProject(opAttributes,newOp);
            if (newOp instanceof Project) {
                opAttributes = ((Project) newOp).getAttributes();
            }

            OpAtts newOpAtts = new OpAtts(newOp, opAttributes);
            buildUp.removeFirst();
            buildUp.add(newOpAtts);
        }
        /*System.out.println("5. plans so far: ");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " : " + item.getAtts());
        }*/

        //set newPlan
        newPlan = buildUp.getFirst().getOp();

    }


    /**
     * Checks the attributes in the subtree against the attributes in predicates to determine if a project can be introduced here
     * @param opAttributes the attributes in the subtree
     * @param newOp the new operator constructed already
     * @return the same operator, potentially with a project added to the top
     */
    public Operator tryAddingProject(List<Attribute> opAttributes, Operator newOp) {

        if (topProject) {
            //List<Attribute> potentialProjectAtts = new ArrayList<>();
            //add attributes in predicates
            Set<Attribute> potentialProjectAttsSet = new HashSet<>();
            for (var attST : opAttributes) {
                for (var attG : predAtts) {
                    if (attG.getName().equals(attST.getName())) {
                        potentialProjectAttsSet.add(attST);
                    }
                }
            }
            if (potentialProjectAttsSet.size() < opAttributes.size() && !potentialProjectAttsSet.isEmpty()) {
                //add the project
                newOp = addProject(potentialProjectAttsSet.stream().toList(), newOp);
            }
        }
        return newOp;
    }


    /**
     * Finds tree snippet in buildUp with the smallest cost via use of estimator
     * @return index of tree in buildUp
     */
    public int smallestTupleCount(){
        int smallest = 1000000000;
        int small = 0;
        for (int i = 0; i < buildUp.size(); i++) {
            Estimator est = new Estimator();
            buildUp.get(i).getOp().accept(est);
            if (est.cost < smallest) {
                smallest = est.cost;
                small = i;
            }
        }
        return small;
    }

    /**
     * returns the Operation which contains the scan which has the attribute in question
     * @param att attribute searching for
     * @return index of buildUp of the operation containing the scan which contains the attribute
     */
    public int findOpIndexInBuildUpForAtt(Attribute att) {
        for (int i = 0; i < buildUp.size(); i++) {
            List<Attribute> opAttributes = buildUp.get(i).getAtts();
            for (Attribute scanAtt : opAttributes) {
                if (scanAtt.getName().equals(att.getName())) {
                    //found the scan with that attribute
                   return i;
                }
            }
        }
        return -1;
    }



    /**
     * Construct new scan from old scan
     * @param scan old scan
     * @return new scan
     */
    public Scan buildNewScan(Scan scan) {
        //build new relation with attributes
        NamedRelation newRelation = new NamedRelation(scan.getRelation().toString(), scan.getRelation().getTupleCount());
        List<Attribute> newAttributes = new ArrayList<>();
        for (Attribute att : scan.getRelation().getAttributes()) {
            newAttributes.add(new Attribute(att));
        }
        for (Attribute att : newAttributes) {
            newRelation.addAttribute(att);
        }

        //build new scan

        return new Scan(newRelation);
    }

    /**
     * Construct new select from old select
     * @param select old select
     * @param op operator select applied to
     * @return new select
     */
    public Operator addSelect(Select select, Operator op) {
        //build new predicate
        Predicate pred = select.getPredicate();
        Predicate newPred;
        if(pred.equalsValue()){
            newPred = new Predicate(new Attribute(pred.getLeftAttribute()), pred.getRightValue());
        } else {
            newPred = new Predicate(new Attribute(pred.getLeftAttribute()), new Attribute(pred.getRightAttribute()));
        }

        //build new select
        return new Select(op, newPred);
    }

    /**
     * Construct new product
     * @param left left operator product applied to
     * @param right right operator product applied to
     * @return new product
     */
    public Operator addProduct(Operator left, Operator right) {

        return new Product(left,right);
    }

    /**
     * Construct new project
     * @param atts list of old atts project uses
     * @param op operator project applied to
     * @return new project
     */
    public Operator addProject(List<Attribute> atts, Operator op) {

        List<Attribute> newAttributes = new ArrayList<>();
        for (Attribute att : atts) {
            newAttributes.add(new Attribute(att));
        }
        return new Project(op, newAttributes);
    }

    /**
     * Construct new join
     * @param left left operator product applied to
     * @param right right operator product applied to
     * @param pred old predicate used for join
     * @return new join
     */
    public Operator addJoin(Operator left, Operator right, Predicate pred) {

        Predicate newPred = new Predicate(new Attribute(pred.getLeftAttribute()), new Attribute(pred.getRightAttribute()));

        return new Join(left,right,newPred);
    }





    @Override
    public void visit(Scan op) {

    }

    @Override
    public void visit(Project op) {

    }

    @Override
    public void visit(Select op) {

    }

    @Override
    public void visit(Product op) {

    }

    @Override
    public void visit(Join op) {

    }

    class OpAtts {

        private Operator op;
        private List<Attribute> atts;

        public OpAtts(Operator op, List<Attribute> atts) {
            this.op = op;
            this.atts = atts;
        }


        public Operator getOp() {
            return op;
        }

        public List<Attribute> getAtts() {
            return atts;
        }

    }
}
