package dbn_jsmile;

import java.util.HashMap;

public class Test {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		HashMap<String, Case> cases = new HashMap<String, Case>();
		String pid;
		Case curCase;
		  	// results
		 
			  pid = "ABC";
			  if (cases.containsKey(pid)){
				  curCase = cases.get(pid);
			  }else{
				  curCase = new Case(pid);
				  cases.put(pid, curCase);
			  }
			  //get values and add them to case
			  int curSlice = 1;
			  curCase.addObservation("age", curSlice, 4) ;
			  curCase.addObservation("test", 1, 1);
			  System.out.println(cases.get("ABC").getStates()[1]);
			  
			  pid = "ABC";
			  if (cases.containsKey(pid)){
				  curCase = cases.get(pid);
			  }else{
				  curCase = new Case(pid);
				  cases.put(pid, curCase);
			  }
			  //get values and add them to case
			  curSlice = 2;
			  curCase.addObservation("age", curSlice, 4) ;
			  curCase.addObservation("test", curSlice, 1);
			  System.out.println(cases.get("ABC").getStates()[2]);
			  
	  

	}

}
