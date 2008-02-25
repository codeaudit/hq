/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004-2008], Hyperic, Inc.
 * This file is part of HQ.
 * 
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.measurement.server.session;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.appdef.server.session.ConfigManagerEJBImpl;
import org.hyperic.hq.appdef.server.session.Platform;
import org.hyperic.hq.appdef.server.session.ResourceCreatedZevent;
import org.hyperic.hq.appdef.server.session.ResourceRefreshZevent;
import org.hyperic.hq.appdef.server.session.ResourceZevent;
import org.hyperic.hq.appdef.server.session.Server;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityValue;
import org.hyperic.hq.appdef.shared.ConfigFetchException;
import org.hyperic.hq.appdef.shared.ConfigManagerLocal;
import org.hyperic.hq.appdef.shared.InvalidConfigException;
import org.hyperic.hq.application.HQApp;
import org.hyperic.hq.application.TransactionListener;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.server.session.AuthzSubjectManagerEJBImpl;
import org.hyperic.hq.authz.server.session.Resource;
import org.hyperic.hq.authz.server.session.ResourceManagerEJBImpl;
import org.hyperic.hq.authz.shared.AuthzSubjectManagerLocal;
import org.hyperic.hq.authz.shared.AuthzSubjectValue;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.ResourceManagerLocal;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.util.Messenger;
import org.hyperic.hq.measurement.EvaluationException;
import org.hyperic.hq.measurement.MeasurementConstants;
import org.hyperic.hq.measurement.MeasurementCreateException;
import org.hyperic.hq.measurement.MeasurementNotFoundException;
import org.hyperic.hq.measurement.MeasurementUnscheduleException;
import org.hyperic.hq.measurement.TemplateNotFoundException;
import org.hyperic.hq.measurement.ext.DownMetricValue;
import org.hyperic.hq.measurement.ext.depgraph.DerivedNode;
import org.hyperic.hq.measurement.ext.depgraph.Graph;
import org.hyperic.hq.measurement.ext.depgraph.InvalidGraphException;
import org.hyperic.hq.measurement.ext.depgraph.Node;
import org.hyperic.hq.measurement.ext.depgraph.RawNode;
import org.hyperic.hq.measurement.monitor.LiveMeasurementException;
import org.hyperic.hq.measurement.shared.CacheEntry;
import org.hyperic.hq.measurement.shared.DataManagerLocal;
import org.hyperic.hq.measurement.shared.DerivedMeasurementManagerLocal;
import org.hyperic.hq.measurement.shared.DerivedMeasurementManagerUtil;
import org.hyperic.hq.measurement.shared.RawMeasurementManagerLocal;
import org.hyperic.hq.measurement.shared.TrackerManagerLocal;
import org.hyperic.hq.measurement.server.session.AgentScheduleSynchronizer;
import org.hyperic.hq.measurement.server.session.DerivedMeasurement;
import org.hyperic.hq.product.Metric;
import org.hyperic.hq.product.MetricValue;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.zevents.ZeventManager;
import org.hyperic.util.StringUtil;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.pager.PageList;
import org.hyperic.util.pager.Pager;
import org.hyperic.util.timer.StopWatch;
import org.quartz.SchedulerException;

/** The DerivedMeasurementManagerEJB class is a stateless session bean that can
 * be used to interact with DerivedMeasurement EJB's
 *
 * @ejb:bean name="DerivedMeasurementManager"
 *      jndi-name="ejb/measurement/DerivedMeasurementManager"
 *      local-jndi-name="LocalDerivedMeasurementManager"
 *      view-type="local"
 *      type="Stateless"
 * 
 * @ejb:transaction type="REQUIRED"
 */
