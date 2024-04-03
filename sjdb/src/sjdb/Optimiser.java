package sjdb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Optimiser implements PlanVisitor {


    //make list of operators
    //make list of selects
    //make lists of 'scans'
    //combine any val selects to the scan
    //combine any att att selects to a product then the two scans
    // this is push select down

    /**
     * Origional canonical plan
     */
    Operator canonicalPlan;

    /**
     * new optimised plan
     */
    Operator newerPlan;

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
     * List of all predicates
     */
    List<Predicate> preds = new ArrayList<>();

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
    Estimator estimator;


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

        //constructs a list of all operators, selects(att=val), selects(att=att), scans, predicates, and attributes in predicates
        getOps(canonicalPlan);

        //if begins with project
        if (canonicalPlan instanceof Project) {
            topProject = true;
        }

        //starts the list of build up plans. just all the scans to start with
        setUpBuildUpLeaves();

        //make buildUp into a list of scans and select scans, using the select val
        pushSelectsDownIntroJoinsAndDetermineOrder();


        System.out.println("printing build up list");
        for (var item : buildUp) {
            System.out.println(item.getOp() + " " + item.getAtts());
        }


        //addProjectsAndPushDown();
        System.out.println(newerPlan);

        return newerPlan;
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
            preds.add(((Select)plan).getPredicate());
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
            preds.add(((Join)plan).getPredicate());
            predAtts.add(((Join)plan).getPredicate().getLeftAttribute());
            predAtts.add(((Join)plan).getPredicate().getRightAttribute());
            getOps(((Join) plan).getLeft());
            getOps(((Join) plan).getRight());
        }
    }


    /**
     * generates new operations for all the scans and puts them in the buildUp'OpAtt' list to build plans
     */
    public void setUpBuildUpLeaves() {
        for (Scan scan : scanList ) {

            List<Attribute> opAttributes = scan.getRelation().getAttributes();
            Operator newOp = buildNewScan(scan);

            //do not need to remove predAtts here as only adding a scan

            //TODO:
            //check if want a project above this
            // attsInSubTree:  opAttributes
            // global atts:    predAtts
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
                if (potentialProjectAttsSet.size() < opAttributes.size()) {
                    //add the project
                    newOp = addProject(potentialProjectAttsSet.stream().toList(), newOp);
                }
            }


            buildUp.add(new OpAtts(newOp, opAttributes));
        }
    }

    /**
     * Method which handles fully rebuilding the tree from the bottom up, creating new operations.
     * Builds tree with the idea of, push selects down, introduce joins in place of product selects, reordering joins
     * Results in an optimised plan
     */
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
                        Operator newOp = addSelect(select, op);

                        //remove predAtt from list
                        predAtts.remove(selectAttribute);

                        //TODO:
                        //check if want a project above this
                        // attsInSubTree:  opAttributes
                        // global atts:    predAtts
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


                        buildUp.remove(i);
                        buildUp.add(i, new OpAtts(newOp, opAttributes));
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

                //remove predAtt from list
                predAtts.remove(chosenSelect.getPredicate().getLeftAttribute());
                predAtts.remove(chosenSelect.getPredicate().getRightAttribute());

                //TODO:
                //check if want a project above this
                // attsInSubTree:  opAttributes
                // global atts:    predAtts
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


                buildUp.add(new OpAtts(newOp, opAttributes));


            } else {
                List<Attribute> opAttributes = buildUp.get(chosenOne).getAtts();
                opAttributes.addAll(buildUp.get(chosenTwo).getAtts());
                buildUp.remove(chosenOne);
                buildUp.remove(chosenTwo);

                //remove predAtt from list
                predAtts.remove(chosenSelect.getPredicate().getLeftAttribute());
                predAtts.remove(chosenSelect.getPredicate().getRightAttribute());

                //TODO:
                //check if want a project above this
                // attsInSubTree:  opAttributes
                // global atts:    predAtts
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
            List<Attribute> opAttributes = first.getAtts();
            opAttributes.addAll(second.getAtts());

            //do not need to remove predAtts here as adding a product rather than a select/join

            //TODO:
            //check if want a project above this
            // attsInSubTree:  opAttributes
            // global atts:    predAtts
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

            buildUp.add(new OpAtts(newOp, opAttributes));


        }


        //add any last selects to the top
        while (!selectsListAtts.isEmpty()) {
            Select select = selectsListAtts.removeFirst();
            Operator newOp = addSelect(select, buildUp.getFirst().getOp());
            List<Attribute> opAttributes = buildUp.getFirst().getAtts();

           
            //remove predAtt from list
            predAtts.remove(select.getPredicate().getLeftAttribute());
            predAtts.remove(select.getPredicate().getRightAttribute());

            //TODO:
            //check if want a project above this
            // attsInSubTree:  opAttributes
            // global atts:    predAtts
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



            OpAtts newOpAtts = new OpAtts(newOp, opAttributes);
            buildUp.removeFirst();
            buildUp.add(newOpAtts);


        }

        newerPlan = buildUp.getFirst().getOp();


    }


    /**
     * Method which handles adding projects to the plan in the relevant places
     */
    public void addProjectsAndPushDown() {

        if (topProject) {
            newerPlan = addProject(getTopProjectAtts(), newerPlan);
        }
        System.out.println(newerPlan);
    }

    /**
     * Gets the project at the top of the canonical form if there is one, if not projects all attributes in plan
     * @return list of attributes for the top project
     */
    public List<Attribute> getTopProjectAtts(){
        if (canonicalPlan instanceof Project) {
            //atts in project
            return ((Project) canonicalPlan).getAttributes();
        } else {
            //all atts in plan
            return buildUp.getFirst().getAtts();
        }
    }


    /**
     * Finds tree snippet in buildUp with the smallest cost via use of estimator
     * @return index of tree in buildUp
     */
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

    /**
     * returns the Operation which contains the scan which has the attribute in question
     * @param att attribute searching for
     * @return index of buildUp of the operation containing the scan which contains the attribute
     */
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
     * Consruct new product
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
