/* Jsps.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Sat Aug 30 01:10:34     2003, Created by tomyeh@potix.com
}}IS_NOTE

Copyright (C) 2003 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package com.potix.web.servlet.jsp;

import javax.servlet.jsp.JspFactory;
import com.potix.web.servlet.jsp.jasper.Jaspers;

/**
 * JSP relevant utilities.
 *
 * @author <a href="mailto:tomyeh@potix.com">tomyeh@potix.com</a>
 */
public class Jsps {
	protected Jsps() {} //prevent from instantiated
	/** Maps a JSP URI to a Java name. */
	public static String mapJspToJavaName(String jspURI) {
		return Jaspers.mapJspToJavaName("com.potix.jsp", jspURI);
			//FUTURE: We might support other JSP engine
	}

	static {
		JspFactory.setDefaultFactory(
			new JspFactoryImpl(JspFactory.getDefaultFactory()));
			//Tom Yeh: to intercept how lifecycle of JSP context
	}
}