public class DerivedMeasurementManagerEJBImpl extends SessionEJB
    implements SessionBean 
{
    private final Log log =
        LogFactory.getLog(DerivedMeasurementManagerEJBImpl.class);

    protected final String VALUE_PROCESSOR =
        PagerProcessor_measurement.class.getName();
     
    private Pager valuePager = null;

    private RawMeasurementManagerLocal getRmMan() {
        return RawMeasurementManagerEJBImpl.getOne();
    }

    private DerivedMeasurement updateMeasurementInterval(Integer tid,
                                                         Integer iid,
                                                         long interval)
        throws FinderException
    {
        DerivedMeasurement m =
            getDerivedMeasurementDAO().findByTemplateForInstance(tid, iid);
        if (m == null) {
            // Fix me
            throw new FinderException();
        }

        m.setEnabled(interval != 0);
        m.setInterval(interval);

        enqueueZeventForMeasScheduleChange(m, interval);

        return m;
    }
    
    /**
     * Enqueue a {@link MeasurementScheduleZevent} on the zevent queue 
     * corresponding to the change in schedule for the derived measurement.
     * 
     * @param dm The derived measurement.
     * @param interval The new collection interval.
     */
    private void enqueueZeventForMeasScheduleChange(DerivedMeasurement dm, 
                                                    long interval) {
        
        MeasurementScheduleZevent event =
            new MeasurementScheduleZevent(dm.getId().intValue(), interval);
        ZeventManager.getInstance().enqueueEventAfterCommit(event);
    }
    
    /**
     * Enqueue a {@link MeasurementScheduleZevent} on the zevent queue 
     * corresponding to collection disabled for the derived measurements.
     * 
     * @param mids The derived measurement ids.
     */
    private void enqueueZeventsForMeasScheduleCollectionDisabled(Integer[] mids) {
        List events = new ArrayList(mids.length);
        
        for (int i = 0; i < mids.length; i++) {
            Integer mid = mids[i];
            
            if (mid != null) {
                events.add(new MeasurementScheduleZevent(mid.intValue(), 0));                
            }
        }
        
        ZeventManager.getInstance().enqueueEventsAfterCommit(events);
    }

    private Integer getRawIdByTemplateAndInstance(Integer tid, Integer iid) {
        RawMeasurement m =
            getRawMeasurementDAO().findByTemplateForInstance(tid, iid);
        if (m == null) {
            return null;
        }
        return m.getId();
    }

    private RawMeasurement createRawMeasurement(Integer instanceId, 
                                                Integer templateId,
                                                ConfigResponse props)
        throws MeasurementCreateException {
        return getRmMan().createMeasurement(templateId, instanceId, props);
    }

    private DerivedMeasurement createDerivedMeasurement(Resource instanceId,
                                                        MeasurementTemplate mt,
                                                        long interval)
        throws MeasurementCreateException
    {
        return getDerivedMeasurementDAO().create(instanceId, mt, interval);
    }

    /**
     * Look up a derived measurement's appdef entity ID
     */
    private AppdefEntityID getAppdefEntityId(DerivedMeasurement dm) {
        return new AppdefEntityID(dm.getAppdefType(), dm.getInstanceId());
    }

    private void sendAgentSchedule(final Serializable obj) {

        // Sending of the agent schedule should only occur once the current
        // transaction is completed.
        try {
            HQApp.getInstance().addTransactionListener(new TransactionListener() {

                public void beforeCommit() {
                }

                public void afterCommit(boolean success) {
                    if (obj != null) {
                        Messenger sender = new Messenger();
                        sender.sendMessage("queue/agentScheduleQueue", obj);
                    }
                }
            });
        } catch (Throwable t) {
            log.error("Unable to send agent schedule post commit", t);
        }
    }
    
    private void unscheduleJobs(Integer[] mids) {
        MetricDataCache cache = MetricDataCache.getInstance();
        for (int i = 0; i < mids.length; i++) {
            cache.remove(mids[i]);
            
            // Remove the job
            String jobName =
                CalculateDerivedMeasurementJob.getJobName(mids[i]);
            try {
                Object job = getScheduler().getJobDetail
                    (jobName, CalculateDerivedMeasurementJob.SCHEDULER_GROUP);

                if (job != null) {
                    getScheduler().deleteJob(
                        jobName,
                        CalculateDerivedMeasurementJob.SCHEDULER_GROUP);
                }
            } catch (SchedulerException e) {
                log.debug("No job for " + jobName);
            }

            // Remove the schedule
            String schedName =
                CalculateDerivedMeasurementJob.getScheduleName(mids[i]);
            try {
                Object schedule = getScheduler().getTrigger
                    (schedName, CalculateDerivedMeasurementJob.SCHEDULER_GROUP);

                if (null != schedule) {
                    getScheduler().unscheduleJob(
                        schedName,
                        CalculateDerivedMeasurementJob.SCHEDULER_GROUP);
                }
            } catch (SchedulerException e) {
                log.debug("No schedule for " + schedName);
            }
        }
    }

    /**
     * Create Measurement objects based their templates
     *
     * @param templates   List of Integer template IDs to add
     * @param id          instance ID (appdef resource) the templates are for
     * @param intervals   Millisecond interval that the measurement is polled
     * @param props       Configuration data for the instance
     *
     * @return a List of the associated DerivedMeasurement objects
     * @ejb:transaction type="REQUIRESNEW"
     * @ejb:interface-method
     */
    public List createMeasurements(AppdefEntityID id, Integer[] templates,
                                   long[] intervals, ConfigResponse props)
        throws MeasurementCreateException, TemplateNotFoundException
    {
        Integer instanceId = id.getId();
        
        // Look up the Resource
        Resource resource = getResource(id);
        
        ArrayList dmList   = new ArrayList();

        if(intervals.length != templates.length){
            throw new IllegalArgumentException("The templates and intervals " +
                                               " lists must be the same size");
        }

        try {
            for (int i = 0; i < templates.length; i++) {
                Integer dTemplateId = templates[i];
                long interval = intervals[i];

                Graph graph = GraphBuilder.buildGraph(dTemplateId);

                DerivedNode derivedNode = (DerivedNode)
                    graph.getNode( dTemplateId.intValue() );
                MeasurementTemplate derivedTemplateValue =
                    derivedNode.getMeasurementTemplate();

                // we will fill this variable with the actual derived 
                // measurement that is being enabled
                DerivedMeasurement argDm = null;
    
                // first handle simple IDENTITY derived case
                if (MeasurementConstants.TEMPL_IDENTITY.
                    equals(derivedTemplateValue.getTemplate()))
                {
                    RawNode rawNode = (RawNode)
                        derivedNode.getOutgoing().iterator().next();
                    MeasurementTemplate rawTemplateValue =
                        rawNode.getMeasurementTemplate();

                    // Check the raw node
                    Integer rmId =
                        getRawIdByTemplateAndInstance(rawTemplateValue.getId(),
                                                      instanceId);
                    if (rmId == null) {
                        if (props == null) {
                            // No properties, go on to the next template
                            continue;
                        }

                        createRawMeasurement(instanceId,
                                             rawTemplateValue.getId(),
                                             props);
                    }
                    else {
                        try {
                            argDm = updateMeasurementInterval(dTemplateId,
                                                              instanceId,
                                                              interval);
                        } catch (FinderException e) {
                            // Create the derived metric
                        }
                    }
                    
                    if (argDm == null) {
                        MonitorableType monTypeVal =
                            derivedTemplateValue.getMonitorableType();

                        if(monTypeVal.getAppdefType() != id.getType()) {
                            throw new MeasurementCreateException(
                                "Appdef entity (" + id + ")/template type (ID: "
                                + derivedTemplateValue.getId() + ") mismatch");
                        }

                        argDm = createDerivedMeasurement(resource,
                                                         derivedTemplateValue,
                                                         interval);
                    }
                        
                } else {
                    // we're not an identity DM template, so we need
                    // to make sure that measurements are enabled for
                    // the whole graph
                    for (Iterator graphNodes = graph.getNodes().iterator();
                         graphNodes.hasNext();) {
                        Node node = (Node)graphNodes.next();
                        MeasurementTemplate templArg =
                            node.getMeasurementTemplate();
    
                        if (node instanceof DerivedNode) {
                            DerivedMeasurement dm;
                            try {
                                dm = updateMeasurementInterval(templArg.getId(),
                                                               instanceId,
                                                               interval);
                            } catch (FinderException e) {
                                dm = createDerivedMeasurement(resource,
                                                              templArg,
                                                              interval);
                            }

                            if (dTemplateId.equals(templArg.getId())) {
                                argDm = dm;
                            }
                        } else {
                            // we are a raw node
                            Integer rmId =
                                getRawIdByTemplateAndInstance(templArg.getId(), 
                                                              instanceId);
    
                            if (rmId == null) {
                                createRawMeasurement(instanceId,
                                                     templArg.getId(),
                                                     props);
                            }
                        }
                    }
                }

                dmList.add(argDm);
            }
        } catch (InvalidGraphException e) {
            throw new MeasurementCreateException("InvalidGraphException:", e);
        } finally {
            // Force a flush to ensure the metrics are stored
            getDerivedMeasurementDAO().getSession().flush();
        }
        return dmList;
    }

    /**
     * @ejb:interface-method
     */
    public List createMeasurements(AuthzSubject subject, AppdefEntityID id,
                                   Integer[] templates, long[] intervals,
                                   ConfigResponse props)
        throws PermissionException, MeasurementCreateException,
               TemplateNotFoundException
    {
        // Authz check
        super.checkModifyPermission(subject.getId(), id);        

        // Call back into ourselves to force a new transaction to be created.
        List dmList = getOne().createMeasurements(id, templates, intervals,
                                                  props);
        sendAgentSchedule(id);
        return dmList;
    }

    /**
     * Create Measurement objects based their templates and default intervals
     *
     * @param templates   List of Integer template IDs to add
     * @param id          instance ID (appdef resource) the templates are for
     * @param props       Configuration data for the instance
     *
     * @return a List of the associated DerivedMeasurementValue objects
     * @ejb:interface-method
     */
    public List createMeasurements(AuthzSubject subject, 
                                   AppdefEntityID id, Integer[] templates,
                                   ConfigResponse props)
        throws PermissionException, MeasurementCreateException,
               TemplateNotFoundException {
        long[] intervals = new long[templates.length];
        for (int i = 0; i < templates.length; i++) {
            MeasurementTemplate tmpl =
                getMeasurementTemplateDAO().findById(templates[i]);
            intervals[i] = tmpl.getDefaultInterval();
        }
        
        return createMeasurements(subject, id, templates, intervals,props);
    }

    /**
     * Create Measurement objects for an appdef entity based on default
     * templates.  This method will only create them if there currently no
     * metrics enabled for the appdef entity.
     *
     * @param subject     Spider subject
     * @param id          appdef entity ID of the resource
     * @param mtype       The string name of the plugin type
     * @param props       Configuration data for the instance
     *
     * @return a List of the associated DerivedMeasurementValue objects
     */
    private List createDefaultMeasurements(AuthzSubject subject,
                                          AppdefEntityID id,
                                          String mtype,
                                          ConfigResponse props)
        throws TemplateNotFoundException, PermissionException,
               MeasurementCreateException {
        // We're going to make sure there aren't metrics already
        List dms = findMeasurements(subject, id, null, PageControl.PAGE_ALL);

        // Find the templates
        Collection mts =
            getMeasurementTemplateDAO().findTemplatesByMonitorableType(mtype);

        if (dms.size() != 0 && dms.size() == mts.size()) {
            return dms;
        }

        Integer[] tids = new Integer[mts.size()];
        long[] intervals = new long[mts.size()];

        Iterator it = mts.iterator();
        for (int i = 0; it.hasNext(); i++) {
            MeasurementTemplate tmpl = (MeasurementTemplate)it.next();
            tids[i] = tmpl.getId();

            if (tmpl.isDefaultOn())
                intervals[i] = tmpl.getDefaultInterval();
            else
                intervals[i] = 0;
        }

        return getOne().createMeasurements(subject, id, tids, intervals, props);
    }

    /**
     * Update the derived measurements of a resource
     * 
     * @ejb:interface-method
     */
    public void updateMeasurements(AuthzSubject subject,
                                   AppdefEntityID id, ConfigResponse props)
        throws PermissionException, MeasurementCreateException
    {
        // Update all of the raw measurements first
        try {
            getRmMan().updateMeasurements(id, props);
            
            // Now see which derived measurements need to be rescheduled
            List mcol = getDerivedMeasurementDAO()
                .findEnabledByInstance(getResource(id));

            Integer[] templates = new Integer[mcol.size()];
            long[] intervals = new long[mcol.size()];
            int idx = 0;
            for (Iterator i = mcol.iterator(); i.hasNext(); idx++) {
                DerivedMeasurement dm = (DerivedMeasurement)i.next();
                templates[idx] = dm.getTemplate().getId();
                intervals[idx] = dm.getInterval();
            }
            createMeasurements(subject, id, templates, intervals, props);

        } catch (TemplateNotFoundException e) {
            // Would not happen since we're creating measurements with the
            // template that we just looked up
            log.error(e);
        }
    }

    /**
     * Remove all measurements no longer associated with a resource.
     *
     * @ejb:interface-method
     */
    public int removeOrphanedMeasurements() {
        StopWatch watch = new StopWatch();
        MetricDeleteCallback cb = 
            MeasurementStartupListener.getMetricDeleteCallbackObj();
        DerivedMeasurementDAO dao = getDerivedMeasurementDAO();
        List mids = dao.findOrphanedMeasurements();
        
        if (mids.size() > 0) {
            cb.beforeMetricsDelete(mids);
            getBaselineDAO().deleteByIds(mids);
            dao.deleteByIds(mids);
        }

        if (log.isDebugEnabled()) {
            log.debug("DerivedMeasurementManager.removeOrphanedMeasurements() "+
            		  watch);
        }
        return mids.size();
    }

    /** 
     * Look up a derived measurement for an instance and an alias
     * and an alias.
     *
     * @return a DerivedMeasurement value
     * @ejb:interface-method
     */
    public DerivedMeasurement getMeasurement(AuthzSubject subject,
                                             AppdefEntityID id,
                                             String alias)
        throws MeasurementNotFoundException {

        DerivedMeasurement m = 
            getDerivedMeasurementDAO().findByAliasAndID(alias, getResource(id));
        if (m == null) {
            throw new MeasurementNotFoundException(alias + " for " + id + 
                                                   " not found.");
        }

        return m;
    }

    /**
     * Look up a DerivedMeasurement by Id.
     * @ejb:interface-method
     */
    public DerivedMeasurement getMeasurement(Integer mid) {
        return getDerivedMeasurementDAO().get(mid);
    }

    /**
     * Get the live measurement values for a given resource.
     * @param id The id of the resource
     * @ejb:interface-method
     */
    public void getLiveMeasurementValues(AuthzSubjectValue subject,
                                         AppdefEntityID id)
        throws EvaluationException, PermissionException,
               LiveMeasurementException, MeasurementNotFoundException
    {
        List mcol = 
            getDerivedMeasurementDAO().findEnabledByInstance(getResource(id));
        Integer[] mids = new Integer[mcol.size()];
        Integer availMeasurement = null; // For insert of AVAIL down
        Iterator it = mcol.iterator();

        for (int i = 0; it.hasNext(); i++) {
            DerivedMeasurement dm = (DerivedMeasurement)it.next();
            mids[i] = dm.getId();
            
            MeasurementTemplate template = dm.getTemplate();

            if (template.getAlias().equals(Metric.ATTR_AVAIL)) {
                availMeasurement = dm.getId();
            }
        }

        log.info("Getting live measurements for " + mids.length +
                 " measurements");
        try {
            getLiveMeasurementValues(subject, mids);
        } catch (LiveMeasurementException e) {            
            log.info("Resource " + id + " reports it is unavailable, setting " +
                    "measurement ID " + availMeasurement + " to DOWN: "+e);

            // Only print the full stack trace in debug mode
            if (log.isDebugEnabled()) {
                log.error("Exception details: ", e);
            }

            if (availMeasurement != null) {
                MetricValue val =
                    new MetricValue(MeasurementConstants.AVAIL_DOWN);
                DataManagerLocal dataMan = getDataMan();
                dataMan.addData(availMeasurement, val, true);
            }
        }
    }

    /**
     * Get the live measurement value - assumes all measurement ID's share
     * the same agent connection
     * @param mids The array of metric id's to fetch
     * @ejb:interface-method
     */
    public MetricValue[] getLiveMeasurementValues(AuthzSubjectValue subject,
                                                  Integer[] mids)
        throws EvaluationException, PermissionException,
               LiveMeasurementException, MeasurementNotFoundException {
        try {
            DataManagerLocal dataMan = getDataMan();

            DerivedMeasurement[] dms = new DerivedMeasurement[mids.length];
            Integer[] identRawIds = new Integer[mids.length];
            Arrays.fill(identRawIds, null);
            
            HashSet rawIdSet = new HashSet();
            HashSet derIdSet = new HashSet();
            for (int i = 0; i < mids.length; i++) {
                // First, find the derived measurement
                dms[i] = getMeasurement(mids[i]);
                
                if (!dms[i].isEnabled())
                    throw new LiveMeasurementException("Metric ID: " +
                                                       mids[i] + 
                                                       " is not currently " +
                                                       "enabled");
                
                // Now get the IDs
                Integer[] metIds = getArgumentIds(dms[i]);

                if (dms[i].getFormula().equals(
                    MeasurementConstants.TEMPL_IDENTITY)) {
                    rawIdSet.add(metIds[0]);
                    identRawIds[i] = metIds[0];
                } else {
                    derIdSet.addAll(Arrays.asList(metIds));
                }
            }

            // Now look up the measurements            
            HashMap dataMap = new HashMap();
            
            // Get the raw measurements
            if (rawIdSet.size() > 0) {
                Integer[] rawIds = (Integer[])
                    rawIdSet.toArray(new Integer[rawIdSet.size()]);
                
                MetricValue[] vals =
                    getRmMan().getLiveMeasurementValues(rawIds);
                for (int i = 0; i < rawIds.length; i++) {
                    dataMap.put(rawIds[i], vals[i]);
                    // Add data to database
                    dataMan.addData(rawIds[i], vals[i], true); 
                }
            }
            
            // Get the derived measurements
            if (derIdSet.size() > 0) {
                Integer[] derIds = (Integer[])
                    derIdSet.toArray(new Integer[derIdSet.size()]);
                
                MetricValue[] vals = getLiveMeasurementValues(subject, derIds);
                for (int i = 0; i < derIds.length; i++) {
                    dataMap.put(derIds[i], vals[i]);
                }
            }

            MetricValue[] res = new MetricValue[dms.length];
            // Now go through each derived measurement and calculate the value
            for (int i = 0; i < dms.length; i++) {
                // If the template string consists of just RawMeasurement (ARG1)
                // then bypass the expression evaluation. Otherwise, evaluate.
                if (identRawIds[i] != null) {
                    res[i] = (MetricValue) dataMap.get(identRawIds[i]);
                    
                    if (res[i] == null) {
                        log.debug("Did not receive live value for " +
                                  identRawIds[i]);
                    }
                } else {
                    Double result = evaluateExpression(dms[i], dataMap);
                    res[i] = new MetricValue(result.doubleValue());
                }

                if (res[i] != null)
                    dataMan.addData(dms[i].getId(), res[i], true);
            }

            return res;
        } catch (FinderException e) {
            throw new MeasurementNotFoundException(
                StringUtil.arrayToString(mids), e);
        }
    }

    /**
     * Count of metrics enabled for a particular entity
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public int getEnabledMetricsCount(AuthzSubjectValue subject,
                                      AppdefEntityID id) {
        List mcol = 
            getDerivedMeasurementDAO().findEnabledByInstance(getResource(id));
        return mcol.size();
    }

    /**
     * Look up a derived measurement value for a resource
     *
     * @return a DerivedMeasurement value
     * @ejb:interface-method
     */
    public DerivedMeasurement findMeasurement(Integer tid, Integer iid)
        throws MeasurementNotFoundException {
        DerivedMeasurement dm =
            getDerivedMeasurementDAO().findByTemplateForInstance(tid, iid);
            
        if (dm == null) {
            throw new MeasurementNotFoundException("No measurement found " +
                                                   "for " + iid + " with " +
                                                   "template " + tid);
        }
        return dm;
    }

    /**
     * Look up a derived measurement POJO
     *
     * @return a DerivedMeasurement value
     * @ejb:interface-method
     */
    public DerivedMeasurement findMeasurement(AuthzSubject subject,
                                              Integer tid, Integer iid)
        throws MeasurementNotFoundException {
        DerivedMeasurement dm = findMeasurement(tid, iid);
            
        if (dm == null) {
            throw new MeasurementNotFoundException("No measurement found " +
                                                   "for " + iid + " with " +
                                                   "template " + tid);
        }
        return dm;
    }

    /**
     * Look up a derived measurement POJO, allowing for the query to return a 
     * stale copy of the derived measurement (for efficiency reasons).
     *
     * @param subject The subject.
     * @param tid The template Id.
     * @param iid The instance Id.
     * @param allowStale <code>true</code> to allow stale copies of an alert 
     *                   definition in the query results; <code>false</code> to 
     *                   never allow stale copies, potentially always forcing a 
     *                   sync with the database.
     * @return a DerivedMeasurement value
     * @ejb:interface-method
     */
    public DerivedMeasurement findMeasurement(AuthzSubjectValue subject,
                                              Integer tid, 
                                              Integer iid,
                                              boolean allowStale)
        throws MeasurementNotFoundException {
        
        DerivedMeasurement dm = getDerivedMeasurementDAO()
            .findByTemplateForInstance(tid, iid, allowStale);
            
        if (dm == null) {
            throw new MeasurementNotFoundException("No measurement found " +
                                                   "for " + iid + " with " +
                                                   "template " + tid);
        }
        
        return dm;
    }
        
    /**
     * Look up a list of derived measurement EJBs for a template and instances
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public List findMeasurements(AuthzSubject subject, Integer tid,
                                 Integer[] ids) {
        ArrayList results = new ArrayList();
        for (int i = 0; i < ids.length; i++) {
            try {
                results.add(findMeasurement(subject, tid, ids[i])); 
            } catch (MeasurementNotFoundException e) {
                continue;
            }
        }
        return results;
    }

    /**
     * Look up a list of derived measurement EJBs for a template and instances
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public Integer[] findMeasurementIds(AuthzSubject subject, Integer tid,
                                        Integer[] ids) {
        List results =
            getDerivedMeasurementDAO().findIdsByTemplateForInstances(tid, ids); 
        return (Integer[]) results.toArray(new Integer[results.size()]);
    }

    private List sortMetrics(List mcol, PageControl pc) {
        // Clearly, assuming that we are sorting by name, in the future we may
        // need to pay attention to the PageControl passed in if we sort by
        // more attributes
        if (pc.getSortorder() == PageControl.SORT_DESC) {
            Collections.sort(mcol, new Comparator() {
                
                public int compare(Object arg0, Object arg1) {
                    DerivedMeasurement dm0 = (DerivedMeasurement) arg0;
                    DerivedMeasurement dm1 = (DerivedMeasurement) arg1;
                    return dm1.getTemplate().getName()
                        .compareTo(dm0.getTemplate().getName());
                }
                
            });
        }
        else {
            Collections.sort(mcol, new Comparator() {
                
                public int compare(Object arg0, Object arg1) {
                    DerivedMeasurement dm0 = (DerivedMeasurement) arg0;
                    DerivedMeasurement dm1 = (DerivedMeasurement) arg1;
                    return dm0.getTemplate().getName()
                        .compareTo(dm1.getTemplate().getName());
                }
                
            });
        }
        
        return mcol;
    }
    
    /**
     * Look up a list of derived measurement EJBs for a category
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public PageList findMeasurements(AuthzSubject subject,
                                     AppdefEntityID id, String cat,
                                     PageControl pc) {
        List mcol;
            
        // See if category is valid
        if (cat == null || Arrays.binarySearch(
            MeasurementConstants.VALID_CATEGORIES, cat) < 0) {
            mcol = getDerivedMeasurementDAO()
                .findEnabledByInstance(getResource(id));
        } else {
            mcol = getDerivedMeasurementDAO()
                .findByInstanceForCategory(getResource(id), cat);
        }

        // Need to order the metrics, as the HQL does not allow us to order
        mcol = sortMetrics(mcol, pc);
    
        return valuePager.seek(mcol, pc);
    }

    /**
     * Look up a list of enabled derived measurements for a category
     *
     * @return a list of {@link DerivedMeasurement}
     * @ejb:interface-method
     */
    public List findEnabledMeasurements(AuthzSubjectValue subject,
                                        AppdefEntityID id, String cat) {
        List mcol;
            
        // See if category is valid
        if (cat == null || Arrays.binarySearch(
            MeasurementConstants.VALID_CATEGORIES, cat) < 0) {
            mcol = getDerivedMeasurementDAO()
                .findEnabledByInstance(getResource(id));
        } else {
            mcol = getDerivedMeasurementDAO().
                findByInstanceForCategory(getResource(id), cat);
        }
        return mcol;
    }

    /**
     * Look up a list of designated measurement EJBs for an entity
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public List findDesignatedMeasurements(AppdefEntityID id) {
        return getDerivedMeasurementDAO()
            .findDesignatedByInstance(getResource(id));
    }

    /**
     * Look up a list of designated measurement EJBs for an entity for
     * a category
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public List findDesignatedMeasurements(AuthzSubject subject,
                                           AppdefEntityID id, String cat) {
        return getDerivedMeasurementDAO()
            .findDesignatedByInstanceForCategory(getResource(id), cat);
    }

    private Cache getAvailabilityCache() {
        return CacheManager.getInstance().getCache("AvailabilitySummary");
    }

    private DerivedMeasurement findAvailabilityMetric(AppdefEntityID id)
        throws MeasurementNotFoundException {
        List mlocals = getDerivedMeasurementDAO().
            findDesignatedByInstanceForCategory(getResource(id),
            MeasurementConstants.CAT_AVAILABILITY);
        
        if (mlocals.size() == 0) {
            throw new MeasurementNotFoundException("No availability metric " +
                                                   "found for " + id);
        }
    
        DerivedMeasurement dm = (DerivedMeasurement) mlocals.get(0);
        CacheEntry entry = new CacheEntry(dm);
        getAvailabilityCache().put(new Element(id, entry));
        return dm;
    }

    /**
     * Look up an availability measurement EJBs for an instance
     * @throws MeasurementNotFoundException
     * @ejb:interface-method
     */
    public CacheEntry getAvailabilityCacheEntry(AuthzSubject subject,
                                                AppdefEntityID id) {
        Cache cache = getAvailabilityCache();
        Element elm = cache.get(id);

        if (elm != null) {
            return (CacheEntry) elm.getObjectValue();
        }
        
        try {
            DerivedMeasurement dm = findAvailabilityMetric(id);
            return new CacheEntry(dm);
        } catch (MeasurementNotFoundException e) {
            return null;
        }        
    }

    /**
     * Look up an availability measurement EJBs for an instance
     * @throws MeasurementNotFoundException
     * @ejb:interface-method
     */
    public DerivedMeasurement getAvailabilityMeasurement(AuthzSubject subject,
                                                         AppdefEntityID id)
        throws MeasurementNotFoundException
    {
        Element e = getAvailabilityCache().get(id);

        if (e != null) {
            CacheEntry entry = (CacheEntry) e.getObjectValue();
            return getDerivedMeasurementDAO().findById(entry.getMetricId());
        }
        
        return findAvailabilityMetric(id);
    }

    /**
     * Look up a list of DerivedMeasurement objects by category
     *
     * @ejb:interface-method
     */
    public List findMeasurementsByCategory(String cat)
    {
        return getDerivedMeasurementDAO().findByCategory(cat);
    }

    /**
     * Look up a list of derived measurement EJBs for a category
     *
     * @return a list of DerivedMeasurement value
     * @ejb:interface-method
     */
    public Map findDesignatedMeasurementIds(AuthzSubject subject,
                                            AppdefEntityID[] ids, String cat)
        throws MeasurementNotFoundException {

        Map midMap = new HashMap();
        if (ids.length == 0)
            return midMap;
        
        if (MeasurementConstants.CAT_AVAILABILITY.equals(cat)) {
            int type = ids[0].getType();
            
            List toget = new ArrayList();
            Cache cache = getAvailabilityCache();
            
            for (int i = 0; i < ids.length; i++) {
                Element e = cache.get(ids[i]);
                
                if (e != null) {
                    CacheEntry entry = (CacheEntry) e.getObjectValue();
                    midMap.put(ids[i], entry.getMetricId());
                    continue;
                }
                
                toget.add(ids[i].getId());
            }
            
            if (toget.size() > 0) {
                Integer[] iids =
                    (Integer[]) toget.toArray(new Integer[toget.size()]);

                List metrics = getDerivedMeasurementDAO()
                    .findAvailabilityByInstances(type, iids);
                for (Iterator it = metrics.iterator(); it.hasNext();) {
                    DerivedMeasurement dm = (DerivedMeasurement) it.next();
                    AppdefEntityID aeid =
                        new AppdefEntityID(type, dm.getInstanceId());

                    midMap.put(aeid, dm.getId());
                    CacheEntry res = new CacheEntry(dm);
                    cache.put(new Element(aeid, res));
                }
            }
        }
        else {
            for (int i = 0; i < ids.length; i++) {
                AppdefEntityID id = ids[i];
                try {
                    List metrics = getDerivedMeasurementDAO().
                        findDesignatedByInstanceForCategory(getResource(id),
                                                            cat);
    
                    if (metrics.size() == 0)
                        throw new FinderException("No metrics found");
                    
                    DerivedMeasurement dm = (DerivedMeasurement) metrics.get(0);    
                    midMap.put(id, dm.getId());
                } catch (FinderException e) {
                    // Throw an exception if we're only looking for one
                    // measurement
                    if (ids.length == 1)
                        throw new MeasurementNotFoundException(cat +
                                                               " metric for " +
                                                               id +
                                                               " not found");
                }
            }
        }
        return midMap;
    }

    /**
     * Look up a list of derived metric intervals for template IDs.
     *
     * @return a map keyed by template ID and values of metric intervals
     * There is no entry if a metric is disabled or does not exist for the
     * given entity or entities.  However, if there are multiple entities, and
     * the intervals differ or some enabled/not enabled, then the value will
     * be "0" to denote varying intervals.
     *
     * @ejb:interface-method
     */
    public Map findMetricIntervals(AuthzSubject subject, AppdefEntityID[] aeids,
                                   Integer[] tids) {
        final Long disabled = new Long(-1);
        DerivedMeasurementDAO ddao = getDerivedMeasurementDAO();
        Map intervals = new HashMap(tids.length);
        
        ResourceManagerLocal resMan = ResourceManagerEJBImpl.getOne();
        
        for (int ind = 0; ind < aeids.length; ind++) {
            Resource res = resMan.findResource(aeids[ind]);
            List metrics = ddao.findByInstance(res);

            for (Iterator i = metrics.iterator(); i.hasNext();)
            {
                DerivedMeasurement dm = (DerivedMeasurement) i.next();
                Long interval = new Long(dm.getInterval());

                if (!dm.isEnabled()) {
                    interval = disabled;
                }
                
                Integer templateId = dm.getTemplate().getId();
                Long previous = (Long) intervals.get(templateId);

                if (previous == null) {
                    intervals.put(templateId, interval);
                } else {
                    if (!previous.equals(interval)) {
                        intervals.put(templateId, new Long(0));
                    }
                }
            }
        }
        
        // Filter by template IDs, since we only pay attention to what was
        // passed, but may have more than that in our map.
        for (int i=0; i<tids.length; i++) {
            if (!intervals.containsKey(tids[i]))
                intervals.put(tids[i], null);
        }
        
        List tidList = Arrays.asList(tids);
        // Copy the keys, since we are going to be modifying the interval map
        Set keys = new HashSet(intervals.keySet());
        for (Iterator i = keys.iterator(); i.hasNext();) {
            Integer templateId = (Integer) i.next();

            if (tidList.indexOf(templateId) == -1 || // Wasn't asked for
                disabled.equals(intervals.get(templateId))) { // Disabled
                // so don't return it
                intervals.remove(templateId);
            }
        }

        return intervals;
    }

    /**
     * Set the interval of Measurements based their template ID's
     *
     * @ejb:interface-method
     */
    public void enableMeasurements(AuthzSubject subject, AppdefEntityID[] aeids,
                                   Integer[] mtids, long interval)
        throws MeasurementNotFoundException, MeasurementCreateException,
               TemplateNotFoundException, PermissionException {

        DerivedMeasurementDAO dao = getDerivedMeasurementDAO();
        // Create a list of IDs
        Integer[] iids = new Integer[aeids.length];
        for (int i = 0; i < aeids.length; i++) {
            iids[i] = aeids[i].getId();
        }
        
        List mids = new ArrayList(aeids.length * mtids.length);
        for (int i = 0; i < mtids.length; i++) {
            mids.addAll(dao.findIdsByTemplateForInstances(mtids[i], iids));
        }

        // Do the update in bulk
        dao.updateInterval(mids, interval);
        
        // Update the agent schedule
        for (int i = 0; i < aeids.length; i++) {
            sendAgentSchedule(aeids[i]);
        }
    } 

    /**
     * Disable all measurements for the given resources.
     *
     * @param agentId The entity id to use to look up the agent connection
     * @param ids The list of entitys to unschedule
     * @ejb:interface-method
     *
     * NOTE: This method requires all entity ids to be monitored by the same
     * agent as specified by the agentId
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID agentId,
                                    AppdefEntityID[] ids)
        throws PermissionException {

        DerivedMeasurementDAO dao = getDerivedMeasurementDAO();
        for (int i = 0; i < ids.length; i++) {
            checkModifyPermission(subject.getId(), ids[i]);

            List mcol = dao.findEnabledByInstance(getResource(ids[i]));
            
            Integer[] mids = new Integer[mcol.size()];
            Iterator it = mcol.iterator();
            for (int j = 0; it.hasNext(); j++) {
                DerivedMeasurement dm = (DerivedMeasurement) it.next();
                dm.setEnabled(false);
                mids[j] = dm.getId();
            }

            // Now unschedule the DerivedMeasurment
            unscheduleJobs(mids);
            
            enqueueZeventsForMeasScheduleCollectionDisabled(mids);
        }

        // Unscheduling of all metrics for a resource could indicate that
        // the resource is getting removed.  Send the unschedule synchronously
        // so that all the necessary plumbing is in place.
        try {
            MeasurementProcessorEJBImpl.getOne().unschedule(agentId, ids);
        } catch (MeasurementUnscheduleException e) {
            log.error("Unable to disable measurements", e);           
        }
    }

    /**
     * Disable all derived measurement's for a resource
     *
     * @ejb:interface-method
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID id)
        throws PermissionException {
        // Authz check
        checkModifyPermission(subject.getId(), id);        

        List mcol =
            getDerivedMeasurementDAO().findEnabledByInstance(getResource(id));
        Integer[] mids = new Integer[mcol.size()];
        Iterator it = mcol.iterator();
        for (int i = 0; it.hasNext(); i++) {
            DerivedMeasurement dm = (DerivedMeasurement)it.next();
            dm.setEnabled(false);
            mids[i] = dm.getId();
        }

        // Now unschedule the DerivedMeasurment
        unscheduleJobs(mids);
        
        enqueueZeventsForMeasScheduleCollectionDisabled(mids);

        // Unscheduling of all metrics for a resource could indicate that
        // the resource is getting removed.  Send the unschedule synchronously
        // so that all the necessary plumbing is in place.
        try {
            MeasurementProcessorEJBImpl.getOne().unschedule(id);
        } catch (MeasurementUnscheduleException e) {
            log.error("Unable to disable measurements", e);
        }
    }

    /**
     * Disable all derived measurements for an instance
     *
     * @ejb:interface-method
     */
    public void disableMeasurements(AuthzSubject subject, Integer[] mids)
        throws PermissionException, MeasurementNotFoundException {
        AppdefEntityID aid = null;
        for (int i = 0; i < mids.length; i++) {
            DerivedMeasurement m = 
                getDerivedMeasurementDAO().findById(mids[i]);

            if (m == null) {
                throw new MeasurementNotFoundException("Measurement id " +
                                                       mids[i] + " not " +
                                                       "found");
            }

            // Check removal permission
            if (aid == null) {
                aid = getAppdefEntityId(m);
                checkModifyPermission(subject.getId(), aid);
            }
            m.setEnabled(false);
        }

        // Now unschedule the DerivedMeasurment
        unscheduleJobs(mids);
        
        enqueueZeventsForMeasScheduleCollectionDisabled(mids);
        
        sendAgentSchedule(aid);
    }

    /**
     * Disable measurements for an instance
     *
     * @ejb:interface-method
     */
    public void disableMeasurements(AuthzSubject subject, AppdefEntityID id,
                                    Integer[] tids)
        throws PermissionException {
        // Authz check
        checkModifyPermission(subject.getId(), id);
        
        Resource resource = getResource(id);
        List mcol = getDerivedMeasurementDAO().findByInstance(resource);
        HashSet tidSet = null;
        if (tids != null) {
            tidSet = new HashSet(Arrays.asList(tids));
        }            
        
        List toUnschedule = new ArrayList();
        for (Iterator it = mcol.iterator(); it.hasNext(); ) {
            DerivedMeasurement dm = (DerivedMeasurement)it.next();
            // Check to see if we need to remove this one
            if (tidSet != null && 
                !tidSet.contains(dm.getTemplate().getId()))
                    continue;

            dm.setEnabled(false);
            toUnschedule.add(dm.getId());
        }

        // Now unschedule the DerivedMeasurment
        Integer[] mids = 
            (Integer[])toUnschedule.toArray(new Integer[toUnschedule.size()]);
        
        unscheduleJobs(mids);
        
        enqueueZeventsForMeasScheduleCollectionDisabled(mids);
        
        sendAgentSchedule(id);
    }

    private Resource getResource(AppdefEntityID id) {
        return ResourceManagerEJBImpl.getOne().findResource(id);
    }

    /**
     * @ejb:interface-method
     */
    public int getNumUnavailEntities() {
        return MetricDataCache.getInstance().getUnavailableMetricsSize();
    }
    
    /**
     * Get the list of DownMetricValues that represent the resources that are
     * currently down
     * 
     * @ejb:interface-method
     */
    public List getUnavailEntities(List includes) {
        MetricDataCache cache = MetricDataCache.getInstance();
        Map unavailMetrics = cache.getUnavailableMetrics();
        List unavailEntities = new ArrayList();
        DerivedMeasurementDAO dao = getDerivedMeasurementDAO();
        for (Iterator it = unavailMetrics.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry el = (Map.Entry) it.next();
            Integer mid = (Integer) el.getKey();
            
            if (includes != null && !includes.contains(mid)) {
                continue;
            }
            
            MetricValue mv = (MetricValue) el.getValue();
            
            // Look up the metric for the appdef entity ID
            DerivedMeasurement dm = dao.get(mid);
            
            if (dm == null) {
                cache.remove(mid);
                continue;
            }
            
            unavailEntities.add(new DownMetricValue(dm.getEntityId(), mid, mv));
        }
        return unavailEntities;
    }
    
    /**
     * @ejb:interface-method
     * @ejb:transaction type="NOTSUPPORTED"
     */
    public void syncPluginMetrics(String plugin) {
        List entities =
            getDerivedMeasurementDAO().findMetricsCountMismatch(plugin);
        
        AuthzSubject overlord =
            AuthzSubjectManagerEJBImpl.getOne().getOverlordPojo();
        
        for (Iterator it = entities.iterator(); it.hasNext(); ) {
            Object[] vals = (Object[]) it.next();
            
            java.lang.Number type = (java.lang.Number) vals[0];
            java.lang.Number id = (java.lang.Number) vals[1];
            AppdefEntityID aeid =
                new AppdefEntityID(type.intValue(), id.intValue());

            try {
                log.info("syncPluginMetrics sync'ing metrics for " + aeid);
                enableDefaultMetrics(overlord, aeid, false);
            } catch (AppdefEntityNotFoundException e) {
                // Move on since we did this query based on measurement table
                // not resource table
            } catch (PermissionException e) {
                // Quite impossible
                assert(false);
            }
        }
    }
    
    /**
     * Gets a summary of the metrics which are scheduled for collection, 
     * across all resource types and metrics.
     * 
     * @return a list of {@link CollectionSummary} beans
     * @ejb:interface-method
     */
    public List findMetricCountSummaries() {
        return getDerivedMeasurementDAO().findMetricCountSummaries();
    }
    
    /**
     * Find a list of tuples (of size 4) consisting of 
     *   the {@link Agent}
     *   the {@link Platform} it manages 
     *   the {@link Server} representing the Agent
     *   the {@link DerivedMeasurement} that contains the Server Offset value
     * 
     * @ejb:interface-method
     */
    public List findAgentOffsetTuples() {
        return getDerivedMeasurementDAO().findAgentOffsetTuples();
    }
    
    /**
     * Get the # of metrics that each agent is collecting.
     * 
     * @return a map of {@link Agent} onto Longs indicating how many metrics
     *         that agent is collecting. 
     * @ejb:interface-method
     */
    public Map findNumMetricsPerAgent() {
        return getDerivedMeasurementDAO().findNumMetricsPerAgent();
    }

    /**
     * Handle events from the {@link MeasurementEnabler}.  This method
     * is required to place the operation within a transaction (and session)
     * 
     * @ejb:interface-method
     */
    public void handleCreateRefreshEvents(List events) {
        ConfigManagerLocal cm = ConfigManagerEJBImpl.getOne();
        TrackerManagerLocal tm = TrackerManagerEJBImpl.getOne();
        AuthzSubjectManagerLocal aman = AuthzSubjectManagerEJBImpl.getOne();
        
        for (Iterator i=events.iterator(); i.hasNext(); ) {
            ResourceZevent z = (ResourceZevent)i.next();
            AuthzSubjectValue subject = z.getAuthzSubjectValue();
            AppdefEntityID id = z.getAppdefEntityID();
            boolean isCreate, isRefresh;
    
            isCreate = z instanceof ResourceCreatedZevent;
            isRefresh = z instanceof ResourceRefreshZevent;
    
            try {
                // Handle reschedules for when agents are updated.
                if (isRefresh) {
                    log.info("Refreshing metric schedule for [" + id + "]");
                    AgentScheduleSynchronizer.scheduleBuffered(id);
                    continue;
                }
    
                // For either create or update events, schedule the default
                // metrics
                if (getEnabledMetricsCount(subject, id) == 0) {
                    log.info("Enabling default metrics for [" + id + "]");
                    AuthzSubject subj = aman.findSubjectById(subject.getId());
                    enableDefaultMetrics(subj, id, true);
                }
    
                if (isCreate) {
                    // On initial creation of the service check if log or config
                    // tracking is enabled.  If so, enable it.  We don't auto
                    // enable log or config tracking for update events since
                    // in the callback we don't know if that flag has changed.
                    ConfigResponse c =
                        cm.getMergedConfigResponse(subject,
                                                   ProductPlugin.TYPE_MEASUREMENT,
                                                   id, true);
                    tm.enableTrackers(subject, id, c);
                }
    
            } catch (ConfigFetchException e) {
                log.debug("Config not set for [" + id + "]", e);
            } catch(Exception e) {
                log.warn("Unable to enable default metrics for [" + id + "]", e);
            }
        }
    }

    /**
     * Enable the default metrics for a resource.  This should only
     * be called by the {@link MeasurementEnabler}.  If you want the behavior
     * of this method, use the {@link MeasurementEnabler} 
     */
    private void enableDefaultMetrics(AuthzSubject subj, 
                                     AppdefEntityID id, boolean verify) 
        throws AppdefEntityNotFoundException, PermissionException 
    {
        ConfigManagerLocal cfgMan = ConfigManagerEJBImpl.getOne();
        ConfigResponse config;
        String mtype;
    
        AuthzSubjectValue subject = subj.getAuthzSubjectValue();
        try {
            if (id.isPlatform() || id.isServer() | id.isService()) {
                AppdefEntityValue av = new AppdefEntityValue(id, subject);
                try {
                    mtype = av.getMonitorableType();
                } catch (AppdefEntityNotFoundException e) {
                    // Non existent resource, we'll clean it up in
                    // removeOrphanedMeasurements()
                    return;
                }
            }
            else {
                return;
            }
    
            config = 
                cfgMan.getMergedConfigResponse(subject,
                                               ProductPlugin.TYPE_MEASUREMENT,
                                               id, true);
        } catch (ConfigFetchException e) {
            log.debug("Unable to enable default metrics for [" + id + "]", e);
            return;
        }  catch (Exception e) {
            log.error("Unable to enable default metrics for [" + id + "]", e);
            return;
        }
    
        // Check the configuration
        if (verify) {
            try {
                getRmMan().checkConfiguration(subj, id, config);
            } catch (InvalidConfigException e) {
                log.warn("Error turning on default metrics, configuration (" +
                          config + ") " + "couldn't be validated", e);
                cfgMan.setValidationError(subject, id, e.getMessage());
                return;
            } catch (Exception e) {
                log.warn("Error turning on default metrics, " +
                          "error in validation", e);
                cfgMan.setValidationError(subject, id, e.getMessage());
                return;
            }
        }
    
        // Enable the metrics
        try {
            createDefaultMeasurements(subj, id, mtype, config);
            cfgMan.clearValidationError(subject, id);
    
            // Execute the callback so other people can do things when the
            // metrics have been created (like create type-based alerts)
            MeasurementStartupListener.getDefaultEnableObj().metricsEnabled(id);
        } catch (Exception e) {
            log.warn("Unable to enable default metrics for id=" + id +
                      ": " + e.getMessage(), e);
        }
    }

    public static DerivedMeasurementManagerLocal getOne() {
        try {
            return DerivedMeasurementManagerUtil.getLocalHome().create();
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

    /**
     * @ejb:create-method
     */
    public void ejbCreate() throws CreateException {
        try {
            valuePager = Pager.getPager(VALUE_PROCESSOR);
        } catch (Exception e) {
            throw new CreateException("Could not create value pager:" + e);
        }
    }

    public void ejbPostCreate() {}
    public void ejbActivate() {}
    public void ejbPassivate() {}
    public void ejbRemove() {}
    public void setSessionContext(SessionContext ctx){}
}
