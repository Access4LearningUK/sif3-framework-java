/*
 * BaseEventProvider.java
 * Created: 19/03/2014
 *
 * Copyright 2014 Systemic Pty Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License 
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package sif3.infra.rest.provider;

import java.util.List;

import sif3.common.CommonConstants;
import sif3.common.header.HeaderValues.EventAction;
import sif3.common.header.HeaderValues.ServiceType;
import sif3.common.interfaces.EventProvider;
import sif3.common.interfaces.SIFEventIterator;
import sif3.common.model.SIFContext;
import sif3.common.model.SIFEvent;
import sif3.common.model.SIFZone;
import sif3.common.model.ServiceInfo;
import sif3.common.model.ServiceRights.AccessRight;
import sif3.common.model.ServiceRights.AccessType;
import sif3.common.persist.model.SIF3Session;
import sif3.common.ws.BaseResponse;
import sif3.infra.common.env.mgr.BrokeredProviderEnvironmentManager;
import sif3.infra.common.env.mgr.ProviderManagerFactory;
import sif3.infra.common.interfaces.EnvironmentManager;
import sif3.infra.rest.client.EventClient;


/**
 * This is the main class each specific provider of a given SIF Object type must extends if it wants to publish events. With this class it
 * enforces implementation of all CRUD methods of the BaseProvider and additionally the methods for publishing events. The BaseEventProvider
 * will take care of all housekeeping things relating to the actual event publishing. Please refer to the Developer's Guide for some more
 * details about this class.
 * 
 * @author Joerg Huber
 *
 */
public abstract class BaseEventProvider<L> extends BaseProvider implements EventProvider<L>
{	
	/**
	 */
    public BaseEventProvider()
    {
	    super();
    }

    /**
     * Attempts to read the max objects per event value from the adapter property file. If no value is found it will use a default of 10.
     * If that behaviour needs to be changed for a particular provider then this method should be overridden by the particular provider
     * implementation.
     *  
     * @return See desc.
     */
    public int getMaxObjectsInEvent()
    {
    	return getServiceProperties().getPropertyAsInt(CommonConstants.EVENT_MAX_OBJ, getProviderName(), 10);
    }
        
    
    /**
     * This method retrieves all events to be published by calling the abstract method getSIFEvents(). The returned list
     * is then broadcasted to all zones known to the implementing agent.
     * 
     * @see #getSIFEvents
     */
    public void broadcastEvents()
    {
    	logger.debug("================================ broadcastEvents() called for provider "+getPrettyName());
		int totalRecords = 0;
		int failedRecords = 0;
		int maxNumObjPerEvent = getMaxObjectsInEvent();
		
		SIF3Session sif3Session = getActiveSession();
		if (sif3Session == null)
		{
			return; //cannot send events. Error already logged.
		}
		
		List<ServiceInfo> servicesForProvider = getServicesForProvider(sif3Session);
		
		// If there are no services for this provider defined then we don't need to get any events at all.
		if ((servicesForProvider == null) || (servicesForProvider.size() == 0))
		{
			logger.info("This emvironment does not have any zones and contexts defined for the "+getMultiObjectClassInfo().getObjectName() + " service. No events can be sent.");
			return;
		}		
		try
		{
			// Let's get the Event Client
			EventClient evtClient = new EventClient(getProviderEnvironment(), sif3Session, getServiceName(), getMarshaller());
			
			SIFEventIterator<L> iterator = getSIFEvents();
			if (iterator != null)
			{
				while (iterator.hasNext())
				{
					SIFEvent<L> sifEvents = null;
					try
					{
						sifEvents = iterator.getNextEvents(maxNumObjPerEvent);
						// This should not return null since the hasNext() returned true, but just in case we check
						// and exit the loop if it should return null. In this case we assume that there is no more
						// data. We also log an error to make the coder aware of the issue.
						if (sifEvents != null)
						{
							logger.debug("Number of "+getMultiObjectClassInfo().getObjectName()+" Objects in this Event: " + sifEvents.getListSize());
							for (ServiceInfo service : servicesForProvider)
							{
								// keep event action. Just in case the developer changes it in modifyBeforePublishing() which would confuse
								// everything.
								EventAction eventAction = sifEvents.getEventAction();
								if (hasAccess(service, eventAction))
								{
									SIFEvent<L> modifiedEvents = modifyBeforePublishing(sifEvents, service.getZone(), service.getContext());
									if (modifiedEvents != null)
									{
										//Just in case the developer has changed it. Should not be allowed :-)
										modifiedEvents.setEventAction(eventAction);
										
										if (!sendEvents(evtClient, modifiedEvents, service.getZone(), service.getContext()))
										{
											//Report back to the caller. This should also give the event back to the caller.
											onEventError(modifiedEvents, service.getZone(), service.getContext());
											failedRecords = failedRecords + ((modifiedEvents != null) ? modifiedEvents.getListSize() : 0);
										}
									}
								}
								else
								{
									logger.debug("The "+getProviderName()+" does not have the PROVIDE = APPROVED. No events are sent.");
									failedRecords = failedRecords + ((sifEvents != null) ? sifEvents.getListSize() : 0);
								}
							}
							
				            totalRecords = totalRecords + sifEvents.getListSize();
						}
						else
						{
							logger.error("iterator.hasNext() has returned true but iterator.getNextEvent() has retrurned null => no further events are broadcasted.");
							break;
						}
					}
					catch (Exception ex)
					{
						logger.error("Failed to retrieve next event for provider "+getPrettyName()+": "+ex.getMessage(), ex);					
						failedRecords = failedRecords + ((sifEvents != null) ? sifEvents.getListSize() : 0);
					}
				}
				iterator.releaseResources();
			}
			else
			{
				logger.info("getSIFEvents() for provider "+getPrettyName()+" returned null. Currently no events to be sent.");
			}
		}
		catch (Exception ex)
		{
			logger.error("Failed to retrieve events for provider "+getPrettyName()+": "+ex.getMessage(), ex);								
		}
		logger.info("Total SIF Event Objects broadcasted: "+totalRecords);
		logger.info("Total SIF Event Objects failed     : "+failedRecords);
    	logger.debug("================================ Finished broadcastEvents() for provider "+getPrettyName());
    }

