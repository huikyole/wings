/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.isi.wings.execution.tools.api.impl.kb;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Properties;

import edu.isi.wings.catalog.component.ComponentFactory;
import edu.isi.wings.catalog.data.DataFactory;
import edu.isi.wings.catalog.resource.ResourceFactory;
import edu.isi.wings.common.URIEntity;
import edu.isi.wings.common.kb.KBUtils;
import edu.isi.wings.execution.engine.classes.ExecutionQueue;
import edu.isi.wings.execution.engine.classes.RuntimeInfo;
import edu.isi.wings.execution.engine.classes.RuntimePlan;
import edu.isi.wings.execution.engine.classes.RuntimeStep;
import edu.isi.wings.execution.tools.api.ExecutionLoggerAPI;
import edu.isi.wings.execution.tools.api.ExecutionMonitorAPI;
import edu.isi.wings.ontapi.KBAPI;
import edu.isi.wings.ontapi.KBObject;
import edu.isi.wings.ontapi.OntFactory;
import edu.isi.wings.ontapi.OntSpec;
import edu.isi.wings.planner.api.WorkflowGenerationAPI;
import edu.isi.wings.planner.api.impl.kb.WorkflowGenerationKB;
import edu.isi.wings.workflow.plan.PlanFactory;
import edu.isi.wings.workflow.plan.api.ExecutionPlan;
import edu.isi.wings.workflow.plan.api.ExecutionStep;
import edu.isi.wings.workflow.plan.classes.ExecutionFile;
import edu.isi.wings.workflow.template.TemplateFactory;
import edu.isi.wings.workflow.template.api.Template;
import edu.isi.wings.workflow.template.api.TemplateCreationAPI;

import com.hp.hpl.jena.datatypes.xsd.XSDDateTime;

public class RunKB implements ExecutionLoggerAPI, ExecutionMonitorAPI {
	KBAPI kb;
	KBAPI libkb;

	Properties props;

	String ns;
	String onturl;
	String liburl;
	String newrunurl;
	String tdbRepository;
	OntFactory ontologyFactory;

	protected HashMap<String, KBObject> objPropMap;
	protected HashMap<String, KBObject> dataPropMap;
	protected HashMap<String, KBObject> conceptMap;

