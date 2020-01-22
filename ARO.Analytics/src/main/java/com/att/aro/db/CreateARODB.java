/*
 *  Copyright 2018 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.db;

import java.io.File;
import com.orientechnologies.orient.core.db.object.ODatabaseObject;
import com.orientechnologies.orient.object.db.OObjectDatabaseTx;

/**
 * Created by Harikrishna Yaramachu on 4/15/14.
 * 
 * Modified by Borey Sao
 * On November 19, 2014
 * Description: check if runningFile exist because there is already an instance of ODatabaseObject
 * , if exist we don't want to run two instance of it, it will keep Analyzer from starting.
 */
public class CreateARODB {
	// MODIFIED BY MO: can pass db home path with env variable.
	// private String dbFolder = System.getProperty( "user.home" ) + "/orient/db";
	private String dbHome = System.getenv("VIDEOOPTIMZER_ARO_DB") == null ? System.getProperty( "user.home" ) : System.getenv("VIDEOOPTIMZER_ARO_DB");
	private String dbFolder =  dbHome + "/orient/db";
	//////////////////
	//this file is created when a new instance of ODatabaseObject is created
	private String runningFile = dbFolder + "/db.wmr";
	private String url =  "plocal:" + dbFolder;
	private String user = "admin";
	private String pass = "admin";
	private static Object syncObj = new Object();
	private ODatabaseObject db = null;
	
	
	private CreateARODB(){
		
	}
	boolean init(){
		if(canInit()){
			db = new OObjectDatabaseTx(url);
			return true;
		}else{
			return false;
		}
	}
	private static CreateARODB createDB = null;
	public static CreateARODB getInstance(){
		
		if(createDB == null){
			synchronized(syncObj){
				if(createDB == null){
					createDB = new CreateARODB();
					if(!createDB.init()){
						createDB = null;
					}
				}
			}
		}
		return createDB;
	}
	private boolean canInit(){
		File file = new File(runningFile);
		if(file.exists()){
			return false;
		}else{
			return true;
		}
	}
	public ODatabaseObject getObjectDB(){

    	  //If Database doesn't exists then create one
    	 if(!db.exists()){ 		        	
	          db.create();
	          
	     }else{
	        db.open(user, pass);	            
	     }
    	
        return db;

    }

	 public void closeDB(){
		 if(db != null){
			 if(!db.isClosed()){
				 db.close();
			 }
		 }
	 }
}