    /**
     * If one doesn't want certain events to be published to a given zone then this method needs to be 
     * overridden. It allows to test for the event and zone and make the appropriate decision if the event
     * shall be sent.
     * 
     * @param event The event to be published to the zone.
     * @param zone The zone to which the event is published to.
     * 
     * @return TRUE: Event sent successfully. FALSE: Failed to send event. Error must be logged.
     */
    protected boolean sendEvents(EventClient evtClient, SIFEvent<?> sifEvents, SIFZone zone, SIFContext context)
    {
    	logger.debug(getPrettyName()+" sending a "+getServiceName()+" event with "+sifEvents.getListSize()+" sif objects.");
    	try
    	{
    		BaseResponse response = evtClient.sendEvents(sifEvents, zone, context);
    		if (response.hasError())
    		{
    			logger.error("Failed to send event: "+response.getError());
    			return false;
    		}
    		else
    		{
    			return true;
    		}
    	}
    	catch (Exception ex)
    	{
   			logger.error("An error occured sending an event message. See previous error log entries.", ex);
			return false;
    	}
    }
    
    private List<ServiceInfo> getServicesForProvider(SIF3Session sif3Session)
    {
    	if (sif3Session != null)
    	{
    		return sif3Session.getServiceInfoForService(getServiceName(), ServiceType.OBJECT);
    	}
    	
    	return null;
    }
    
    private SIF3Session getActiveSession()
    {
    	EnvironmentManager envMgr = ProviderManagerFactory.getEnvironmentManager();
    	if (envMgr != null) // we have a proper setup and are initialised.
    	{
    		// Note: Currently we only support events for Brokered Environments, so the EnvironmentManager should be of type 
    		//       BrokeredProviderEnvironmentManager
    		if (envMgr instanceof BrokeredProviderEnvironmentManager)
    		{
    			return ((BrokeredProviderEnvironmentManager)envMgr).getSIF3Session();
    		}
    		else
    		{
    			logger.error("Events are only supported for BROKERED environments. This provider is a DIRECT Environment.");
    		}
    	}
    	else
    	{
			logger.error("Environment Manager not initialised. Not connected to an environment. No active SIF3 Session.");
    	}
    	
    	return null;
    }
    
    private boolean hasAccess(ServiceInfo service, EventAction eventAction)
    {
    	// All that is required is PROVIDE right to be set to APPROVED.
    	return service.getRights().hasRight(AccessRight.PROVIDE, AccessType.APPROVED);
    	// Map eventAction (CREATE, UPDATE, DELETE) to an AccessRight (CREATE, UPDATE, DELETE)
//    	AccessRight right = null;
//    	if (eventAction != null)
//    	{
//	    	switch (eventAction)
//	    	{
//	    		case CREATE:
//	    			right = AccessRight.CREATE;
//	    			break;
//	    		case UPDATE:
//	    			right = AccessRight.UPDATE;
//	    			break;
//	    		case DELETE:
//	    			right = AccessRight.DELETE;
//	    			break;
//	    	}
//	    	return service.getRights().hasRight(right, AccessType.APPROVED);
//    	}
//    	
//    	// If we get here then the event type is not set! That is an issue, so we cannot allow access
//    	logger.error("No event action set in events. Must be either CREATE, UPDATE or DELETE. No events will be sent for this provider.");
//    	return false;
    }
}