	public RunKB(Properties props) {
		this.props = props;
		this.onturl = props.getProperty("ont.execution.url");
		this.liburl = props.getProperty("lib.domain.execution.url");
		this.newrunurl = props.getProperty("domain.executions.dir.url");
		this.tdbRepository = props.getProperty("tdb.repository.dir");

		if (tdbRepository == null) {
			this.ontologyFactory = new OntFactory(OntFactory.JENA);
		} else {
			this.ontologyFactory = new OntFactory(OntFactory.JENA, this.tdbRepository);
		}
		KBUtils.createLocationMappings(props, this.ontologyFactory);
		try {
			this.kb = this.ontologyFactory.getKB(liburl, OntSpec.PLAIN, true);
			this.kb.importFrom(this.ontologyFactory.getKB(onturl, OntSpec.PLAIN, false, true));
			this.libkb = this.ontologyFactory.getKB(liburl, OntSpec.PLAIN);
			this.initializeMaps();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void initializeMaps() {
		this.objPropMap = new HashMap<String, KBObject>();
		this.dataPropMap = new HashMap<String, KBObject>();
		this.conceptMap = new HashMap<String, KBObject>();

		for (KBObject prop : this.kb.getAllObjectProperties()) {
			this.objPropMap.put(prop.getName(), prop);
		}
		for (KBObject prop : this.kb.getAllDatatypeProperties()) {
			this.dataPropMap.put(prop.getName(), prop);
		}
		for (KBObject con : this.kb.getAllClasses()) {
			this.conceptMap.put(con.getName(), con);
		}
		if (!dataPropMap.containsKey("hasLog"))
			dataPropMap.put("hasLog", this.kb.createDatatypeProperty(this.onturl + "#hasLog"));
    if(!objPropMap.containsKey("hasSeededTemplate"))
      objPropMap.put("hasSeededTemplate", kb.createObjectProperty(this.onturl+"#hasSeededTemplate"));
	}

	@Override
	public void startLogging(RuntimePlan exe) {
		try {
		  KBAPI tkb = this.ontologyFactory.getKB(OntSpec.PLAIN);
		  this.writeExecutionRun(tkb, exe);
		  tkb.saveAs(exe.getURL());
		  // exe.getPlan().save();
		  KBObject exobj = kb.createObjectOfClass(exe.getID(), conceptMap.get("Execution"));
		  this.updateRuntimeInfo(kb, exobj, exe.getRuntimeInfo());
		  kb.save();
		} catch (Exception e) {
		  e.printStackTrace();
		}
	}

	@Override
	public void updateRuntimeInfo(RuntimePlan exe) {
	  try {
	    KBAPI tkb = this.ontologyFactory.getKB(exe.getURL(), OntSpec.PLAIN);
	    this.updateExecutionRun(tkb, exe);
	    tkb.save();

	    KBObject exobj = kb.getIndividual(exe.getID());
	    this.updateRuntimeInfo(kb, exobj, exe.getRuntimeInfo());
	    kb.save();
	  } catch (Exception e) {
	    e.printStackTrace();
	  }
	}

	@Override
	public void updateRuntimeInfo(RuntimeStep stepexe) {
    try {
      KBAPI tkb = this.ontologyFactory.getKB(stepexe.getRuntimePlan().getURL(),
          OntSpec.PLAIN);
      this.updateExecutionStep(tkb, stepexe);
      tkb.save();
    } catch (Exception e) {
      e.printStackTrace();
    }
	}

	@Override
	public ArrayList<RuntimePlan> getRunList() {
		ArrayList<RuntimePlan> rplans = new ArrayList<RuntimePlan>();
		for (KBObject exobj : this.kb.getInstancesOfClass(conceptMap.get("Execution"), true)) {
			RuntimePlan rplan = this.getExecutionRun(exobj, false);
			rplans.add(rplan);
		}
		return rplans;
	}

	@Override
	public RuntimePlan getRunDetails(String runid) {
		KBObject exobj = this.kb.getIndividual(runid);
		try {
			RuntimePlan rplan = this.getExecutionRun(exobj, true);
			return rplan;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public boolean deleteRun(String runid) {
		return this.deleteExecutionRun(runid);
	}

	@Override
	public boolean runExists(String runid) {
		KBObject obj = this.kb.getIndividual(runid);
		if(obj != null)
			return true;
		return false;
	}
	

	@Override
	public void delete() {
		for(RuntimePlan rplan : this.getRunList()) {
			this.deleteRun(rplan.getID());
		}
		this.kb.delete();
	}

	/*
	 * Private helper functions
	 */
	private KBObject writeExecutionRun(KBAPI tkb, RuntimePlan exe) {
		KBObject exobj = tkb.createObjectOfClass(exe.getID(), conceptMap.get("Execution"));
		KBObject xtobj = tkb.getResource(exe.getExpandedTemplateID());
		KBObject tobj = tkb.getResource(exe.getOriginalTemplateID());
		KBObject sobj = tkb.getResource(exe.getSeededTemplateID());
		KBObject pobj = tkb.getResource(exe.getPlan().getID());
		if(xtobj != null)
		  tkb.setPropertyValue(exobj, objPropMap.get("hasExpandedTemplate"), xtobj);
		if(sobj != null)
		  tkb.setPropertyValue(exobj, objPropMap.get("hasSeededTemplate"), sobj);
		if(tobj != null)
		  tkb.setPropertyValue(exobj, objPropMap.get("hasTemplate"), tobj);
		if(pobj != null)
		  tkb.setPropertyValue(exobj, objPropMap.get("hasPlan"), pobj);
		for (RuntimeStep stepexe : exe.getQueue().getAllSteps()) {
			KBObject stepobj = this.writeExecutionStep(tkb, stepexe);
			tkb.addPropertyValue(exobj, objPropMap.get("hasStep"), stepobj);
		}
		this.updateRuntimeInfo(tkb, exobj, exe.getRuntimeInfo());
		return exobj;
	}

	private RuntimePlan getExecutionRun(KBObject exobj, boolean details) {
		// Create new runtime plan
		RuntimePlan rplan = new RuntimePlan(exobj.getID());
		rplan.setRuntimeInfo(this.getRuntimeInfo(this.kb, exobj));
		RuntimeInfo.Status status = rplan.getRuntimeInfo().getStatus();
		if (details
				|| (status == RuntimeInfo.Status.FAILURE || status == RuntimeInfo.Status.RUNNING)) {
			try {
				KBAPI tkb = this.ontologyFactory.getKB(rplan.getURL(), OntSpec.PLAIN);
				exobj = tkb.getIndividual(rplan.getID());
				// Get execution queue (list of steps)
				ExecutionQueue queue = new ExecutionQueue();
				KBObject exobj_r = tkb.getIndividual(rplan.getID());
				for (KBObject stepobj : tkb.getPropertyValues(exobj_r, objPropMap.get("hasStep"))) {
					RuntimeStep rstep = new RuntimeStep(stepobj.getID());
					rstep.setRuntimeInfo(this.getRuntimeInfo(tkb, stepobj));
					queue.addStep(rstep);
				}
				rplan.setQueue(queue);

				// Get provenance information
				KBObject xtobj = tkb.getPropertyValue(exobj, objPropMap.get("hasExpandedTemplate"));
        KBObject sobj = tkb.getPropertyValue(exobj, objPropMap.get("hasSeededTemplate"));
				KBObject tobj = tkb.getPropertyValue(exobj, objPropMap.get("hasTemplate"));
				KBObject pobj = tkb.getPropertyValue(exobj, objPropMap.get("hasPlan"));
				if(xtobj != null)
				  rplan.setExpandedTemplateID(xtobj.getID());
        if(sobj != null)
          rplan.setSeededTemplateId(sobj.getID());
				if(tobj != null)
				  rplan.setOriginalTemplateID(tobj.getID());
				if(pobj != null)
				  rplan.setPlan(PlanFactory.loadExecutionPlan(pobj.getID(), props));

				return rplan;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return rplan;
	}

	private void deleteGraph(String id) {
	  try {
	    if(id != null)
	      ontologyFactory.getKB(new URIEntity(id).getURL(), OntSpec.PLAIN).delete();
    } catch (Exception e) {
      e.printStackTrace();
    }
	}
	
	private boolean deleteExecutionRun(String runid) {
		KBObject exobj = this.kb.getIndividual(runid);
		RuntimePlan rplan = this.getExecutionRun(exobj, true);
		try {
			KBAPI tkb = this.ontologyFactory.getKB(rplan.getURL(), OntSpec.PLAIN);
			this.deleteGraph(rplan.getExpandedTemplateID());
			this.deleteGraph(rplan.getSeededTemplateID());
			if(rplan.getPlan() != null)
			  this.deleteGraph(rplan.getPlan().getID());
	    tkb.delete();
	     
			// Delete output files
			if(rplan.getPlan() != null) {
        for (ExecutionStep step : rplan.getPlan().getAllExecutionSteps()) {
          for (ExecutionFile file : step.getOutputFiles()) {
            file.removeMetadataFile();
            File f = new File(file.getLocation());
            if(f.exists())
              f.delete();
          }
        }
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

		KBUtils.removeAllTriplesWith(this.kb, runid, false);
		return this.kb.save();
	}

	private KBObject writeExecutionStep(KBAPI tkb, RuntimeStep stepexe) {
		KBObject exobj = tkb.createObjectOfClass(stepexe.getID(), conceptMap.get("ExecutionStep"));
		this.updateRuntimeInfo(tkb, exobj, stepexe.getRuntimeInfo());
		return exobj;
	}

	private void updateExecutionRun(KBAPI tkb, RuntimePlan exe) {
		KBObject exobj = tkb.getIndividual(exe.getID());
		this.updateRuntimeInfo(tkb, exobj, exe.getRuntimeInfo());
	}

	private void updateExecutionStep(KBAPI tkb, RuntimeStep exe) {
		KBObject exobj = tkb.getIndividual(exe.getID());
		if(exobj == null) {
      exobj = this.writeExecutionStep(tkb, exe);
		  KBObject planexeobj = tkb.getIndividual(exe.getRuntimePlan().getID());
      tkb.addPropertyValue(planexeobj, objPropMap.get("hasStep"), exobj);
		}
		this.updateRuntimeInfo(tkb, exobj, exe.getRuntimeInfo());
	}

	private void updateRuntimeInfo(KBAPI tkb, KBObject exobj, RuntimeInfo rinfo) {
		tkb.setPropertyValue(exobj, dataPropMap.get("hasLog"),
				ontologyFactory.getDataObject(rinfo.getLog()));
		tkb.setPropertyValue(exobj, dataPropMap.get("hasStartTime"),
				this.getXSDDateTime(rinfo.getStartTime()));
		tkb.setPropertyValue(exobj, dataPropMap.get("hasEndTime"),
				this.getXSDDateTime(rinfo.getEndTime()));
		tkb.setPropertyValue(exobj, dataPropMap.get("hasExecutionStatus"),
				ontologyFactory.getDataObject(rinfo.getStatus().toString()));
	}

	private KBObject getXSDDateTime(Date date) {
		if (date == null)
			return null;
		Calendar cal = new GregorianCalendar();
		cal.setTime(date);
		return ontologyFactory.getDataObject(new XSDDateTime(cal));
	}

	private RuntimeInfo getRuntimeInfo(KBAPI tkb, KBObject exobj) {
		RuntimeInfo info = new RuntimeInfo();
		KBObject sttime = this.kb.getPropertyValue(exobj, dataPropMap.get("hasStartTime"));
		KBObject endtime = this.kb.getPropertyValue(exobj, dataPropMap.get("hasEndTime"));
		KBObject status = this.kb.getPropertyValue(exobj, dataPropMap.get("hasExecutionStatus"));
		KBObject log = this.kb.getPropertyValue(exobj, dataPropMap.get("hasLog"));
		if (sttime != null && sttime.getValue() != null)
			info.setStartTime(((XSDDateTime) sttime.getValue()).asCalendar().getTime());
		if (endtime != null && endtime.getValue() != null)
			info.setEndTime(((XSDDateTime) endtime.getValue()).asCalendar().getTime());
		if (status != null && status.getValue() != null)
			info.setStatus(RuntimeInfo.Status.valueOf((String) status.getValue()));
		if (log != null && log.getValue() != null)
			info.setLog((String) log.getValue());
		return info;
	}
	
	private RuntimePlan setPlanError(RuntimePlan planexe, String message) {
	  planexe.getRuntimeInfo().addLog(message);
	  planexe.getRuntimeInfo().setStatus(RuntimeInfo.Status.FAILURE);
	  return planexe;
	}

  @Override
  public RuntimePlan rePlan(RuntimePlan planexe) {
    WorkflowGenerationAPI wg = new WorkflowGenerationKB(props,
        DataFactory.getReasoningAPI(props), ComponentFactory.getReasoningAPI(props),
        ResourceFactory.getAPI(props), planexe.getID());
    TemplateCreationAPI tc = TemplateFactory.getCreationAPI(props);

    Template seedtpl = tc.getTemplate(planexe.getSeededTemplateID());
    Template itpl = wg.getInferredTemplate(seedtpl);
    ArrayList<Template> candidates = wg.specializeTemplates(itpl);
    if(candidates.size() == 0) 
      return this.setPlanError(planexe, 
          "No Specialized templates after planning");

    ArrayList<Template> bts = new ArrayList<Template>();
    for(Template t : candidates)
      bts.addAll(wg.selectInputDataObjects(t));
    if(bts.size() == 0) 
      return this.setPlanError(planexe, 
          "No Bound templates after planning");

    wg.setDataMetricsForInputDataObjects(bts);

    ArrayList<Template> cts = new ArrayList<Template>();
    for(Template bt : bts)
      cts.addAll(wg.configureTemplates(bt));
    if(cts.size() == 0)
      return this.setPlanError(planexe, 
          "No Configured templates after planning");

    ArrayList<Template> ets = new ArrayList<Template>();
    for(Template ct : cts)
      ets.add(wg.getExpandedTemplate(ct));
    if(ets.size() == 0)
      return this.setPlanError(planexe, 
          "No Expanded templates after planning");

    // TODO: Should show all options to the user. Picking the top one for now
    Template xtpl = ets.get(0);
    xtpl.autoLayout();
    
    String xpid = planexe.getExpandedTemplateID();

    // Delete the existing expanded template
    this.deleteGraph(xpid);
    // Save the new expanded template
    if (!xtpl.saveAs(xpid)) {
      return this.setPlanError(planexe, 
          "Could not save new Expanded template");
    }
    xtpl = tc.getTemplate(xpid);

    String ppid = planexe.getPlan().getID();
    ExecutionPlan newplan = wg.getExecutionPlan(xtpl);
    if(newplan != null) {
      // Delete the existing plan
      this.deleteGraph(ppid);
      // Save the new plan
      if(!newplan.saveAs(ppid)) {
        return this.setPlanError(planexe, 
            "Could not save new Plan");
      }
      newplan.setID(ppid);

      // Get the new runtime plan
      RuntimePlan newexe = new RuntimePlan(newplan);

      // Update the current plan executable with the new plan
      planexe.setPlan(newplan);

      // Hash steps from current queue
      HashMap<String, RuntimeStep>
      stepMap = new HashMap<String, RuntimeStep>();
      for(RuntimeStep step : planexe.getQueue().getAllSteps())
        stepMap.put(step.getID(), step);

      // Add new steps to the current queue
      boolean newsteps = false;
      for(RuntimeStep newstep : newexe.getQueue().getAllSteps()) {
        // Add steps not already in current queue
        if(!stepMap.containsKey(newstep.getID())) {
          newsteps = true;

          // Set runtime plan
          newstep.setRuntimePlan(planexe);

          // Set parents
          @SuppressWarnings("unchecked")
          ArrayList<RuntimeStep> parents = 
          (ArrayList<RuntimeStep>) newstep.getParents().clone();
          newstep.getParents().clear();
          for(RuntimeStep pstep : parents) {
            if(stepMap.containsKey(pstep.getID()))
              pstep = stepMap.get(pstep.getID());
            newstep.addParent(pstep);
          }

          // Add new step to queue
          planexe.getQueue().addStep(newstep);
        }
      }

      if(newsteps)
        return planexe;
      else {
        return this.setPlanError(planexe, 
            "No new steps in the new execution plan");
      }
    }
    else {
      return this.setPlanError(planexe, 
          "Could not get a new Execution Plan");
    }
  }
}
