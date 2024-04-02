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
        pushSelectsDown();

        for (var item : buildUp) {
            System.out.println(item.getOp() + " " + item.getAtts());
        }

        return null;
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

    public void pushSelectsDown() {



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
        while (!selectsListAtts.isEmpty() || buildUp.size() > 1) {
            int cost = 1000000000;
            Operator chosenSelect = null;
            int chosenOne = 0;
            int chosenTwo = 0;

            //want to do a pass through all selects to choose the smallest cost one, then remove and add that.
            // If the two attributes in the same tree, then just add select, if different, combine them with a join

            //choosing the least cost select
            for (int i = 0; i < selectsListAtts.size(); i++) {
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
                checkOp.accept(estimator);
                if(estimator.cost < cost) {
                    cost = estimator.cost;
                    chosenSelect = checkOp;
                    chosenOne = one;
                    chosenTwo = two;
                }
            }

            //acc add the chosen select
            if (chosenOne==chosenTwo) {
                buildUp.remove(chosenOne);

                //add new one with the same list of atts
            } else {
                buildUp.remove(chosenOne);
                buildUp.remove(chosenTwo);

                //add new one with combined list of atts
            }

            //buildUp.add(i, new OpAtts(newNode, opAttributes));


            //one could equal two if that part has alreday been combined. in which case, you add the select just to the top, not doing a join.


        }




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

    public Operator addProject(ArrayList<Attribute> atts, Operator op) {

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

        /*
            A selection of the form attr=val can be pushed down to just above the
            relation that contains attr

            A selection of the form attr1=attr2 can be pushed down to the product above
            the subtree containing the relations that contain attr1 and attr2
        */

        /*
        Ideas

        for att=val
        get the select to be pushed down
        get the scan it is pushed to
        push it

        for att=att
        get the select to be pushed down
        get the product above two scans it is pushed to
        push it


        rebuild

        do i recreate from here? well all the projects will be at the top, so
        i could make a series of select scans



        store that there is a select on this relation.
        when I go through and rebuild, add in that select

         */














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
