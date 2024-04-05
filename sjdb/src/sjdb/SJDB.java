/**
 * 
 */
package sjdb;
import java.io.*;
import java.util.ArrayList;

/**
 * @author nmg
 *
 */
public class SJDB {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		// read serialised catalogue from file and parse
		String catFile = args[0];
		Catalogue cat = new Catalogue();
		CatalogueParser catParser = new CatalogueParser(catFile, cat);
		catParser.parse();
		System.out.println("cat: " + cat.getRelation("Person").getAttributes());
		
		// read stdin, parse, and build canonical query plan
		//QueryParser queryParser = new QueryParser(cat, new InputStreamReader(System.in));
		//Operator plan = queryParser.parse();

		// create estimator visitor and apply it to canonical plan

		Operator plan = testPlan3();

		Estimator est = new Estimator();
		Inspector ins = new Inspector();
		plan.accept(est);
		System.out.println("Estimated cost: " + est.cost);
		plan.accept(ins);
		
		// create optimised plan
		System.out.println("Optimiser");
		Optimiser opt = new Optimiser(null);
		Estimator est2 = new Estimator();
		Operator optPlan = opt.optimise(plan);
		optPlan.accept(est2);
		System.out.println("Estimated cost: " + est2.cost);
		optPlan.accept(ins);

	}


	public static Operator makePlan(){
		NamedRelation r1 = new NamedRelation("A",3);
		NamedRelation r2 = new NamedRelation("B",3);
		r1.addAttribute(new Attribute("a1",3));
		r1.addAttribute(new Attribute("a2",3));
		r2.addAttribute(new Attribute("b1",3));
		r2.addAttribute(new Attribute("b2",3));

		Scan a = new Scan(r1);
		Scan b = new Scan(r2);

		Product p1 = new Product(a, b);

		Select s1 = new Select(p1, new Predicate(new Attribute("a2"), new Attribute("b2")));

		Select s2 = new Select(s1, new Predicate(new Attribute("a1"), "3"));

		ArrayList<Attribute> atts = new ArrayList<Attribute>();
		atts.add(new Attribute("a2"));
		Project pj1 = new Project(s2, atts);

		Operator plan = new Join(pj1,b, new Predicate(new Attribute("a2"), new Attribute("b2")));

		return plan;
	}


	public static Operator makeCanonicalPlan() {
		NamedRelation r1 = new NamedRelation("A",3);
		NamedRelation r2 = new NamedRelation("B",3);
		NamedRelation r3 = new NamedRelation("C",3);
		r1.addAttribute(new Attribute("a1",3));
		r1.addAttribute(new Attribute("a2",3));
		r2.addAttribute(new Attribute("b1",3));
		r2.addAttribute(new Attribute("b2",3));
		r3.addAttribute(new Attribute("c1",3));
		r3.addAttribute(new Attribute("c2",3));

		Scan a = new Scan(r1);
		Scan b = new Scan(r2);
		Scan c = new Scan(r3);

		Product p1 = new Product(a,b);
		Product p2 = new Product(p1,c);

		Select s1 = new Select(p2,new Predicate(new Attribute("b1"),"2"));
		Select s2 = new Select(s1,new Predicate(new Attribute("c1"),new Attribute("a1")));

		ArrayList<Attribute> atts = new ArrayList<Attribute>();
		atts.add(new Attribute("a2"));

		Project pj1 = new Project(s2, atts);



		return pj1;
	}


	public static Operator testPlan2() {
		NamedRelation r1 = new NamedRelation("A",3);
		NamedRelation r2 = new NamedRelation("B",3);
		r1.addAttribute(new Attribute("a1",3));
		r1.addAttribute(new Attribute("a2",3));
		r2.addAttribute(new Attribute("b1",3));
		r2.addAttribute(new Attribute("b2",3));

		Scan a = new Scan(r1);
		Scan b = new Scan(r2);

		Product p1 = new Product(a,b);

		Select s1 = new Select(p1,new Predicate(new Attribute("b2"),new Attribute("a1")));
		Select s2 = new Select(s1,new Predicate(new Attribute("b2"),new Attribute("a1")));

		ArrayList<Attribute> atts = new ArrayList<Attribute>();
		atts.add(new Attribute("a1"));

		Project pj1 = new Project(s2, atts);



		return pj1;
	}

	public static Operator testPlan3() {
		NamedRelation r1 = new NamedRelation("A",3);
		NamedRelation r2 = new NamedRelation("B",3);
		NamedRelation r3 = new NamedRelation("C",3);
		NamedRelation r4 = new NamedRelation("D",3);
		r1.addAttribute(new Attribute("a1",3));
		r1.addAttribute(new Attribute("a2",3));
		r2.addAttribute(new Attribute("b1",3));
		r2.addAttribute(new Attribute("b2",3));
		r3.addAttribute(new Attribute("c1",3));
		r3.addAttribute(new Attribute("c2",3));
		r4.addAttribute(new Attribute("d1",3));
		r4.addAttribute(new Attribute("d2",3));

		Scan a = new Scan(r1);
		Scan c = new Scan(r3);
		Scan d = new Scan(r4);
		Scan b = new Scan(r2);

		Product p1 = new Product(d,a);
		Product p2 = new Product(p1,b);
		Product p3 = new Product(p2,c);

		Select s1 = new Select(p3,new Predicate(new Attribute("a1"),new Attribute("d1")));
		Select s2 = new Select(s1,new Predicate(new Attribute("a1"),new Attribute("c1")));
		Select s3 = new Select(s2,new Predicate(new Attribute("d1"),new Attribute("c1")));

		ArrayList<Attribute> atts = new ArrayList<Attribute>();
		atts.add(new Attribute("a1"));
		atts.add(new Attribute("b1"));

		Project pj1 = new Project(s3, atts);



		return pj1;
	}

}
