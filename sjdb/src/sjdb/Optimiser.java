package sjdb;

import java.util.ArrayList;
import java.util.List;

public class Optimiser implements PlanVisitor {


    // make list of operators
    //make list of selects
    //make lists of 'scans'
    //combine any val selects to the scan
    //combine any att att selects to a product then the two scans
    // this is push select down

    Operator canonicalPlan;

    Operator newerPlan;

    List<Operator> allOps = new ArrayList<>();

    //SELECT
    //the operator it is applied to
    //the predicate

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
     * List of plans which get built up
     */
    List<OpAtts> buildUp = new ArrayList<>();

    Estimator estimator;

    int noSelects;
    int noScans;

    /**
     * Constructor
     *
     * @param catalogue defines the relations and attributes
     */
    public Optimiser(Catalogue catalogue) {
        estimator = new Estimator();
    }


    /**
     * Takes a canonical query plan (operator) and optimises it
     *
     * @param plan the original plan
     * @return a new optimised plan
     */
    public Operator optimise(Operator plan) {
        canonicalPlan = plan;

        //contructs a list of all operators, selects, and scans, and returns number of selects and number of scans
        getOps(canonicalPlan);
        noSelects = getSelectOps();
        noScans = getScanOps();

        //starts the list of build up plans. just all the scans to start with
        setUpBuildUpLeaves();

        //make buildUp into a list of scans and select scans, using the select val
        pushSelectsDownIntroJoinsAndDetermineOrder();


        System.out.println("printing build up list");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " " + item.getAtts());
        }


        addProjectsAndPushDown();

        return newerPlan;
    }


    /**
     * Constructs a list of all selects from canonical plan
     * @return no. selects in canonical plan
     */
    public int getSelectOps() {
        int num = 0;
        for (Operator op : allOps) {
            if (op instanceof Select) {
                if (((Select) op).getPredicate().equalsValue()) {
                    selectsListVal.add((Select) op);
                } else {
                    selectsListAtts.add((Select) op);
                }
                num++;
            }
        }
        return num;
    }

    /**
     * Constructs a list of all scans from canonical plan
     * @return no. scans in canonical plan
     */
    public int getScanOps() {
        int num = 0;
        for (Operator op : allOps) {
            if (op instanceof Scan) {
                scanList.add((Scan) op);
                num++;
            }
        }
        return num;
    }


    /**
     * Gets all operators from canonical plan into a list
     * @param plan the canonical plan
     */
    public void getOps(Operator plan) {
        if (plan instanceof Scan) {
            allOps.add(plan);
        } else if (plan instanceof Select) {
            allOps.add(plan);
            getOps(((Select) plan).getInput());
        } else if (plan instanceof Product) {
            allOps.add(plan);
            getOps(((Product) plan).getLeft());
            getOps(((Product) plan).getRight());
        } else if (plan instanceof Project) {
            allOps.add(plan);
            getOps(((Project) plan).getInput());
        } else if (plan instanceof Join) {
            allOps.add(plan);
            getOps(((Join) plan).getLeft());
            getOps(((Join) plan).getRight());
        }
    }


    //make new scans and put in op att list

    /**
     * generates new operations for all the scans and puts them in the OpAtt list to build plans
     */
    public void setUpBuildUpLeaves() {
        for (Scan scan : scanList ) {
            buildUp.add(new OpAtts(buildNewScan(scan), scan.getRelation().getAttributes()));
        }
    }

    public void pushSelectsDownIntroJoinsAndDetermineOrder() {



        //combine val selects and scans
        while (!selectsListVal.isEmpty()) {
            Select select = selectsListVal.removeFirst();
            Attribute selectAttribute = select.getPredicate().getLeftAttribute();

            boolean completedSelect = false;

            for (int i = 0; i < buildUp.size(); i++) {
                Operator op = buildUp.get(i).getOp();
                List<Attribute> opAttributes = buildUp.get(i).getAtts();
                for (Attribute scanAtt : opAttributes) {
                    if (scanAtt.getName().equals(selectAttribute.getName())) {

                        //found the scan with that attribute
                        Operator newNode = addSelect(select, op);
                        buildUp.remove(i);
                        buildUp.add(i, new OpAtts(newNode, opAttributes));
                        completedSelect = true;
                        break;
                    }
                }
                if (completedSelect) break;
            }
        }



        //introduce select attr=att with joins (select+product)
        while (!selectsListAtts.isEmpty() && buildUp.size() > 1) {
            int cost = 1000000000;
            Select chosenSelect = null;
            Operator newOp = null;
            int chosenOne = 0;
            int chosenTwo = 0;

            // want to do a pass through all selects to choose the smallest cost one, then remove and add that.
            // If the two attributes in the same tree, then just add select, if different, combine them with a join
            // One could equal Two if that part has already been combined. in which case, you add the select just to the top, not doing a join.

            //choosing the least cost select attr=attr to add from the list
            for (int i = 0; i < selectsListAtts.size(); i++) {
                //System.out.println(i);
                Select select = selectsListAtts.get(i);
                Attribute selectAttr1 = select.getPredicate().getLeftAttribute();
                Attribute selectAttr2 = select.getPredicate().getRightAttribute();

                int one = findOpforAtt(selectAttr1);
                int two = findOpforAtt(selectAttr2);
                Operator checkOp;
                if (one==two) {
                    checkOp = addSelect(select, buildUp.get(one).getOp());
                } else {
                    checkOp = addJoin(buildUp.get(one).getOp(),buildUp.get(two).getOp(),select.getPredicate());
                }
                //System.out.println(checkOp);
                checkOp.accept(estimator);
                if(estimator.cost < cost) {
                    cost = estimator.cost;
                    newOp = checkOp;
                    chosenOne = one;
                    chosenTwo = two;
                    chosenSelect = select;
                }
            }

            //acc add the chosen select
            selectsListAtts.remove(chosenSelect);
            if (chosenOne==chosenTwo) {
                List<Attribute> opAttributes = buildUp.get(chosenOne).getAtts();
                buildUp.remove(chosenOne);
                buildUp.add(new OpAtts(newOp, opAttributes));
            } else {
                List<Attribute> opAttributes = buildUp.get(chosenOne).getAtts();
                opAttributes.addAll(buildUp.get(chosenTwo).getAtts());
                buildUp.remove(chosenOne);
                buildUp.remove(chosenTwo);
                buildUp.add(new OpAtts(newOp, opAttributes));
            }

        }



        if (selectsListAtts.isEmpty()) {
            System.out.println("Used all select attr=attr");
        }
        if (!(buildUp.size() > 1)) {
            System.out.println("one plan in list left");
        }



        //join any last trees by a product
        while ((buildUp.size() > 1)) {
            int firstIndex = smallestTupleCount();
            OpAtts first = buildUp.get(firstIndex);
            buildUp.remove(firstIndex);
            int secondIndex = smallestTupleCount();
            OpAtts second = buildUp.get(secondIndex);
            buildUp.remove(secondIndex);
            Operator newOp = addProduct(first.getOp(), second.getOp());
            List<Attribute> atts = first.getAtts();
            atts.addAll(second.getAtts());
            buildUp.add(new OpAtts(newOp, atts));
        }
        //add any last selects to the top
        while (!selectsListAtts.isEmpty()) {
            Select select = selectsListAtts.removeFirst();
            Operator newOp = addSelect(select, buildUp.getFirst().getOp());
            OpAtts newOpAtts = new OpAtts(newOp, buildUp.getFirst().getAtts());
            buildUp.removeFirst();
            buildUp.add(newOpAtts);
        }

        newerPlan = buildUp.getFirst().getOp();


    }

    public void addProjectsAndPushDown() {
        Operator newPlan = addProject(getTopProjectAtts(), newerPlan);

        System.out.println(newPlan);
    }

    /**
     * Gets the project at the top of the canonical form if there is one, if not projects all attributes in plan
     * @return list of attributes for the top project
     */
    public List<Attribute> getTopProjectAtts(){
        if (canonicalPlan instanceof Project) {
            return ((Project) canonicalPlan).getAttributes();
        } else {
            return buildUp.getFirst().getAtts();
        }
    }

    public int smallestTupleCount(){
        int smallest = 1000000000;
        int small = 0;
        Estimator est = new Estimator();
        for (int i = 0; i < buildUp.size(); i++) {
            buildUp.get(i).getOp().accept(est);
            if (est.cost < smallest) {
                smallest = est.cost;
                small = i;
            }
        }
        return small;
    }

    public int findOpforAtt(Attribute att) {
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



    //rebuild new tree

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
        Scan newScan = new Scan(newRelation);

        return newScan;
    }

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
        Select newSelect = new Select(op, newPred);



        return newSelect;
    }

    public Operator addProduct(Operator left, Operator right) {

        return new Product(left,right);
    }

    public Operator addProject(List<Attribute> atts, Operator op) {

        List<Attribute> newAttributes = new ArrayList<>();
        for (Attribute att : atts) {
            newAttributes.add(new Attribute(att));
        }
        Project project = new Project(op, newAttributes);


        return project;
    }

    public Operator addJoin(Operator left, Operator right, Predicate pred) {
        Predicate newPred;
        if(pred.equalsValue()){
            newPred = new Predicate(new Attribute(pred.getLeftAttribute()), pred.getRightValue());
        } else {
            newPred = new Predicate(new Attribute(pred.getLeftAttribute()), new Attribute(pred.getRightAttribute()));
        }

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

        public void setOp(Operator op) {
            this.op = op;
        }

        public Operator getOp() {
            return op;
        }

        public List<Attribute> getAtts() {
            return atts;
        }
    }
}
