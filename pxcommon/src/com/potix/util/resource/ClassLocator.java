/* ClassLocator.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Tue Aug 30 09:56:06     2005, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.util.resource;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedList;
import java.util.List;
import java.util.Collections;
import java.util.Iterator;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;

import com.potix.lang.D;
import com.potix.util.CollectionsX;
import com.potix.util.logging.Log;
import com.potix.idom.Document;
import com.potix.idom.Element;
import com.potix.idom.util.IDOMs;
import com.potix.idom.input.SAXBuilder;

/**
 * The locator searches the current thread's context class loader,
 * and then this class's class loader.
 *
 * <p>It is important to use this locator if you want to load something
 * in other jar files.
 *
 * <p>Besides {@link Locator}, it also provides additional methods,
 * such as {@link #getResources}.
 *
 * <p>Since this locator is used frequently, {@link Locators#getDefault}
 * is provided to return an instance of this class,
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public class ClassLocator implements Locator {
	private static final Log log = Log.lookup(ClassLocator.class);

	/** Returns an enumeration of resources.
	 * Unlike {@link #getDependentXmlResources}, it doesn't resolve the dependence
	 * among the resouces.
	 *
	 * @param name the resouce name, such as "metainfo/i3-com.xml".
	 */
	public Enumeration getResources(String name) throws IOException {
		name = resolveName(name);
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl != null) {
			final Enumeration en = cl.getResources(name);
			if (en.hasMoreElements()) return en;
		}
		cl = ClassLocator.class.getClassLoader();
		if (cl != null) {
			final Enumeration en = cl.getResources(name);
			if (en.hasMoreElements()) return en;
		}
		return ClassLoader.getSystemResources(name);
	}
	/** Returns a set of resources after resolving the dependence.
	 * The resource is returned in the format of {@link Document}
	 * In other words, the returned value is a set of {@link Document}.
	 *
	 * <p>To resolve the dependence, it assumes each resource has two
	 * element whose name is identified by elName and elDepends.
	 * The elName element specifies the unique name of each resource.
	 * The elDepends element specifies a list of names of resources
	 * that this resource depends on. If not found, it assumes it could
	 * be loaded first.
	 *
	 * @param name the resouce name, such as "metainfo/i3-comp.xml".
	 * @param elName the element used to specify the name.
	 * @param elDepends the element used to specify the dependence.
	 */
	public List getDependentXmlResources(String name, String elName,
	String elDepends) throws IOException {
		final Map rcmap = new LinkedHashMap();
		for (Enumeration en = getResources(name); en.hasMoreElements();) {
			final URL url = (URL)en.nextElement();
			final XmlResource xr = new XmlResource(url, elName, elDepends);
			final XmlResource old = (XmlResource)rcmap.put(xr.name, xr);
			if (old != null)
				log.warning("Replicate resource: "+xr.name
					+"\nOverwrite "+old.url+"\nwith "+xr.url);
			//it is possible if pxcommon.jar is placed in both
			//WEB-INF/lib and shared/lib, i.e., appear twice in the class path
			//We overwrite because the order is the parent class loader first
			//so WEB-INF/lib is placed after
		}
		if (D.ON && rcmap.isEmpty() && log.debugable()) log.debug("No resouce is found for "+name);

		final List rcs = new LinkedList(); //a list of Document
		final Set resolving = new LinkedHashSet();
			//a set of names used to prevent dead-loop
		while (!rcmap.isEmpty()) {
			final Iterator it = rcmap.values().iterator();
			final XmlResource xr = (XmlResource)it.next();
			it.remove();
			resolveDependency(xr, rcs, rcmap, resolving);
			assert D.OFF || resolving.isEmpty();
		}
		return rcs;
	}
	private static
	void resolveDependency(XmlResource xr, List rcs, Map rcmap, Set resolving) {
		if (!resolving.add(xr.name))
			throw new IllegalStateException("Recusrive reference among "+resolving);

		for (Iterator it = xr.depends.iterator(); it.hasNext();) {
			final String nm = (String)it.next();
			final XmlResource dep = (XmlResource)rcmap.remove(nm);
			if (dep != null) //not resolved yet
				resolveDependency(dep, rcs, rcmap, resolving); //recusrively
		}

		rcs.add(xr.document);
		resolving.remove(xr.name);

		if (D.ON && log.debugable()) log.debug("Adding resolved resource: "+xr.name);
	}
	/** Info used with getDependentXmlResource. */
	private static class XmlResource {
		private final String name;
		private final URL url;
		private final Document document;
		private final List depends;
		private XmlResource(URL url, String elName, String elDepends)
		throws IOException{
			if (D.ON && log.debugable()) log.debug("Loading "+url);
			try {
				this.document = new SAXBuilder(false, false, true).build(url);
			} catch (Exception ex) {
				if (ex instanceof IOException) throw (IOException)ex;
				if (ex instanceof RuntimeException) throw (RuntimeException)ex;
				final IOException ioex = new IOException("Unable to load "+url);
				ioex.initCause(ex);
				throw ioex;
			}

			this.url = url;
			final Element root = this.document.getRootElement();
			this.name = IDOMs.getRequiredElementValue(root, elName);
			final String deps = root.getElementValue(elDepends, true);
			if (deps == null || deps.length() == 0) {
				this.depends = Collections.EMPTY_LIST;
			} else {
				this.depends = new LinkedList();
				CollectionsX.parse(this.depends, deps, ',');
				if (D.ON && log.finerable()) log.finer(this.name+" depends on "+this.depends);
			}
		}
		public String toString() {
			return "["+name+": "+url+" depends on "+depends+']';
		}
	};

	//-- Locator --//
	public URL getResource(String name) {
		final ClassLoader cl = Thread.currentThread().getContextClassLoader();
		final URL url = cl != null ? cl.getResource(resolveName(name)): null;
		return url != null ? url: ClassLocator.class.getResource(name);
	}
	public InputStream getResourceAsStream(String name) {
		final ClassLoader cl = Thread.currentThread().getContextClassLoader();
		final InputStream is =
			cl != null ? cl.getResourceAsStream(resolveName(name)): null;
		return is != null ? is: ClassLocator.class.getResourceAsStream(name);
	}
	private static String resolveName(String name) {
		return name != null && name.startsWith("/") ?
			name.substring(1): name;
	}

	//-- Object --//
	public int hashCode() {
		return 1123;
	}
	public boolean equals(Object o) {
		return o instanceof ClassLocator;
	}
}
