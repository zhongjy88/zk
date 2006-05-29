/* UiEngineImpl.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Thu Jun  9 13:05:28     2005, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.zk.ui.impl;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;
import java.io.Writer;
import java.io.IOException;

import javax.servlet.jsp.el.VariableResolver;

import com.potix.lang.D;
import com.potix.lang.Classes;
import com.potix.lang.Objects;
import com.potix.lang.Threads;
import com.potix.lang.Exceptions;
import com.potix.lang.Expectable;
import com.potix.mesg.Messages;
import com.potix.util.prefs.Apps;
import com.potix.util.logging.Log;

import com.potix.zk.mesg.MZk;
import com.potix.zk.ui.*;
import com.potix.zk.ui.sys.*;
import com.potix.zk.ui.event.*;
import com.potix.zk.ui.metainfo.*;
import com.potix.zk.ui.ext.PostCreate;
import com.potix.zk.ui.util.Initiator;
import com.potix.zk.ui.util.Monitor;
import com.potix.zk.ui.util.Condition;
import com.potix.zk.ui.util.ForEach;
import com.potix.zk.ui.util.Configuration;
import com.potix.zk.au.*;

/**
 * An implementation of {@link UiEngine}.
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public class UiEngineImpl implements UiEngine {
	private static final Log log = Log.lookup(UiEngineImpl.class);

	/** A pool of idle EventProcessingThread. */
	private final List _evtthds = new LinkedList();
	/** A map of suspended processing:
	 * (Desktop desktop, Map(Object obj, List(EventProcessingThread)).
	 */
	private final Map _suspended = new HashMap();
	/** A map of resumed processing
	 * (Desktop desktop, List(EventProcessingThread)).
	 */
	private final Map _resumed = new HashMap();
	/** The maximal allowed # of event handling threads.*/
	private final int _maxEvtThds;

	public UiEngineImpl() {
		_maxEvtThds = Apps.getInteger("com.potix.zk.ui.event.numThreads", 100);
	}

	//-- UiEngine --//
	public void start(WebApp wapp) {
	}
	public void stop(WebApp wapp) {
		synchronized (_evtthds) {
			for (Iterator it = _evtthds.iterator(); it.hasNext();)
				((EventProcessingThread)it.next()).cease();
			_evtthds.clear();
		}

		synchronized (_suspended) {
			for (Iterator it = _suspended.values().iterator(); it.hasNext();) {
				final Map map = (Map)it.next();
				synchronized (map) {
					for (Iterator i2 = map.values().iterator(); i2.hasNext();) {
						final List list = (List)i2.next();
						for (Iterator i3 = list.iterator(); i3.hasNext();)
							((EventProcessingThread)i3.next()).cease();
					}
				}
			}
			_suspended.clear();
		}
		synchronized (_resumed) {
			for (Iterator it = _resumed.values().iterator(); it.hasNext();) {
				final List list = (List)it.next();
				synchronized (list) {
					for (Iterator i2 = list.iterator(); i2.hasNext();)
						((EventProcessingThread)i2.next()).cease();
				}
			}
			_resumed.clear();
		}
	}

	public void cleanup(Desktop desktop) {
		if (log.debugable()) log.debug("Cleanup "+desktop);

		final Configuration conf = desktop.getWebApp().getConfiguration();
		final Map map;
		synchronized (_suspended) {
			map = (Map)_suspended.remove(desktop);
		}
		if (map != null) {
			synchronized (map) {
				for (Iterator it = map.values().iterator(); it.hasNext();) {
					final List list = (List)it.next();
					for (Iterator i2 = list.iterator(); i2.hasNext();) {
						final EventProcessingThread evtthd =
							(EventProcessingThread)i2.next();
						evtthd.ceaseSilently();
						conf.invokeEventThreadResumes(
							evtthd.getComponent(), evtthd.getEvent(), true);
					}
				}
			}
		}

		final List list;
		synchronized (_resumed) {
			list = (List)_resumed.remove(desktop);
		}
		if (list != null) {
			synchronized (list) {
				for (Iterator it = list.iterator(); it.hasNext();) {
					final EventProcessingThread evtthd =
						(EventProcessingThread)it.next();
					evtthd.ceaseSilently();
					conf.invokeEventThreadResumes(
						evtthd.getComponent(), evtthd.getEvent(), true);
				}
			}
		}
	}

	private static UiVisualizer getCurrentVisualizer() {
		final ExecutionCtrl execCtrl = ExecutionsCtrl.getCurrentCtrl();
		if (execCtrl == null)
			throw new IllegalStateException("Components can be accessed only in event listeners");
		return (UiVisualizer)execCtrl.getVisualizer();
	}
	public void pushOwner(Component comp) {
		getCurrentVisualizer().pushOwner(comp);
	}
	public void popOwner() {
		getCurrentVisualizer().popOwner();
	}
	public void addInvalidate(Page page) {
		if (page == null)
			throw new NullPointerException();
		getCurrentVisualizer().addInvalidate(page);
	}
	public void addInvalidate(Component comp, Component.Range range) {
		if (comp == null)
			throw new NullPointerException();
		getCurrentVisualizer().addInvalidate(comp, range);
	}
	public void addSmartUpdate(Component comp, String attr, String value) {
		if (comp == null)
			throw new NullPointerException();
		getCurrentVisualizer().addSmartUpdate(comp, attr, value);
	}
	public void addResponse(String key, AuResponse response) {
		getCurrentVisualizer().addResponse(key, response);
	}
	public void addMoved(Component comp, boolean newAttached) {
		if (comp == null)
			throw new NullPointerException();
		getCurrentVisualizer().addMoved(comp, newAttached);
	}

	//-- Creating a new page --//
	public void execNewPage(Execution exec, Page page, Writer out)
	throws IOException {
		//It is possible this method is invoked when processing other exec
		final ExecutionCtrl oldexc = ExecutionsCtrl.getCurrentCtrl();
		final UiVisualizer olduv =
			oldexc != null ? (UiVisualizer)oldexc.getVisualizer(): null;
		final UiVisualizer uv;
		if (olduv != null) uv = doReactivate(exec, olduv);
		else uv = doActivate(exec, null);
		try {
			if (olduv != null) olduv.setOwner(page);

			//Cycle 1: Creates all components
			((ExecutionCtrl)exec).setCurrentPage(page);

			final PageDefinition pagedef = page.getDefinition();
			final Initiators inits = Initiators.doInit(pagedef, page);
			try {
				if (pagedef != null) pagedef.init(page);
				else ((PageCtrl)page).init(null, null, null);

				execCreate(page, pagedef, null);
			} catch(Throwable ex) {
				inits.doCatch(ex);
				throw UiException.Aide.wrap(ex);
			} finally {
				inits.doFinally();
			}

			//Cycle 2: process pending events
			final Desktop desktop = exec.getDesktop();
			Event event = nextEvent(uv);
			do {
				for (; event != null; event = nextEvent(uv)) {
					process(desktop, event);
					//Unlike execUpdate, we don't cache exception here
				}

				//Cycle 2a: processing resumed event processing
				resumeAll(desktop, uv, null);
			} while ((event = nextEvent(uv)) != null);

			//Cycle 3: Redraw the page (and responses)
			List responses = uv.getResponses();

			final AbortingReason aborting = uv.getAbortingReason();
			if (aborting != null)
				responses.add(0, aborting.getResponse());

			if (olduv != null && olduv.addToFirstAsyncUpdate(responses))
				responses = null;
				//A new ZK page might be included by an async update
				//(example: ZUL's include).
				//If so, we cannot generate the responses in the page.
				//Rather, we shall add them to the async update.

			((PageCtrl)page).redraw(responses, out);
		} catch (IOException ex) {
			throw UiException.Aide.wrap(ex);
		} finally {
			if (olduv != null) doDereactivate(exec, olduv);
			else doDeactivate(exec);
		}
	}
	private static final Event nextEvent(UiVisualizer uv) {
		final Event evt = ((ExecutionCtrl)uv.getExecution()).getNextEvent();
		return evt != null && !uv.isAborting() ? evt: null;
	}
	/** Cycle 1:
	 * Creates all child components defined in the specified definition.
	 * @return the first component being created.
	 */
	private static final Component execCreate(Page page,
	InstanceDefinition instdef, Component parent)
	throws IOException {
		Component firstCreated = null;
		final PageDefinition pagedef = instdef.getPageDefinition();
			//note: don't use page.getDefinition because createComponents
			//might be called from a page other than instance's
		for (Iterator it = instdef.getChildren().iterator(); it.hasNext();) {
			final Object obj = it.next();
			if (obj instanceof InstanceDefinition) {
				final InstanceDefinition childdef = (InstanceDefinition)obj;
				final ForEach forEach = childdef.getForEach(pagedef, page, parent);
				if (forEach == null) {
					if (isEffective(childdef, pagedef, page, parent)) {
						final Component child =
							execCreateChild(page, parent, childdef);
						if (firstCreated == null) firstCreated = child;
					}
				} else {
					while (forEach.next()) {
						if (isEffective(childdef, pagedef, page, parent)) {
							final Component child =
								execCreateChild(page, parent, childdef);
							if (firstCreated == null) firstCreated = child;
						}
					}
				}
			} else if (obj instanceof Script) {
				final Script script = (Script)obj;
				if (isEffective(script, pagedef, page, parent))
					page.interpret(parent, script.getScript());
			} else {
				throw new IllegalStateException("Unknown object: "+obj);
			}
		}
		return firstCreated;
	}
	private static Component execCreateChild(Page page, Component parent,
	InstanceDefinition childdef) throws IOException {
		if (ComponentDefinition.ZK == childdef.getComponentDefinition()) {
			return execCreate(page, childdef, parent);
		} else {
			final Component child = childdef.newInstance(page);
			if (parent != null) child.setParent(parent);
			else child.setPage(page);

			childdef.applyProperties(child);
			childdef.applyCustomAttributes(child);

			if (child instanceof PostCreate)
				((PostCreate)child).postCreate();

			execCreate(page, childdef, child); //recursive

			if (Events.isListenerAvailable(child, "onCreate", false))
				Events.postEvent(new CreateEvent("onCreate", child));

			return child;
		}
	}
	private static final boolean isEffective(Condition cond,
	PageDefinition pagedef, Page page, Component comp) {
		return comp != null ? cond.isEffective(comp):
			cond.isEffective(pagedef, page);
	}

	//-- might be called either from execNewPage or execUpdate --//
	public Component createComponents(Execution exec,
	PageDefinition pagedef, Page page, Component parent, Map params) {
		if (page == null) {
			if (parent != null)
				page = parent.getPage();
			if (page == null) {
				page = ((ExecutionCtrl)exec).getCurrentPage();
				if (page == null)
					throw new IllegalStateException("No current page");
			}
		} else if (parent != null) {
			page = parent.getPage();
		}

		if (pagedef == null) {
			pagedef = page.getDefinition();
			if (pagedef == null)
				return null;
		}

		if (parent == null)
			parent = ((PageCtrl)page).getDefaultParent();

		final ExecutionCtrl execCtrl = (ExecutionCtrl)exec;
		if (!execCtrl.isActivated())
			throw new IllegalStateException("Not activated yet");

		final Page old = execCtrl.getCurrentPage();
		final PageDefinition olddef = execCtrl.getCurrentPageDefinition(false);
		execCtrl.setCurrentPage(page);
		execCtrl.setCurrentPageDefinition(pagedef);
		exec.pushArg(params != null ? params: Collections.EMPTY_MAP);
		final Initiators inits = Initiators.doInit(pagedef, page);
		try {
			return execCreate(page, pagedef, parent);
		} catch (Throwable ex) {
			inits.doCatch(ex);
			throw UiException.Aide.wrap(ex);
		} finally {
			exec.popArg();
			execCtrl.setCurrentPage(old); //restore it
			execCtrl.setCurrentPageDefinition(olddef); //restore it
			inits.doFinally();
		}
	}

	public void sendRedirect(String uri, String target) {
		final UiVisualizer uv = getCurrentVisualizer();
		uv.setAbortingReason(
			new AbortBySendRedirect(uv.getExecution().encodeURL(uri), target));
	}

	//-- Asynchronous updates --//
	public void execUpdate(Execution exec, List requests, Writer out)
	throws IOException {
		if (requests == null)
			throw new IllegalArgumentException("null requests");
		assert D.OFF || ExecutionsCtrl.getCurrentCtrl() == null:
			"Impossible to re-activate for update";

		final UiVisualizer uv = doActivate(exec, requests);
		if (uv == null)
			return; //done (request is added to the exec currently activated)

		final Desktop desktop = exec.getDesktop();
		final Monitor monitor = desktop.getWebApp().getConfiguration().getMonitor();
		if (monitor != null) {
			try {
				monitor.beforeUpdate(desktop, requests);
			} catch (Throwable ex) {
				log.error(ex);
			}
		}
		final RequestQueue rque = ((DesktopCtrl)exec.getDesktop()).getRequestQueue();
		try {
			final StringBuffer errsb = new StringBuffer(80);
			final long tmexpired = System.currentTimeMillis() + 3000;
				//Tom Yeh: 20060120
				//Don't process all requests if this thread has processed
				//a while. Thus, user could see the response sooner.
			for (AuRequest request; System.currentTimeMillis() < tmexpired
			&& (request = rque.nextRequest()) != null;) {
				//Cycle 1: Process one request
				//Don't process more such that requests will be queued
				//adn we have the chance to optimize them
				try {
					process(exec, request, errsb.length() > 0);
				} catch (ComponentNotFoundException ex) {
					//possible because the previous might remove some comp
					//so ignore it
					if (log.debugable()) log.debug("Component not found: "+request);
				} catch (Throwable ex) {
					handleError(ex, uv, errsb);
				}

				//Cycle 2: Process any pending events posted by components
				Event event = nextEvent(uv);
				do {
					for (; event != null; event = nextEvent(uv)) {
						try {
							process(desktop, event);
						} catch (Throwable ex) {
							handleError(ex, uv, errsb);
						}
					}

					//Cycle 2a: processing resumed event processing
					resumeAll(desktop, uv, errsb);
				} while ((event = nextEvent(uv)) != null);
			}

			//Cycle 3: Generate output
			if (!uv.isAborting()) {
				if (errsb.length() > 0)
					uv.addResponse(null, new AuAlert(errsb.toString()));

				final List responses = uv.getResponses();
				if (rque.hasRequest())
					responses.add(new AuEcho());
				response(responses, out);
				if (log.debugable())
					if (responses.size() < 5 || log.finerable()) log.debug("Responses: "+responses);
					else log.debug("Responses: "+responses.subList(0, 5)+"...");
			}

			final AbortingReason aborting = uv.getAbortingReason();
			if (aborting != null) response(aborting.getResponse(), out);

			out.flush();
				//flush before deactivating to make sure it has been sent
		} finally {
			doDeactivate(exec);

			if (monitor != null) {
				try {
					monitor.afterUpdate(desktop);
				} catch (Throwable ex) {
					log.error(ex);
				}
			}
		}
	}
	private static final
	void handleError(Throwable ex, UiVisualizer uv, StringBuffer errsb) {
		final Throwable t = Exceptions.findCause(ex, Expectable.class);
		if (t == null) {
			log.realCause(ex);
		} else {
			ex = t;
			if (log.debugable()) log.debug(Exceptions.getRealCause(ex));
		}

		final String msg = Exceptions.getMessage(ex);
		if (ex instanceof WrongValueException) {
			final Component comp = ((WrongValueException)ex).getComponent();
			if (comp != null) {
				uv.addResponse("wrongValue", new AuAlert(comp, msg));
				return;
			}
		}

		if (errsb.length() > 0) errsb.append('\n');
		errsb.append(msg);
	}
	/** Processing the request and stores result into UiVisualizer.
	 * @param everError whether any error ever occured before processing this
	 * request.
	 */
	private void process(Execution exec, AuRequest request, boolean everError) {
		if (log.debugable()) log.debug("Processing request: "+request);

		final ExecutionCtrl execCtrl = (ExecutionCtrl)exec;
		execCtrl.setCurrentPage(request.getPage());
		request.getCommand().process(request, everError);
	}
	/** Processing the event and stores result into UiVisualizer. */
	private void process(Desktop desktop, Event event) {
		if (log.debugable()) log.debug("Processing event: "+event);
		final Component comp = event.getTarget();
		if (comp != null) {
			//Note: a component might be removed before event being processed
			if (comp.getPage() != null)
				processEvent(comp, event);
		} else {
			//since an event might change the page/desktop/component relation,
			//we copy roots first
			final List roots = new LinkedList();
			for (Iterator it = desktop.getPages().iterator(); it.hasNext();) {
				roots.addAll(((Page)it.next()).getRoots());
			}
			for (Iterator it = roots.iterator(); it.hasNext();) {
				final Component c = (Component)it.next();
				if (c.getPage() != null) //might be removed, so check first
					processEvent(c, event);
			}
		}
	}

	public void wait(Object obj) throws InterruptedException {
		if (obj == null)
			throw new IllegalArgumentException("obj cannot be null");

		final Thread thd = Thread.currentThread();
		if (!(thd instanceof EventProcessingThread))
			throw new UiException("suspend can be called only in processing an event");
		if (D.ON && log.finerable()) log.finer("Suspend "+thd+" on "+obj);

		final Execution exec = Executions.getCurrent();
		final Desktop desktop = exec.getDesktop();

		final EventProcessingThread evtthd = (EventProcessingThread)thd;
		desktop.getWebApp().getConfiguration()
			.invokeEventThreadSuspends(
				evtthd.getComponent(), evtthd.getEvent(), obj);
			//it might throw an exception, so process it before updating
			//_suspended

		Map map;
		synchronized (_suspended) {
			map = (Map)_suspended.get(desktop);
			if (map == null)
				_suspended.put(desktop, map = new HashMap(3));
		}
		synchronized (map) {
			List list = (List)map.get(obj);
			if (list == null)
				map.put(obj, list = new LinkedList());
			list.add(evtthd);
		}
		EventProcessingThread.doSuspend();
	}
	public void notify(Object obj) {
		if (obj == null)
			throw new IllegalArgumentException("obj cannot be null");

		final Execution exec = Executions.getCurrent();
		if (exec == null)
			throw new UiException("resume can be called only in processing a request");
		notify(exec.getDesktop(), obj);
	}
	public void notify(Page page, Object obj) {
		if (page == null || obj == null)
			throw new IllegalArgumentException("page and obj cannot be null");
		notify(page.getDesktop(), obj);
	}
	public void notifyAll(Object obj) {
		if (obj == null)
			throw new IllegalArgumentException("obj cannot be null");

		final Execution exec = Executions.getCurrent();
		if (exec == null)
			throw new UiException("resume can be called only in processing a request");
		notifyAll(exec.getDesktop(), obj);
	}
	public void notifyAll(Page page, Object obj) {
		if (page == null || obj == null)
			throw new IllegalArgumentException("page and obj cannot be null");
		notifyAll(page.getDesktop(), obj);
	}

	private void notify(Desktop desktop, Object obj) {
		final Map map;
		synchronized (_suspended) {
			map = (Map)_suspended.get(desktop);
			if (map == null) return; //nothing to notify
		}

		final EventProcessingThread evtthd;
		synchronized (map) {
			final List list = (List)map.get(obj);
			if (list == null) return; //nothing to notify

			//Note: list is never empty
			evtthd = (EventProcessingThread)list.remove(0);
			if (list.isEmpty()) map.remove(obj); //clean up
		}
		addResumed(desktop, evtthd);
	}
	private void notifyAll(Desktop desktop, Object obj) {
		final Map map;
		synchronized (_suspended) {
			map = (Map)_suspended.get(desktop);
			if (map == null) return; //nothing to notify
		}

		final List list;
		synchronized (map) {
			list = (List)map.remove(obj);
			if (list == null) return; //nothing to notify
		}
		for (Iterator it = list.iterator(); it.hasNext();)
			addResumed(desktop, (EventProcessingThread)it.next());
	}
	/** Adds to _resumed */
	private void addResumed(Desktop desktop, EventProcessingThread evtthd) {
		if (D.ON && log.finerable()) log.finer("Ready to resume "+evtthd);
		List list;
		synchronized (_resumed) {
			list = (List)_resumed.get(desktop);
			if (list == null)
				_resumed.put(desktop, list = new LinkedList());
		}
		synchronized (list) {
			list.add(evtthd);
		}
	}

	/** Does the real resume.
	 * Note: {@link #resume} only puts a thread into a resume queue in execution.
	 */
	private void resumeAll(Desktop desktop, UiVisualizer uv, StringBuffer errsb) {
		//We have to loop because a resumed thread might resume others
		for (;;) {
			final List list;
			synchronized (_resumed) {
				list = (List)_resumed.remove(desktop);
				if (list == null) return; //nothing to resume; done
			}

			synchronized (list) {
				for (Iterator it = list.iterator(); it.hasNext();) {
					final EventProcessingThread evtthd =
						(EventProcessingThread)it.next();
					if (D.ON && log.finerable()) log.finer("Resume "+evtthd);
					if (uv.isAborting()) {
						evtthd.ceaseSilently();
					} else {
						try {
							if (evtthd.doResume()) //wait it complete or suspend again
								recycleEventThread(evtthd); //completed
						} catch (Throwable ex) {
							recycleEventThread(evtthd);
							if (errsb == null) {
								log.error("Unable to resume "+evtthd, ex);
								throw UiException.Aide.wrap(ex);
							}
							handleError(ex, uv, errsb);
						}
					}
				}
			}
		}
	}
	/** Process an event. */
	private void processEvent(Component comp, Event event) {
		if (comp.getPage() == null) {
			if (D.ON && log.debugable()) log.debug("Event is ignored due to dead");
			return; //nothing to do
		}

		final List inits = comp.getDesktop().getWebApp()
			.getConfiguration().newEventThreadInits(comp, event);

		EventProcessingThread evtthd = null;
		synchronized (_evtthds) {
			if (!_evtthds.isEmpty())
				evtthd = (EventProcessingThread)_evtthds.remove(0);
		}

		if (evtthd == null)
			evtthd = new EventProcessingThread();

		try {
			if (evtthd.processEvent(comp, event, inits))
				recycleEventThread(evtthd);
		} catch (Throwable ex) {
			recycleEventThread(evtthd);
			throw UiException.Aide.wrap(ex);
		}
	}
	private void recycleEventThread(EventProcessingThread evtthd) {
		if (!evtthd.isCeased()) {
			if (evtthd.isIdle()) {
				synchronized (_evtthds) {
					if (_evtthds.size() < _maxEvtThds) {
						_evtthds.add(evtthd); //return to pool
						return; //done
					}
				}
			}
			evtthd.ceaseSilently();
		}
	}

	//-- Generate output from a response --//
	public void response(AuResponse response, Writer out)
	throws IOException {
		final StringBuffer outsb = new StringBuffer(1024*4);
		output(response, outsb);
		out.write(outsb.toString());
	}
	public void response(List responses, Writer out)
	throws IOException {
		final StringBuffer outsb = new StringBuffer(1024*8);
		for (Iterator it = responses.iterator(); it.hasNext();) {
			final AuResponse response = (AuResponse)it.next();
			output(response, outsb);
		}
		if (D.ON && log.finerable()) log.finer(outsb);
		out.write(outsb.toString());
	}
	private static void output(AuResponse response, StringBuffer outsb) {
		outsb.append("\n<r><c>")
			.append(response.getCommand())
			.append("</c>");
		final String[] data = response.getData();
		if (data != null) {
			for (int j = 0; j < data.length; ++j) {
				outsb.append("\n<d>");
				encodeXML(outsb, data[j]);
				outsb.append("</d>");
			}
		}
		outsb.append("\n</r>");
	}
	private static void encodeXML(StringBuffer outsb, String data) {
		if (data == null || data.length() == 0)
			return;

		//20051208: Tom Yeh
		//The following codes are tricky.
		//Reason:
		//1. nested CDATA is not allowed
		//2. Firefox (1.0.7)'s XML parser cannot handle over 4096 chars
		//	if CDATA is not used
		int j = 0;
		for (int k; (k = data.indexOf("]]>", j)) >= 0;) {
			encodeByCData(outsb, data.substring(j, k));
			outsb.append("]]&gt;");
			j = k + 3;
		}
		encodeByCData(outsb, data.substring(j));
	}
	private static void encodeByCData(StringBuffer outsb, String data) {
		for (int j = data.length(); --j >= 0;) {
			final char cc = data.charAt(j);
			if (cc == '<' || cc == '>' || cc == '&') {
				outsb.append("<![CDATA[").append(data).append("]]>");
				return;
			}
		}
		outsb.append(data);
	}

	public void activate(Execution exec) {
		assert D.OFF || ExecutionsCtrl.getCurrentCtrl() == null:
			"Impossible to re-activate for update";
		doActivate(exec, null);
	}
	public void deactivate(Execution exec) {
		doDeactivate(exec);
	}

	//-- Common private utilities --//
	/** Activates the specified execution for processing.
	 *
	 * @param requests a list of requests to process.
	 * Activation assumes it is asynchronous update if it is not null.
	 * @return the exec info if the execution is granted;
	 * null if request has been added to the exec currently activated
	 */
	private static UiVisualizer doActivate(Execution exec, List requests) {
		final Desktop desktop = exec.getDesktop();
		final DesktopCtrl desktopCtrl = (DesktopCtrl)desktop;
		final Session sess = desktop.getSession();
		final boolean asyncupd = requests != null;
		if (log.finerable()) log.finer("Activating "+desktop);

		assert D.OFF || Executions.getCurrent() == null: "Use doReactivate instead";

		final boolean inProcess = asyncupd
			&& desktopCtrl.getRequestQueue().addRequests(requests);

		//lock desktop
		final UiVisualizer uv;
		final Map eis = getVisualizers(sess);
		synchronized (eis) {
			for (;;) {
				final UiVisualizer old = (UiVisualizer)eis.get(desktop);
				if (old == null) break; //grantable

				if (inProcess) return null;

				try {
					eis.wait(120*1000);
				} catch (InterruptedException ex) {
					throw UiException.Aide.wrap(ex);
				}
			}

			//grant
			if (asyncupd) desktopCtrl.getRequestQueue().setInProcess();
				//set the flag asap to free more following executions
			eis.put(desktop, uv = new UiVisualizer(exec, asyncupd));
			desktopCtrl.setExecution(exec);
		}

		final ExecutionCtrl execCtrl = (ExecutionCtrl)exec;
		execCtrl.setVisualizer(uv);
		ExecutionsCtrl.setCurrent(exec);

		try {
			execCtrl.onActivate();
		} catch (Throwable ex) {
			doDeactivate(exec);
			throw UiException.Aide.wrap(ex);
		}
		return uv;
	}
	/** Deactivates the execution. */
	private static final void doDeactivate(Execution exec) {
		final Desktop desktop = exec.getDesktop();
		final Session sess = desktop.getSession();
		if (log.finerable()) log.finer("Deactivating "+desktop);

		final ExecutionCtrl execCtrl = (ExecutionCtrl)exec;
		try {
			execCtrl.onDeactivate();
		} catch (Throwable ex) {
			log.warning("Ignored: failed to deactivate "+desktop, ex);
		}

		//Unlock desktop
		final Map eis = getVisualizers(sess);
		synchronized (eis) {
			final Object o = eis.remove(desktop);
			assert D.OFF || o != null;
			((DesktopCtrl)desktop).setExecution(null);
			eis.notify(); //wakeup doActivate's wait
		}

		execCtrl.setCurrentPage(null);
		execCtrl.setVisualizer(null);
		ExecutionsCtrl.setCurrent(null);

		final SessionCtrl sessCtrl = (SessionCtrl)sess;
		if (sessCtrl.isInvalidated()) sessCtrl.invalidateNow();
	}
	/** Re-activates for another execution. It is callable only for
	 * creating new page (execNewPage). It is not allowed for async-update.
	 * <p>Note: doActivate cannot handle reactivation. In other words,
	 * the caller has to detect which method to use.
	 */
	private static UiVisualizer doReactivate(Execution curExec, UiVisualizer olduv) {
		final Desktop desktop = curExec.getDesktop();
		final Session sess = desktop.getSession();
		if (log.finerable()) log.finer("Re-activating "+desktop);

		assert D.OFF || olduv.getExecution().getDesktop() == desktop:
			"old dt: "+olduv.getExecution().getDesktop()+", new:"+desktop;

		final UiVisualizer uv = new UiVisualizer(olduv, curExec);
		final Map eis = getVisualizers(sess);
		synchronized (eis) {
			final Object o = eis.put(desktop, uv);
			if (o != olduv)
				throw new InternalError(); //wrong olduv

			((DesktopCtrl)desktop).setExecution(curExec);
		}

		final ExecutionCtrl curCtrl = (ExecutionCtrl)curExec;
		curCtrl.setVisualizer(uv);
		ExecutionsCtrl.setCurrent(curExec);

		try {
			curCtrl.onActivate();
		} catch (Throwable ex) {
			doDereactivate(curExec, olduv);
			throw UiException.Aide.wrap(ex);
		}
		return uv;
	}
	/** De-reactivated exec. Work with {@link #doReactivate}.
	 */
	private static void doDereactivate(Execution curExec, UiVisualizer olduv) {
		if (olduv == null) throw new IllegalArgumentException("null");

		final Desktop desktop = curExec.getDesktop();
		final Session sess = desktop.getSession();
		if (log.finerable()) log.finer("Deactivating "+desktop);

		final ExecutionCtrl curCtrl = (ExecutionCtrl)curExec;
		try {
			curCtrl.onDeactivate();
		} catch (Throwable ex) {
			log.warning("Ignored: failed to deactivate "+desktop, ex);
		}
		curCtrl.setCurrentPage(null);
		curCtrl.setVisualizer(null);

		final Execution oldexec = olduv.getExecution();
		final Map eis = getVisualizers(sess);
		synchronized (eis) {
			eis.put(desktop, olduv);
			((DesktopCtrl)desktop).setExecution(oldexec);
		}
		ExecutionsCtrl.setCurrent(oldexec);
	}
	/** Returns a map of (Page, UiVisualizer). */
	private static Map getVisualizers(Session sess) {
		synchronized (sess) {
			final String attr = "com.potix.zk.ui.Visualizers";
			Map eis = (Map)sess.getAttribute(attr);
			if (eis == null)
				sess.setAttribute(attr, eis = new HashMap());
			return eis;
		}
	}
}
